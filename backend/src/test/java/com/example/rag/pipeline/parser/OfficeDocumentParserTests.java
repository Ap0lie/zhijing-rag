package com.example.rag.pipeline.parser;

import com.example.rag.document.OoxmlPackageInspector;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProviderKind;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfficeDocumentParserTests {

    private static final UUID DOCUMENT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final UUID REVISION_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final ChunkingProfile PROFILE =
            ChunkingProfile.phase4Default();

    private final OfficeDocumentParser parser =
            new OfficeDocumentParser(new OoxmlPackageInspector());

    @TempDir
    Path directory;

    @Test
    void parsesDocxHeadingsTablesAndImagesWithStableLocators()
            throws Exception {
        Path path = directory.resolve("knowledge.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("企业检索架构");

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(
                    "Parent/Child Chunk、Hybrid Search 与 Rerank "
                            + "共同形成可追溯的检索链路。"
            );
            paragraph.createRun().addPicture(
                    new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG,
                    "architecture.png",
                    Units.toEMU(16),
                    Units.toEMU(16)
            );

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("阶段");
            table.getRow(0).getCell(1).setText("作用");
            table.getRow(1).getCell(0).setText("Rerank");
            table.getRow(1).getCell(1).setText("重排序");
            XWPFParagraph cellParagraph = table.getRow(1).getCell(1)
                    .addParagraph();
            cellParagraph.createRun().addPicture(
                    new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG,
                    "cell-architecture.png",
                    Units.toEMU(12),
                    Units.toEMU(12)
            );
            var cursor = table.getRow(1).getCell(1).getCTTc().newCursor();
            cursor.toEndToken();
            XWPFTable nested = table.getRow(1).getCell(1)
                    .insertNewTbl(cursor);
            cursor.close();
            nested.createRow().createCell().setText("嵌套证据");
            try (var output = Files.newOutputStream(path)) {
                document.write(output);
            }
        }

        ParsedDocument first = parse(
                path,
                DocumentFormat.DOCX,
                ParserProviderKind.DOCX_POI
        );
        ParsedDocument second = parse(
                path,
                DocumentFormat.DOCX,
                ParserProviderKind.DOCX_POI
        );

        assertThat(first.pageCount()).isZero();
        assertThat(first.markdown())
                .contains("# 企业检索架构", "| 阶段 | 作用 |");
        assertThat(first.tables()).hasSize(2);
        assertThat(first.tables().getFirst().cells()).hasSize(4);
        assertThat(first.images()).hasSize(1);
        assertThat(first.packageMetadata().parserName()).isEqualTo("DOCX_POI");
        assertThat(first.packageMetadata().parserRevision())
                .isEqualTo("apache-poi-5.5.1");
        assertThat(first.parserVersion()).isEqualTo("docx-poi-5.5.1-v3");
        assertThat(first.packageMetadata().manifestJson())
                .contains("\"parser\":\"DOCX_POI\"")
                .contains("\"parserRevision\":\"apache-poi-5.5.1\"")
                .contains("\"imageReferences\":2")
                .contains("\"commentsSkipped\":0")
                .contains("\"deletedRevisionsSkipped\":0");
        assertThat(first.packageMetadata().outputHash())
                .isEqualTo(ParsedPackageIntegrity.canonicalHash(first));
        assertThat(first.sourceUnits())
                .extracting(ParsedDocument.SourceUnit::kind)
                .contains(
                        SourceUnitKind.PARAGRAPH,
                        SourceUnitKind.TABLE_CELL
                );
        assertThat(first.contentBlocks())
                .allSatisfy(block -> assertThat(
                        block.sourceSpan().locatorKind()
                ).isIn(
                        SourceLocatorKind.PARAGRAPH,
                        SourceLocatorKind.TABLE_CELL
                ));
        assertThat(first.sourceUnits().stream().map(ParsedDocument.SourceUnit::id))
                .containsExactlyElementsOf(
                        second.sourceUnits().stream()
                                .map(ParsedDocument.SourceUnit::id)
                                .toList()
                );
    }

    @Test
    void parsesPptxShapesTablesImagesAndSkipsHiddenSlides()
            throws Exception {
        Path path = directory.resolve("retrieval.pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox title = slide.createTextBox();
            title.setAnchor(new Rectangle(40, 30, 620, 80));
            title.setText("混合检索与 GraphRAG");
            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle(40, 130, 620, 120));
            body.setText("BM25 和向量召回合并后只执行一次 Rerank。");
            var bullet = body.addNewTextParagraph();
            bullet.setBullet(true);
            bullet.addNewTextRun().setText("Graph 路径用于关系问题");

            XSLFTable table = slide.createTable(2, 2);
            table.setAnchor(new Rectangle(40, 280, 620, 120));
            table.getCell(0, 0).setText("模式");
            table.getCell(0, 1).setText("用途");
            table.getCell(1, 0).setText("LOCAL_GRAPH");
            table.getCell(1, 1).setText("关系问题");

            XSLFPictureData image = show.addPicture(
                    png(),
                    PictureData.PictureType.PNG
            );
            var picture = slide.createPicture(image);
            picture.setAnchor(new Rectangle(700, 40, 80, 80));

            XSLFSlide titleless = show.createSlide();
            XSLFTextBox titlelessBody = titleless.createTextBox();
            titlelessBody.setAnchor(new Rectangle(40, 130, 620, 120));
            titlelessBody.setText("第二张幻灯片没有标题，Parent 仍不得跨页。");
            var repeatedPicture = titleless.createPicture(image);
            repeatedPicture.setAnchor(new Rectangle(700, 40, 80, 80));

            XSLFSlide hidden = show.createSlide();
            hidden.getXmlObject().setShow(false);
            hidden.createTextBox().setText("隐藏内容不得进入索引");
            try (var output = Files.newOutputStream(path)) {
                show.write(output);
            }
        }

        ParsedDocument parsed = parse(
                path,
                DocumentFormat.PPTX,
                ParserProviderKind.PPTX_POI
        );

        assertThat(parsed.markdown())
                .contains("混合检索与 GraphRAG", "LOCAL_GRAPH")
                .doesNotContain("隐藏内容不得进入索引");
        assertThat(parsed.tables()).hasSize(1);
        assertThat(parsed.images()).hasSize(1);
        assertThat(parsed.sourceUnits())
                .extracting(ParsedDocument.SourceUnit::kind)
                .contains(
                        SourceUnitKind.SLIDE,
                        SourceUnitKind.SHAPE,
                        SourceUnitKind.TABLE_CELL
                );
        assertThat(parsed.contentBlocks())
                .allSatisfy(block -> assertThat(
                        block.sourceSpan().locatorKind()
                ).isIn(
                        SourceLocatorKind.SLIDE_SHAPE,
                        SourceLocatorKind.TABLE_CELL
                ));
        assertThat(parsed.contentBlocks())
                .extracting(ParsedDocument.ContentBlock::type)
                .contains(ParsedDocument.BlockType.LIST);
        assertThat(parsed.packageMetadata().manifestJson())
                .contains("\"parser\":\"PPTX_POI\"")
                .contains("\"parserRevision\":\"apache-poi-5.5.1\"")
                .contains("\"hiddenSlidesSkipped\":1")
                .contains("\"duplicateImageReferencesSkipped\":1")
                .contains("\"activeContentExecuted\":false");
        assertThat(parsed.parserVersion()).isEqualTo("pptx-poi-5.5.1-v3");
        assertThat(parsed.contentBlocks().stream()
                .filter(block -> block.sourceSpan().address()
                        .startsWith("pptx:slide:2:")))
                .allSatisfy(block -> assertThat(block.headingPath()).isEmpty());
        assertThat(parsed.chunks().stream()
                .filter(chunk -> chunk.type() == ParsedDocument.ChunkType.PARENT))
                .allSatisfy(parent -> assertThat(parent.sourceSpans().stream()
                        .map(span -> span.address().split(":", 4)[2])
                        .distinct()
                        .count()).isEqualTo(1));
        assertThat(parsed.packageMetadata().outputHash())
                .isEqualTo(ParsedPackageIntegrity.canonicalHash(parsed));
    }

    private ParsedDocument parse(
            Path path,
            DocumentFormat format,
            ParserProviderKind provider
    ) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String mediaType = format == DocumentFormat.DOCX
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        return parser.parse(
                new ParserInput(
                        path,
                        DOCUMENT_ID,
                        REVISION_ID,
                        format,
                        mediaType,
                        bytes.length,
                        HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(bytes)
                        )
                ),
                PROFILE,
                provider
        );
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(
                2,
                2,
                BufferedImage.TYPE_INT_RGB
        );
        image.setRGB(0, 0, 0x3157a4);
        image.setRGB(1, 0, 0x4f7fd1);
        image.setRGB(0, 1, 0x84a9e8);
        image.setRGB(1, 1, 0xffffff);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
