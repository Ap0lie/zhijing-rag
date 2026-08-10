package com.example.rag.pipeline.parser;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProcessingException;
import com.example.rag.pipeline.ParserProviderKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextDocumentParserTests {

    private static final ChunkingProfile PROFILE =
            ChunkingProfile.phase4Default();
    private final TextDocumentParser parser = new TextDocumentParser();

    @TempDir
    Path directory;

    @Test
    void parsesUtf8TxtAndProducesStableLineLocators() throws Exception {
        String value = """
                第一章 平台目标

                这是中文优先、兼容英文的知识库说明。The retrieval pipeline keeps exact source locations.

                1. 使用小块检索
                2. 返回父块上下文
                """;
        Path path = write("guide.txt", value.getBytes(StandardCharsets.UTF_8));

        ParsedDocument first = parse(
                path,
                DocumentFormat.TXT,
                "text/plain",
                ParserProviderKind.TEXT
        );
        ParsedDocument second = parse(
                path,
                DocumentFormat.TXT,
                "text/plain",
                ParserProviderKind.TEXT
        );

        assertThat(first.documentFormat()).isEqualTo(DocumentFormat.TXT);
        assertThat(first.parserProvider()).isEqualTo(ParserProviderKind.TEXT);
        assertThat(first.pageCount()).isZero();
        assertThat(first.sourceUnits()).isNotEmpty();
        assertThat(first.contentBlocks())
                .allSatisfy(block -> assertThat(block.sourceSpan().locatorKind())
                        .isIn(
                                SourceLocatorKind.LINE_RANGE,
                                SourceLocatorKind.HEADING_BLOCK
                        ));
        assertThat(first.sourceUnits().stream().map(ParsedDocument.SourceUnit::id))
                .containsExactlyElementsOf(
                        second.sourceUnits().stream()
                                .map(ParsedDocument.SourceUnit::id)
                                .toList()
                );
        assertThat(first.packageMetadata().manifestJson())
                .contains("\"encoding\":\"UTF-8\"");
    }

    @Test
    void preservesMarkdownStructureAndUtf16Bom() throws Exception {
        String value = """
                # 检索策略

                - BM25 关键词检索
                - Semantic Search 向量检索

                | 阶段 | TopK |
                | --- | --- |
                | Rerank | 30 |

                ```text
                Parent and Child remain linked.
                ```
                """;
        byte[] text = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[text.length + 2];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xfe;
        System.arraycopy(text, 0, bytes, 2, text.length);
        Path path = write("retrieval.md", bytes);

        ParsedDocument parsed = parse(
                path,
                DocumentFormat.MARKDOWN,
                "text/markdown",
                ParserProviderKind.MARKDOWN
        );

        assertThat(parsed.markdown())
                .contains("# 检索策略", "| 阶段 | TopK |", "```text");
        assertThat(parsed.contentBlocks().stream()
                .map(ParsedDocument.ContentBlock::type))
                .contains(
                        ParsedDocument.BlockType.HEADING,
                        ParsedDocument.BlockType.LIST,
                        ParsedDocument.BlockType.TABLE
                );
        assertThat(parsed.packageMetadata().manifestJson())
                .contains("\"encoding\":\"UTF-16LE\"");
    }

    @Test
    void sanitizesHtmlWithoutFetchingOrReturningExecutableContent()
            throws Exception {
        String value = """
                <!doctype html>
                <html>
                  <head>
                    <title>隐藏标题</title>
                    <script>window.secret = 'never index this';</script>
                    <style>body { display:none }</style>
                  </head>
                  <body onload="steal()">
                    <h1>安全网页知识</h1>
                    <p>平台只提取本地文本，并保留可引用的 DOM 位置。</p>
                    <iframe src="https://attacker.example/frame"></iframe>
                    <p><a href="https://example.com/remote">远程链接不会被抓取</a>，正文仍然保留。</p>
                  </body>
                </html>
                """;
        Path path = write("safe.html", value.getBytes(StandardCharsets.UTF_8));

        ParsedDocument parsed = parse(
                path,
                DocumentFormat.HTML,
                "text/html",
                ParserProviderKind.HTML
        );

        assertThat(parsed.markdown())
                .contains("# 安全网页知识", "远程链接不会被抓取")
                .doesNotContain(
                        "window.secret",
                        "display:none",
                        "attacker.example",
                        "onload"
                );
        assertThat(parsed.contentBlocks())
                .allSatisfy(block -> assertThat(block.sourceSpan().locatorKind())
                        .isEqualTo(SourceLocatorKind.DOM_PATH));
        assertThat(parsed.packageMetadata().manifestJson())
                .contains("\"networkResourcesFetched\":0")
                .contains("external references ignored");
    }

    @Test
    void acceptsVerifiableGb18030AndRejectsInvalidEncoding() throws Exception {
        String value = "中文知识库采用严格编码验证。".repeat(8);
        Path valid = write(
                "legacy.txt",
                value.getBytes(Charset.forName("GB18030"))
        );
        ParsedDocument parsed = parse(
                valid,
                DocumentFormat.TXT,
                "text/plain",
                ParserProviderKind.TEXT
        );
        assertThat(parsed.packageMetadata().manifestJson())
                .contains("\"encoding\":\"GB18030\"");

        Path invalid = write(
                "invalid.txt",
                new byte[]{(byte) 0x81}
        );
        assertThatThrownBy(() -> parse(
                invalid,
                DocumentFormat.TXT,
                "text/plain",
                ParserProviderKind.TEXT
        )).isInstanceOfSatisfying(
                ParserProcessingException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("TEXT_ENCODING_INVALID")
        );
    }

    @Test
    void rejectsTextThatDecodesDifferentlyAsUtf8AndGb18030()
            throws Exception {
        Path ambiguous = write(
                "ambiguous.txt",
                "你好".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> parse(
                ambiguous,
                DocumentFormat.TXT,
                "text/plain",
                ParserProviderKind.TEXT
        )).isInstanceOfSatisfying(
                ParserProcessingException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("TEXT_ENCODING_AMBIGUOUS")
        );
    }

    private ParsedDocument parse(
            Path path,
            DocumentFormat format,
            String mediaType,
            ParserProviderKind provider
    ) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return parser.parse(
                new ParserInput(
                        path,
                        UUID.fromString(
                                "11111111-1111-1111-1111-111111111111"
                        ),
                        UUID.fromString(
                                "22222222-2222-2222-2222-222222222222"
                        ),
                        format,
                        mediaType,
                        bytes.length,
                        sha256(bytes)
                ),
                PROFILE,
                provider
        );
    }

    private Path write(String name, byte[] bytes) throws Exception {
        Path path = directory.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
        );
    }
}
