package com.example.rag.pipeline.parser;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserProviderKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.ENCRYPTED_PDF;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.GIBBERISH_TEXT;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.LOW_QUALITY_TEXT;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.PAGE_LIMIT_EXCEEDED;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.SCANNED_PDF;
import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.CHILD;
import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.PARENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTests {

    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final ChunkingProfile PROFILE = new ChunkingProfile(
            "test-v1", 220, 42, 7, 20, 0.01, 0.20,
            "pdfbox-test-v1", "parent-child-test-v1", "unicode-codepoint-v1"
    );

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDeterministicTraceableParentAndChildChunks() throws Exception {
        byte[] pdf = textPdf(List.of(
                List.of(
                        "1 Overview",
                        "This platform turns enterprise documents into reliable evidence for answers.",
                        "Every extracted passage keeps its source location and revision identity."
                ),
                List.of(
                        "Small child chunks improve retrieval while bounded parents preserve context.",
                        "The same input and profile always produce the same chunk identifiers."
                )
        ));

        ParsedDocument first = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, PROFILE);
        ParsedDocument second = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, PROFILE);

        assertThat(first.markdown()).contains("# 1 Overview", "bounded parents preserve context");
        assertThat(first.documentFormat()).isEqualTo(DocumentFormat.PDF);
        assertThat(first.parserProvider()).isEqualTo(ParserProviderKind.PDFBOX);
        assertThat(first.pageCount()).isEqualTo(2);
        assertThat(first.sourceUnits()).hasSize(2).isEqualTo(second.sourceUnits())
                .allSatisfy(unit -> {
                    assertThat(unit.kind()).isEqualTo(SourceUnitKind.PAGE);
                    assertThat(unit.stableAddress()).startsWith("page:");
                    assertThat(unit.sourceTextHash()).hasSize(64);
                    assertThat(unit.normalizationVersion())
                            .isEqualTo("pdf-nfkc-whitespace-v1");
                });
        assertThat(first.contentBlocks()).isNotEmpty().isEqualTo(second.contentBlocks());
        assertThat(first.chunks()).isNotEmpty().isEqualTo(second.chunks());
        assertThat(first.tokenCounterVersion()).isEqualTo("unicode-codepoint-v1");

        List<ParsedDocument.Chunk> parents = first.chunks().stream()
                .filter(chunk -> chunk.type() == PARENT)
                .toList();
        List<ParsedDocument.Chunk> children = first.chunks().stream()
                .filter(chunk -> chunk.type() == CHILD)
                .toList();
        Map<UUID, ParsedDocument.Chunk> parentsById = parents.stream()
                .collect(Collectors.toMap(ParsedDocument.Chunk::id, Function.identity()));

        assertThat(parents).allSatisfy(parent -> {
            assertThat(parent.parentId()).isNull();
            assertThat(parent.tokenCount()).isLessThanOrEqualTo(PROFILE.parentMaxTokens());
        });
        assertThat(parents.stream().flatMap(parent -> parent.sourceSpans().stream())
                .map(ParsedDocument.SourceSpan::startPage)
                .collect(Collectors.toSet())).containsExactlyInAnyOrder(1, 2);
        assertThat(children).hasSizeGreaterThan(1).allSatisfy(child -> {
            assertThat(child.parentId()).isNotNull();
            assertThat(child.tokenCount()).isLessThanOrEqualTo(PROFILE.childMaxTokens());
            ParsedDocument.Chunk parent = parentsById.get(child.parentId());
            assertThat(parent).isNotNull();
            assertThat(child.startBlockOrder()).isGreaterThanOrEqualTo(parent.startBlockOrder());
            assertThat(child.endBlockOrder()).isLessThanOrEqualTo(parent.endBlockOrder());
            assertThat(child.sourceSpans()).allSatisfy(childSpan -> assertThat(parent.sourceSpans())
                    .anySatisfy(parentSpan -> assertThat(covers(parentSpan, childSpan)).isTrue()));
        });
        assertThat(first.contentBlocks()).allSatisfy(block -> {
            assertThat(block.sourceSpan().startPage()).isBetween(1, 2);
            assertThat(first.sourceUnits())
                    .extracting(ParsedDocument.SourceUnit::id)
                    .contains(block.sourceSpan().startSourceUnitId());
            assertThat(block.sourceSpan().endOffset()).isGreaterThan(block.sourceSpan().startOffset());
            assertThat(block.sourceSpan().sourceTextHash()).hasSize(64);
        });
    }

    @Test
    void parsesTheBoundedPathWithoutChangingTheDeterministicPackage() throws Exception {
        byte[] pdf = textPdf(List.of(List.of(
                "Path based parsing avoids duplicating the whole original document in worker memory."
        )));
        Path path = temporaryDirectory.resolve("input.pdf");
        Files.write(path, pdf);

        ParsedDocument fromBytes = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, PROFILE);
        ParsedDocument fromPath = parser.parse(path, DOCUMENT_ID, REVISION_ID, PROFILE);

        assertThat(fromPath.packageMetadata().inputHash())
                .isEqualTo(fromBytes.packageMetadata().inputHash());
        assertThat(fromPath.packageMetadata().outputHash())
                .isEqualTo(fromBytes.packageMetadata().outputHash());
        assertThat(fromPath.sourceUnits()).isEqualTo(fromBytes.sourceUnits());
        assertThat(fromPath.chunks()).isEqualTo(fromBytes.chunks());
    }

    @Test
    void keepsCrossPageParentAndChildSourceSpans() throws Exception {
        ChunkingProfile crossPageProfile = new ChunkingProfile(
                "cross-page-v1", 300, 300, 0, 20, 0.01, 0.20,
                "pdfbox-test-v1", "parent-child-test-v1", "unicode-codepoint-v1"
        );
        byte[] pdf = textPdf(List.of(
                List.of("First page evidence remains traceable when one chunk crosses a page boundary."),
                List.of("Second page evidence keeps its own page-local offsets in the same chunk.")
        ));

        ParsedDocument result = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, crossPageProfile);
        List<ParsedDocument.Chunk> parents = result.chunks().stream()
                .filter(chunk -> chunk.type() == PARENT)
                .toList();
        List<ParsedDocument.Chunk> children = result.chunks().stream()
                .filter(chunk -> chunk.type() == CHILD)
                .toList();

        assertThat(parents).hasSize(1);
        assertThat(children).hasSize(1);
        ParsedDocument.Chunk parent = parents.getFirst();
        ParsedDocument.Chunk child = children.getFirst();

        assertThat(parent.sourceSpans())
                .extracting(ParsedDocument.SourceSpan::startPage)
                .containsExactly(1, 2);
        assertThat(child.sourceSpans())
                .extracting(ParsedDocument.SourceSpan::startPage)
                .containsExactly(1, 2);
        assertThat(child.parentId()).isEqualTo(parent.id());
        assertThat(parent.startBlockOrder()).isZero();
        assertThat(parent.endBlockOrder()).isEqualTo(1);
        assertThat(child.startBlockOrder()).isZero();
        assertThat(child.endBlockOrder()).isEqualTo(1);
    }

    @Test
    void splitsOneLongSectionDeterministicallyWithinConfiguredLimits() throws Exception {
        ChunkingProfile boundedProfile = new ChunkingProfile(
                "bounded-v1", 70, 28, 5, 20, 0.01, 0.20,
                "pdfbox-test-v1", "parent-child-test-v1", "unicode-codepoint-v1"
        );
        List<String> lines = new ArrayList<>();
        lines.add("1 Long Section");
        for (int index = 0; index < 10; index++) {
            lines.add("Paragraph " + index + " keeps deterministic evidence inside one bounded section.");
        }
        byte[] pdf = textPdf(List.of(lines));

        ParsedDocument first = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, boundedProfile);
        ParsedDocument second = parser.parse(pdf, DOCUMENT_ID, REVISION_ID, boundedProfile);
        List<ParsedDocument.Chunk> parents = first.chunks().stream()
                .filter(chunk -> chunk.type() == PARENT)
                .toList();

        assertThat(first.chunks()).isEqualTo(second.chunks());
        assertThat(parents).hasSizeGreaterThan(3)
                .allSatisfy(parent -> assertThat(parent.tokenCount())
                        .isLessThanOrEqualTo(boundedProfile.parentMaxTokens()));
        assertThat(first.chunks().stream().filter(chunk -> chunk.type() == CHILD).toList())
                .allSatisfy(child -> {
                    assertThat(child.tokenCount()).isLessThanOrEqualTo(boundedProfile.childMaxTokens());
                    assertThat(parents).extracting(ParsedDocument.Chunk::id).contains(child.parentId());
                });
    }

    @Test
    void quarantinesPdfAbovePageLimitBeforeTextExtraction() throws Exception {
        assertThatThrownBy(() -> parser.parse(pageCountPdf(1_001), DOCUMENT_ID, REVISION_ID, PROFILE))
                .isInstanceOfSatisfying(ParseQuarantineException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(PAGE_LIMIT_EXCEEDED);
                    assertThat(exception.getMessage()).contains("1000-page parsing limit");
                });
    }

    @Test
    void quarantinesEncryptedPdf() throws Exception {
        byte[] pdf = encryptedPdf(textPdf(List.of(List.of(
                "Protected document text that would otherwise be long enough for parsing."
        ))));

        assertQuarantined(pdf, ENCRYPTED_PDF);
    }

    @Test
    void quarantinesImageOnlyPdfAsScanned() throws Exception {
        assertQuarantined(imageOnlyPdf(), SCANNED_PDF);
    }

    @Test
    void quarantinesUnreadableAndEmptyTextExplicitly() throws Exception {
        String punctuation = "!@#$%^&*()_+-=[]{};:',.<>/?".repeat(4);
        assertQuarantined(textPdf(List.of(List.of(punctuation))), GIBBERISH_TEXT);
        assertQuarantined(blankPdf(), LOW_QUALITY_TEXT);
    }

    @Test
    void rejectsInvalidChunkingLimits() {
        assertThatThrownBy(() -> new ChunkingProfile(
                "bad", 20, 21, 0, 10, 0.1, 0.2, "parser", "chunker", "counter"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultProfileMatchesThePersistedPhase4Profile() {
        ChunkingProfile profile = ChunkingProfile.phase4Default();

        assertThat(profile.version()).isEqualTo("phase4-v1");
        assertThat(profile.parentMaxTokens()).isEqualTo(1_200);
        assertThat(profile.childMaxTokens()).isEqualTo(300);
        assertThat(profile.childOverlapTokens()).isEqualTo(40);
        assertThat(profile.parserVersion()).isEqualTo("pdfbox-3.0.8");
        assertThat(profile.chunkerVersion()).isEqualTo("parent-child-v1");
        assertThat(profile.tokenCounterVersion()).isEqualTo("unicode-codepoint-v1");
    }

    @Test
    void sealsFinalOutputAndUsesExplicitUtf16Offsets() throws Exception {
        String text = "Phase 13 😀 扩展字符 𠀀 保持定位一致。".repeat(3);
        String inputHash = "a".repeat(64);
        ParsedStructure structure = new ParsedStructure(
                new ParsedStructure.PackageMetadata(
                        "mineru",
                        "3.4.4",
                        "model-revision",
                        inputHash,
                        "b".repeat(64),
                        "mineru-content-list-v1",
                        """
                        {"schema":"mineru-content-list-v1","parser":"mineru",
                         "parserVersion":"3.4.4","parserRevision":"model-revision",
                         "inputHash":"%s","outputHash":"%s"}
                        """.formatted(inputHash, "b".repeat(64))
                ),
                List.of(new ParsedStructure.Block(
                        0,
                        ParsedDocument.BlockType.PARAGRAPH,
                        text,
                        0,
                        new ParsedStructure.BoundingBox(1, 0, 0, 1000, 1000)
                )),
                List.of(),
                List.of()
        );

        ParsedDocument parsed = parser.parseStructuredMarkdown(
                text,
                structure,
                DOCUMENT_ID,
                REVISION_ID,
                1,
                PROFILE,
                "3.4.4"
        );

        assertThat(parsed.characterCount()).isEqualTo(text.length());
        assertThat(parsed.contentBlocks().getFirst().sourceSpan().endOffset())
                .isEqualTo(text.length());
        ParsedStructure.BoundingBox box = parsed.contentBlocks().getFirst()
                .sourceSpan()
                .boundingBoxes()
                .getFirst();
        assertThat(box.sourceUnitId()).isEqualTo(parsed.sourceUnits().getFirst().id());
        assertThat(box.sourceUnitOrder()).isEqualTo(1);
        assertThat(new ObjectMapper().writeValueAsString(List.of(box)))
                .contains(
                        "\"sourceUnitId\"",
                        "\"sourceUnitOrder\":1",
                        "\"sourceUnitKind\":\"PAGE\""
                )
                .doesNotContain("\"pageNumber\"");
        assertThat(parsed.packageMetadata().outputHash())
                .isEqualTo(ParsedPackageIntegrity.canonicalHash(parsed));
        assertThat(parsed.packageMetadata().manifestJson())
                .contains(
                        "\"packageSchema\":\"parsed-package-v3\"",
                        "\"sourceLocatorSchema\":\"source-locator-v1\"",
                        "\"documentFormat\":\"PDF\"",
                        "\"parserProvider\":\"MINERU\"",
                        "\"sourceUnits\"",
                        "\"locators\"",
                        "\"offsetEncoding\":\"UTF16_CODE_UNIT\"",
                        "\"revisionId\":\"" + REVISION_ID + "\"",
                        "\"rawOutputHash\":\"" + "b".repeat(64) + "\""
                );
    }

    private void assertQuarantined(byte[] pdf, ParseQuarantineException.Reason reason) {
        assertThatThrownBy(() -> parser.parse(pdf, DOCUMENT_ID, REVISION_ID, PROFILE))
                .isInstanceOfSatisfying(ParseQuarantineException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static boolean covers(ParsedDocument.SourceSpan parent, ParsedDocument.SourceSpan child) {
        return parent.startPage() == child.startPage()
                && parent.endPage() == child.endPage()
                && parent.startOffset() <= child.startOffset()
                && parent.endOffset() >= child.endOffset();
    }

    private static byte[] textPdf(List<List<String>> pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(16);
                    content.newLineAtOffset(48, 740);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] blankPdf() throws IOException {
        return pageCountPdf(1);
    }

    private static byte[] pageCountPdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] imageOnlyPdf() throws IOException {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.drawString("scanned page", 16, 40);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject pdfImage = PDImageXObject.createFromByteArray(document, png.toByteArray(), "scan");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 48, 620, 240, 160);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] encryptedPdf(byte[] source) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission()
            );
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }
}
