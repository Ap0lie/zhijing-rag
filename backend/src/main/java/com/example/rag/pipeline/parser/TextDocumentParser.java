package com.example.rag.pipeline.parser;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProcessingException;
import com.example.rag.pipeline.ParserProviderKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.GIBBERISH_TEXT;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.LOW_QUALITY_TEXT;

/**
 * Deterministic, network-free parser for bounded text, Markdown and HTML
 * inputs. It emits the same parsed-package-v3 contract as the PDF providers.
 */
@Component
public final class TextDocumentParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final int MAX_CHARACTERS = 20_000_000;
    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.+");
    private static final Pattern TABLE_DIVIDER =
            Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:[、.)])?\\s+.+");
    private static final Pattern CHINESE_HEADING =
            Pattern.compile("^第[一二三四五六七八九十百千万零〇0-9]+([章节篇部])(?:\\s*.*)$");

    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile profile,
            ParserProviderKind provider
    ) throws IOException, ParseQuarantineException, ParserProcessingException {
        Instant startedAt = Instant.now();
        requireProvider(input.documentFormat(), provider);
        DecodedText decoded = decode(input);
        String normalized = normalize(decoded.text());
        validateText(normalized, profile);

        ParseResult result = switch (input.documentFormat()) {
            case TXT -> new ParseResult(
                    textBlocks(normalized),
                    "line-and-section-v1",
                    "line endings normalized; text preserved"
            );
            case MARKDOWN -> new ParseResult(
                    markdownBlocks(normalized),
                    "markdown-block-v1",
                    "raw HTML remains inert text; no rendering or resource loading"
            );
            case HTML -> htmlBlocks(normalized);
            default -> throw new IllegalArgumentException(
                    "Unsupported text format " + input.documentFormat()
            );
        };
        if (result.blocks().isEmpty()) {
            throw new ParserProcessingException(
                    "DOCUMENT_TEXT_EMPTY",
                    "The document contains no indexable text"
            );
        }
        return assemble(
                input,
                profile,
                provider,
                decoded.encoding(),
                result,
                startedAt
        );
    }

    private static ParsedDocument assemble(
            ParserInput input,
            ChunkingProfile profile,
            ParserProviderKind provider,
            String encoding,
            ParseResult result,
            Instant startedAt
    ) throws ParserProcessingException {
        List<ParsedDocument.SourceUnit> sourceUnits = new ArrayList<>();
        List<ParsedDocument.ContentBlock> contentBlocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String normalizationVersion =
                input.documentFormat().name().toLowerCase(Locale.ROOT)
                        + "-nfc-line-endings-v1";

        for (int index = 0; index < result.blocks().size(); index++) {
            BlockSpec block = result.blocks().get(index);
            String text = block.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            String hash = FormatNeutralChunker.sha256(text);
            UUID sourceUnitId = FormatNeutralChunker.stableUuid(
                    "source-unit",
                    input.revisionId(),
                    block.unitKind(),
                    block.address(),
                    hash
            );
            int unitOrder = sourceUnits.size() + 1;
            sourceUnits.add(new ParsedDocument.SourceUnit(
                    sourceUnitId,
                    unitOrder,
                    block.unitKind(),
                    block.address(),
                    text,
                    hash,
                    normalizationVersion,
                    labelMetadata(block, encoding)
            ));

            if (block.type() == ParsedDocument.BlockType.HEADING) {
                int depth = Math.max(1, Math.min(6, block.headingLevel()));
                while (headingPath.size() >= depth) {
                    headingPath.removeLast();
                }
                headingPath.add(text);
            }
            ParsedDocument.SourceSpan span = new ParsedDocument.SourceSpan(
                    sourceUnitId,
                    sourceUnitId,
                    unitOrder,
                    unitOrder,
                    block.locatorKind(),
                    block.address() + "#chars=0-" + text.length(),
                    0,
                    text.length(),
                    0,
                    text.length(),
                    hash,
                    normalizationVersion,
                    List.of()
            );
            int blockOrder = contentBlocks.size();
            contentBlocks.add(new ParsedDocument.ContentBlock(
                    FormatNeutralChunker.stableUuid(
                            "block",
                            input.revisionId(),
                            blockOrder,
                            sourceUnitId,
                            hash
                    ),
                    blockOrder,
                    block.type(),
                    text,
                    List.copyOf(headingPath),
                    text.length(),
                    FormatNeutralChunker.countTokens(text),
                    profile.tokenCounterVersion(),
                    span
            ));
        }

        if (contentBlocks.isEmpty()) {
            throw new ParserProcessingException(
                    "DOCUMENT_TEXT_EMPTY",
                    "The document contains no indexable text"
            );
        }
        String markdown = result.blocks().stream()
                .map(BlockSpec::markdown)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow() + "\n";
        String parserVersion = parserVersion(provider);
        String parserName = provider.name().toLowerCase(Locale.ROOT);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", result.schemaVersion());
        manifest.put("parser", parserName);
        manifest.put("parserVersion", parserVersion);
        manifest.put("inputHash", input.inputHash());
        manifest.put("outputHash", FormatNeutralChunker.sha256(markdown));
        manifest.put("encoding", encoding);
        manifest.put("sanitization", result.sanitization());
        manifest.put("networkResourcesFetched", 0);

        ParsedStructure.PackageMetadata metadata =
                new ParsedStructure.PackageMetadata(
                        parserName,
                        parserVersion,
                        null,
                        input.inputHash(),
                        FormatNeutralChunker.sha256(markdown),
                        result.schemaVersion(),
                        json(manifest)
                );
        List<ParsedDocument.Chunk> chunks =
                FormatNeutralChunker.createChunks(
                        input.revisionId(),
                        contentBlocks,
                        profile
                );
        int characterCount = contentBlocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::characterCount)
                .sum();
        int tokenCount = contentBlocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::tokenCount)
                .sum();
        return ParsedPackageIntegrity.seal(new ParsedDocument(
                input.documentId(),
                input.revisionId(),
                input.documentFormat(),
                provider,
                sourceUnits,
                markdown,
                contentBlocks,
                chunks,
                metadata,
                List.of(),
                List.of(),
                characterCount,
                tokenCount,
                profile.version(),
                parserVersion,
                profile.chunkerVersion(),
                profile.tokenCounterVersion(),
                Duration.between(startedAt, Instant.now()).toMillis()
        ));
    }

    private static DecodedText decode(ParserInput input)
            throws IOException, ParserProcessingException {
        byte[] header = new byte[4];
        int headerLength;
        try (InputStream stream = Files.newInputStream(input.path())) {
            headerLength = stream.read(header);
        }
        if (headerLength >= 3
                && (header[0] & 0xff) == 0xef
                && (header[1] & 0xff) == 0xbb
                && (header[2] & 0xff) == 0xbf) {
            return new DecodedText(
                    decode(input, StandardCharsets.UTF_8, 3),
                    "UTF-8"
            );
        }
        if (headerLength >= 2
                && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xfe) {
            return new DecodedText(
                    decode(input, StandardCharsets.UTF_16LE, 2),
                    "UTF-16LE"
            );
        }
        if (headerLength >= 2
                && (header[0] & 0xff) == 0xfe
                && (header[1] & 0xff) == 0xff) {
            return new DecodedText(
                    decode(input, StandardCharsets.UTF_16BE, 2),
                    "UTF-16BE"
            );
        }
        try {
            String utf8 = decode(input, StandardCharsets.UTF_8, 0);
            try {
                String gb18030 = decode(input, GB18030, 0);
                if (!gb18030.equals(utf8)
                        && sha256(gb18030.getBytes(GB18030))
                        .equals(input.inputHash())) {
                    throw new ParserProcessingException(
                            "TEXT_ENCODING_AMBIGUOUS",
                            "Text is valid as both UTF-8 and GB18030 with different content; add an encoding BOM"
                    );
                }
            } catch (CharacterCodingException invalidGb18030) {
                // UTF-8 is authoritative when GB18030 cannot decode strictly.
            }
            return new DecodedText(utf8, "UTF-8");
        } catch (CharacterCodingException invalidUtf8) {
            try {
                String text = decode(input, GB18030, 0);
                if (!sha256(text.getBytes(GB18030)).equals(input.inputHash())) {
                    throw invalidEncoding(invalidUtf8);
                }
                return new DecodedText(text, "GB18030");
            } catch (CharacterCodingException invalidGb18030) {
                invalidGb18030.addSuppressed(invalidUtf8);
                throw invalidEncoding(invalidGb18030);
            }
        }
    }

    private static String decode(
            ParserInput input,
            Charset charset,
            int skipBytes
    ) throws IOException, ParserProcessingException {
        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder result = new StringBuilder();
        try (InputStream stream = Files.newInputStream(input.path())) {
            stream.skipNBytes(skipBytes);
            try (Reader reader = new BufferedReader(
                    new InputStreamReader(stream, decoder)
            )) {
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    result.append(buffer, 0, count);
                    if (result.length() > MAX_CHARACTERS) {
                        throw new ParserProcessingException(
                                "DOCUMENT_TEXT_TOO_LARGE",
                                "Decoded text exceeds the parser character limit"
                        );
                    }
                }
            }
        }
        return result.toString();
    }

    private static String normalize(String value) {
        String lineEndings = value
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        return Normalizer.normalize(lineEndings, Normalizer.Form.NFC);
    }

    private static void validateText(
            String value,
            ChunkingProfile profile
    ) throws ParseQuarantineException, ParserProcessingException {
        int meaningful = 0;
        int controls = 0;
        int nonWhitespace = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == 0) {
                throw new ParserProcessingException(
                        "DOCUMENT_BINARY_CONTENT",
                        "NUL bytes are not allowed in text documents"
                );
            }
            if (!Character.isWhitespace(codePoint)) {
                nonWhitespace++;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                meaningful++;
            }
            if (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\t') {
                controls++;
            }
        }
        if (nonWhitespace == 0) {
            throw new ParserProcessingException(
                    "DOCUMENT_TEXT_EMPTY",
                    "The document contains no text"
            );
        }
        if (controls > Math.max(2, nonWhitespace / 100)) {
            throw new ParseQuarantineException(
                    GIBBERISH_TEXT,
                    "The document contains too many binary control characters"
            );
        }
        if (meaningful < profile.minMeaningfulCharacters()) {
            throw new ParseQuarantineException(
                    LOW_QUALITY_TEXT,
                    "The document does not contain enough meaningful text"
            );
        }
    }

    private static List<BlockSpec> textBlocks(String value) {
        String[] lines = value.split("\\n", -1);
        List<BlockSpec> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            if (lines[index].isBlank()) {
                index++;
                continue;
            }
            int start = index;
            int depth = headingDepth(lines[index].strip());
            if (depth > 0) {
                String text = lines[index].strip();
                blocks.add(lineBlock(
                        ParsedDocument.BlockType.HEADING,
                        text,
                        "#".repeat(depth) + " " + text,
                        depth,
                        start + 1,
                        start + 1
                ));
                index++;
                continue;
            }
            List<String> paragraph = new ArrayList<>();
            while (index < lines.length
                    && !lines[index].isBlank()
                    && headingDepth(lines[index].strip()) == 0) {
                paragraph.add(lines[index].stripTrailing());
                index++;
            }
            int end = index;
            String text = String.join("\n", paragraph).strip();
            ParsedDocument.BlockType type = paragraph.stream()
                    .filter(line -> !line.isBlank())
                    .allMatch(line -> LIST_ITEM.matcher(line).matches())
                    ? ParsedDocument.BlockType.LIST
                    : ParsedDocument.BlockType.PARAGRAPH;
            blocks.add(lineBlock(
                    type,
                    text,
                    text,
                    0,
                    start + 1,
                    end
            ));
        }
        return List.copyOf(blocks);
    }

    private static List<BlockSpec> markdownBlocks(String value) {
        String[] lines = value.split("\\n", -1);
        List<BlockSpec> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            if (lines[index].isBlank()) {
                index++;
                continue;
            }
            int start = index;
            Matcher heading = MARKDOWN_HEADING.matcher(lines[index]);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String text = heading.group(2).strip();
                blocks.add(new BlockSpec(
                        ParsedDocument.BlockType.HEADING,
                        text,
                        "#".repeat(level) + " " + text,
                        level,
                        start + 1,
                        start + 1,
                        SourceUnitKind.SECTION,
                        SourceLocatorKind.HEADING_BLOCK,
                        "heading:" + (blocks.size() + 1),
                        "标题「" + abbreviate(text, 80) + "」· 第 "
                                + (start + 1) + " 行"
                ));
                index++;
                continue;
            }
            String stripped = lines[index].stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                String fence = stripped.substring(0, 3);
                index++;
                while (index < lines.length
                        && !lines[index].stripLeading().startsWith(fence)) {
                    index++;
                }
                if (index < lines.length) {
                    index++;
                }
                blocks.add(lineBlock(
                        ParsedDocument.BlockType.PARAGRAPH,
                        join(lines, start, index),
                        join(lines, start, index),
                        0,
                        start + 1,
                        index
                ));
                continue;
            }
            if (index + 1 < lines.length
                    && lines[index].contains("|")
                    && TABLE_DIVIDER.matcher(lines[index + 1]).matches()) {
                index += 2;
                while (index < lines.length
                        && !lines[index].isBlank()
                        && lines[index].contains("|")) {
                    index++;
                }
                String table = join(lines, start, index);
                blocks.add(lineBlock(
                        ParsedDocument.BlockType.TABLE,
                        table,
                        table,
                        0,
                        start + 1,
                        index
                ));
                continue;
            }
            if (LIST_ITEM.matcher(lines[index]).matches()) {
                index++;
                while (index < lines.length
                        && !lines[index].isBlank()
                        && (LIST_ITEM.matcher(lines[index]).matches()
                        || Character.isWhitespace(lines[index].charAt(0)))) {
                    index++;
                }
                String list = join(lines, start, index);
                blocks.add(lineBlock(
                        ParsedDocument.BlockType.LIST,
                        list,
                        list,
                        0,
                        start + 1,
                        index
                ));
                continue;
            }
            index++;
            while (index < lines.length
                    && !lines[index].isBlank()
                    && !MARKDOWN_HEADING.matcher(lines[index]).matches()
                    && !LIST_ITEM.matcher(lines[index]).matches()
                    && !(index + 1 < lines.length
                    && lines[index].contains("|")
                    && TABLE_DIVIDER.matcher(lines[index + 1]).matches())) {
                index++;
            }
            String paragraph = join(lines, start, index);
            blocks.add(lineBlock(
                    ParsedDocument.BlockType.PARAGRAPH,
                    paragraph,
                    paragraph,
                    0,
                    start + 1,
                    index
            ));
        }
        return List.copyOf(blocks);
    }

    private static ParseResult htmlBlocks(String value)
            throws IOException, ParserProcessingException {
        SafeHtmlCallback callback = new SafeHtmlCallback();
        try (StringReader reader = new StringReader(value)) {
            new ParserDelegator().parse(reader, callback, true);
        }
        List<BlockSpec> blocks = callback.blocks();
        if (blocks.isEmpty()) {
            throw new ParserProcessingException(
                    "HTML_TEXT_EMPTY",
                    "The HTML document contains no safe indexable text"
            );
        }
        return new ParseResult(
                blocks,
                "safe-html-block-v1",
                "script/style/form/iframe/head content and all attributes removed; "
                        + callback.externalReferenceCount()
                        + " external references ignored"
        );
    }

    private static BlockSpec lineBlock(
            ParsedDocument.BlockType type,
            String text,
            String markdown,
            int headingLevel,
            int startLine,
            int endLine
    ) {
        String range = startLine == endLine
                ? Integer.toString(startLine)
                : startLine + "-" + endLine;
        return new BlockSpec(
                type,
                text,
                markdown,
                headingLevel,
                startLine,
                endLine,
                SourceUnitKind.LINE,
                SourceLocatorKind.LINE_RANGE,
                "lines:" + range,
                startLine == endLine
                        ? "第 " + startLine + " 行"
                        : "第 " + startLine + "–" + endLine + " 行"
        );
    }

    private static int headingDepth(String text) {
        Matcher numbered = NUMBERED_HEADING.matcher(text);
        if (numbered.matches()) {
            return Math.min(
                    6,
                    1 + (int) numbered.group(1)
                            .chars()
                            .filter(value -> value == '.')
                            .count()
            );
        }
        Matcher chinese = CHINESE_HEADING.matcher(text);
        if (chinese.matches()) {
            return "章篇部".contains(chinese.group(1)) ? 1 : 2;
        }
        long letters = text.codePoints().filter(Character::isLetter).count();
        boolean lowercase = text.codePoints().anyMatch(Character::isLowerCase);
        return text.length() <= 80 && letters >= 4 && !lowercase ? 1 : 0;
    }

    private static void requireProvider(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        ParserProviderKind expected = switch (format) {
            case TXT -> ParserProviderKind.TEXT;
            case MARKDOWN -> ParserProviderKind.MARKDOWN;
            case HTML -> ParserProviderKind.HTML;
            default -> throw new IllegalArgumentException(
                    "Unsupported text format " + format
            );
        };
        if (provider != expected) {
            throw new IllegalArgumentException(
                    provider + " cannot parse " + format
            );
        }
    }

    private static String parserVersion(ParserProviderKind provider) {
        return switch (provider) {
            case TEXT -> "text-lines-v2";
            case MARKDOWN -> "markdown-structure-v2";
            case HTML -> "html-safe-structure-v2";
            default -> throw new IllegalArgumentException(
                    "Not a text parser provider: " + provider
            );
        };
    }

    private static String labelMetadata(BlockSpec block, String encoding) {
        ObjectNode value = JSON.createObjectNode();
        value.put("sourceLabel", block.label());
        value.put("encoding", encoding);
        if (block.startLine() > 0) {
            value.put("startLine", block.startLine());
            value.put("endLine", block.endLine());
        }
        if (block.locatorKind() == SourceLocatorKind.DOM_PATH) {
            value.put("domPath", block.address());
        }
        return json(value);
    }

    private static String join(String[] lines, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!value.isEmpty()) {
                value.append('\n');
            }
            value.append(lines[index].stripTrailing());
        }
        return value.toString().strip();
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum
                ? value
                : value.substring(0, maximum - 1) + "…";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ParserProcessingException invalidEncoding(Throwable cause) {
        return new ParserProcessingException(
                "TEXT_ENCODING_INVALID",
                "Text encoding is not valid UTF-8, BOM UTF-16, or verifiable GB18030",
                cause
        );
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize parser metadata",
                    exception
            );
        }
    }

    private record DecodedText(String text, String encoding) {
    }

    private record ParseResult(
            List<BlockSpec> blocks,
            String schemaVersion,
            String sanitization
    ) {
        private ParseResult {
            blocks = List.copyOf(blocks);
        }
    }

    private record BlockSpec(
            ParsedDocument.BlockType type,
            String text,
            String markdown,
            int headingLevel,
            int startLine,
            int endLine,
            SourceUnitKind unitKind,
            SourceLocatorKind locatorKind,
            String address,
            String label
    ) {
    }

    private static final class SafeHtmlCallback
            extends HTMLEditorKit.ParserCallback {

        private static final Set<HTML.Tag> IGNORED = Set.of(
                HTML.Tag.SCRIPT,
                HTML.Tag.STYLE,
                HTML.Tag.FORM,
                HTML.Tag.HEAD
        );
        private static final Set<HTML.Tag> BLOCKS = Set.of(
                HTML.Tag.H1,
                HTML.Tag.H2,
                HTML.Tag.H3,
                HTML.Tag.H4,
                HTML.Tag.H5,
                HTML.Tag.H6,
                HTML.Tag.P,
                HTML.Tag.LI,
                HTML.Tag.PRE,
                HTML.Tag.TD,
                HTML.Tag.TH,
                HTML.Tag.BLOCKQUOTE
        );

        private final Deque<ElementFrame> elements = new ArrayDeque<>();
        private final Deque<Capture> captures = new ArrayDeque<>();
        private final List<BlockSpec> blocks = new ArrayList<>();
        private int ignoredDepth;
        private int externalReferenceCount;

        @Override
        public void handleStartTag(
                HTML.Tag tag,
                MutableAttributeSet attributes,
                int position
        ) {
            String path = push(tag);
            if (IGNORED.contains(tag) || isUnsupportedFrame(tag)) {
                ignoredDepth++;
            }
            countExternalReferences(attributes);
            if (ignoredDepth == 0 && BLOCKS.contains(tag)
                    && captures.isEmpty()) {
                captures.push(new Capture(tag, path, new StringBuilder()));
            }
        }

        @Override
        public void handleSimpleTag(
                HTML.Tag tag,
                MutableAttributeSet attributes,
                int position
        ) {
            countExternalReferences(attributes);
            if (ignoredDepth == 0 && tag == HTML.Tag.BR
                    && !captures.isEmpty()) {
                captures.peek().text().append('\n');
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (ignoredDepth == 0 && !captures.isEmpty()) {
                String value = new String(data)
                        .replaceAll("\\s+", " ")
                        .strip();
                if (!value.isEmpty()) {
                    StringBuilder text = captures.peek().text();
                    if (!text.isEmpty()
                            && text.charAt(text.length() - 1) != '\n') {
                        text.append(' ');
                    }
                    text.append(value);
                }
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (!captures.isEmpty() && captures.peek().tag() == tag) {
                addCapture(captures.pop());
            }
            if (IGNORED.contains(tag) || isUnsupportedFrame(tag)) {
                ignoredDepth = Math.max(0, ignoredDepth - 1);
            }
            if (!elements.isEmpty()) {
                elements.pop();
            }
        }

        List<BlockSpec> blocks() {
            while (!captures.isEmpty()) {
                addCapture(captures.removeLast());
            }
            return List.copyOf(blocks);
        }

        int externalReferenceCount() {
            return externalReferenceCount;
        }

        private String push(HTML.Tag tag) {
            int sibling = 1;
            if (!elements.isEmpty()) {
                sibling = elements.peek().next(tag.toString());
            }
            String parent = elements.isEmpty()
                    ? ""
                    : elements.peek().path();
            String path = parent + "/" + tag + "[" + sibling + "]";
            elements.push(new ElementFrame(path, new HashMap<>()));
            return path;
        }

        private void addCapture(Capture capture) {
            String text = capture.text().toString().strip();
            if (text.isEmpty()) {
                return;
            }
            int heading = headingLevel(capture.tag());
            ParsedDocument.BlockType type = heading > 0
                    ? ParsedDocument.BlockType.HEADING
                    : capture.tag() == HTML.Tag.LI
                    ? ParsedDocument.BlockType.LIST
                    : capture.tag() == HTML.Tag.TD
                    || capture.tag() == HTML.Tag.TH
                    ? ParsedDocument.BlockType.TABLE
                    : ParsedDocument.BlockType.PARAGRAPH;
            String markdown = heading > 0
                    ? "#".repeat(heading) + " " + text
                    : type == ParsedDocument.BlockType.LIST
                    ? "- " + text
                    : text;
            blocks.add(new BlockSpec(
                    type,
                    text,
                    markdown,
                    heading,
                    0,
                    0,
                    SourceUnitKind.DOM_BLOCK,
                    SourceLocatorKind.DOM_PATH,
                    boundedPath(capture.path()),
                    heading > 0
                            ? "标题「" + abbreviate(text, 80) + "」"
                            : "DOM " + boundedPath(capture.path())
            ));
        }

        private void countExternalReferences(MutableAttributeSet attributes) {
            for (HTML.Attribute attribute : List.of(
                    HTML.Attribute.HREF,
                    HTML.Attribute.SRC
            )) {
                Object value = attributes.getAttribute(attribute);
                if (value != null) {
                    String address = value.toString()
                            .strip()
                            .toLowerCase(Locale.ROOT);
                    if (address.startsWith("http:")
                            || address.startsWith("https:")
                            || address.startsWith("//")) {
                        externalReferenceCount++;
                    }
                }
            }
        }

        private static boolean isUnsupportedFrame(HTML.Tag tag) {
            String name = tag.toString();
            return "iframe".equals(name)
                    || "frame".equals(name)
                    || "frameset".equals(name);
        }

        private static int headingLevel(HTML.Tag tag) {
            if (tag == HTML.Tag.H1) return 1;
            if (tag == HTML.Tag.H2) return 2;
            if (tag == HTML.Tag.H3) return 3;
            if (tag == HTML.Tag.H4) return 4;
            if (tag == HTML.Tag.H5) return 5;
            if (tag == HTML.Tag.H6) return 6;
            return 0;
        }

        private static String boundedPath(String value) {
            if (value.length() <= 450) {
                return value;
            }
            return value.substring(0, 380)
                    + "/…/"
                    + FormatNeutralChunker.sha256(value).substring(0, 32);
        }

        private record Capture(
                HTML.Tag tag,
                String path,
                StringBuilder text
        ) {
        }

        private record ElementFrame(
                String path,
                Map<String, Integer> children
        ) {
            int next(String tag) {
                int next = children.getOrDefault(tag, 0) + 1;
                children.put(tag, next);
                return next;
            }
        }
    }
}
