package com.example.rag.pipeline.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.CHILD;
import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.PARENT;
import static org.assertj.core.api.Assertions.assertThat;

class PdfParserSampleReportTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ChunkingProfile PROFILE = ChunkingProfile.phase4Default();
    private static final Path SAMPLE_OUTPUT = Path.of("target", "phase4-samples", "v1");
    private static final Path REPORT_OUTPUT = Path.of("target", "phase4-reports", "parser-samples-v1.json");

    @Test
    void generatesVersionedParserReport() throws Exception {
        byte[] manifestBytes;
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/parser-samples/v1/manifest.json"),
                "parser sample manifest"
        )) {
            manifestBytes = input.readAllBytes();
        }
        Manifest manifest = JSON.readValue(manifestBytes, Manifest.class);
        Files.createDirectories(SAMPLE_OUTPUT);
        Files.createDirectories(REPORT_OUTPUT.getParent());

        PdfDocumentParser parser = new PdfDocumentParser();
        List<SampleResult> results = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (SampleSpec sample : manifest.samples()) {
            byte[] pdf = generate(sample);
            Files.write(SAMPLE_OUTPUT.resolve(sample.id() + ".pdf"), pdf);
            if ("GENERATED_ONLY".equals(sample.expectedOutcome())) {
                results.add(new SampleResult(
                        sample.id(), sample.language(), sample.expectedOutcome(), "GENERATED_ONLY",
                        null, null, true, pdf.length, sha256(pdf), 0, 0,
                        400, 0, 0, false, false
                ));
                continue;
            }

            resetHeapPeaks();
            long started = System.nanoTime();
            String actualOutcome;
            String actualReason = null;
            String error = null;
            int pageCount = 0;
            int parentCount = 0;
            int childCount = 0;
            boolean crossPageChunk = false;
            boolean multipleParents = false;
            try {
                ParsedDocument parsed = parser.parse(
                        pdf,
                        stableUuid("document", sample.id()),
                        stableUuid("revision", sample.id()),
                        PROFILE
                );
                actualOutcome = "PARSED";
                pageCount = parsed.pageCount();
                List<ParsedDocument.Chunk> parents = parsed.chunks().stream()
                        .filter(chunk -> chunk.type() == PARENT)
                        .toList();
                List<ParsedDocument.Chunk> children = parsed.chunks().stream()
                        .filter(chunk -> chunk.type() == CHILD)
                        .toList();
                parentCount = parents.size();
                childCount = children.size();
                Set<UUID> parentIds = parents.stream().map(ParsedDocument.Chunk::id).collect(Collectors.toSet());
                assertThat(parsed.markdown()).isNotBlank();
                assertThat(parents).isNotEmpty();
                assertThat(children).isNotEmpty().allSatisfy(child -> {
                    assertThat(child.parentId()).isIn(parentIds);
                    assertThat(child.sourceSpans()).isNotEmpty().allSatisfy(span -> {
                        assertThat(span.startPage()).isPositive();
                        assertThat(span.endOffset()).isGreaterThan(span.startOffset());
                    });
                });
                crossPageChunk = parsed.chunks().stream().anyMatch(chunk -> chunk.sourceSpans().stream()
                        .map(ParsedDocument.SourceSpan::startPage).distinct().count() > 1);
                multipleParents = parents.size() > 1;
            } catch (ParseQuarantineException exception) {
                actualOutcome = "QUARANTINED";
                actualReason = exception.reason().name();
            } catch (Exception exception) {
                actualOutcome = "ERROR";
                error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            long durationMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            long peakHeapBytes = peakHeapUsedBytes();
            boolean matches = sample.expectedOutcome().equals(actualOutcome)
                    && Objects.equals(sample.expectedReason(), actualReason)
                    && (!Boolean.TRUE.equals(sample.requireCrossPageChunk()) || crossPageChunk)
                    && (!Boolean.TRUE.equals(sample.requireMultipleParents()) || multipleParents);
            if (!matches) {
                mismatches.add(sample.id() + " expected " + sample.expectedOutcome() + "/"
                        + sample.expectedReason() + " but was " + actualOutcome + "/" + actualReason);
            }
            results.add(new SampleResult(
                    sample.id(), sample.language(), sample.expectedOutcome(), actualOutcome,
                    sample.expectedReason(), actualReason, matches, pdf.length, sha256(pdf),
                    durationMillis, peakHeapBytes, pageCount, parentCount, childCount,
                    crossPageChunk, multipleParents
            ));
        }

        ParserReport report = report(manifest, manifestBytes, results);
        Files.writeString(
                REPORT_OUTPUT,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        assertThat(mismatches).as("parser sample report: %s", REPORT_OUTPUT).isEmpty();
        assertThat(report.electronicParseSuccessRate()).isEqualTo(1.0);
        assertThat(report.expectedOutcomeMatchRate()).isEqualTo(1.0);
    }

    private static ParserReport report(Manifest manifest, byte[] manifestBytes, List<SampleResult> results) {
        List<SampleResult> evaluated = results.stream()
                .filter(result -> !"GENERATED_ONLY".equals(result.expectedOutcome()))
                .toList();
        long expectedParsed = evaluated.stream().filter(result -> "PARSED".equals(result.expectedOutcome())).count();
        long successfulParsed = evaluated.stream().filter(result -> "PARSED".equals(result.actualOutcome())).count();
        long matching = evaluated.stream().filter(SampleResult::matchesExpectation).count();
        Map<String, Long> quarantineReasons = evaluated.stream()
                .filter(result -> result.actualReason() != null)
                .collect(Collectors.groupingBy(
                        SampleResult::actualReason,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        List<Long> durations = evaluated.stream()
                .map(SampleResult::durationMillis)
                .sorted(Comparator.naturalOrder())
                .toList();
        long peakHeap = evaluated.stream().mapToLong(SampleResult::peakJvmHeapUsedBytes).max().orElse(0);

        return new ParserReport(
                1,
                manifest.datasetId(),
                manifest.datasetVersion(),
                manifest.generatorVersion(),
                sha256(manifestBytes),
                PROFILE.parserVersion(),
                PROFILE.chunkerVersion(),
                PROFILE.version(),
                PROFILE.tokenCounterVersion(),
                new RuntimeEnvironment(
                        System.getProperty("java.version"),
                        System.getProperty("os.name"),
                        System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        Runtime.getRuntime().maxMemory()
                ),
                results.size(),
                evaluated.size(),
                expectedParsed,
                successfulParsed,
                ratio(successfulParsed, expectedParsed),
                ratio(matching, evaluated.size()),
                quarantineReasons,
                durationSummary(durations),
                peakHeap,
                results
        );
    }

    private static DurationSummary durationSummary(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return new DurationSummary(0, 0, 0, 0, 0);
        }
        return new DurationSummary(
                sorted.getFirst(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sorted.getLast(),
                sorted.stream().mapToLong(Long::longValue).sum()
        );
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static byte[] generate(SampleSpec sample) throws IOException {
        return switch (sample.generator()) {
            case "TEXT" -> textPdf(sample.pages());
            case "LONG_SECTION" -> longSectionPdf();
            case "IMAGE_ONLY" -> imageOnlyPdf();
            case "ENCRYPTED" -> encryptedPdf(textPdf(List.of(List.of(
                    "Protected document text remains readable before encryption is applied."
            ))));
            case "GIBBERISH" -> textPdf(List.of(List.of("!@#$%^&*()_+-=[]{};:',.<>/?".repeat(5))));
            case "BLANK" -> blankPdf();
            case "SMOKE_400" -> smokePdf(400);
            default -> throw new IllegalArgumentException("Unknown PDF sample generator: " + sample.generator());
        };
    }

    private static byte[] longSectionPdf() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("1 Long Deterministic Section");
        for (int index = 0; index < 80; index++) {
            lines.add("Paragraph " + index
                    + " preserves traceable evidence while deterministic parent boundaries remain bounded.");
        }
        return textPdf(List.of(lines));
    }

    private static byte[] smokePdf(int pageCount) throws IOException {
        List<List<String>> pages = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pages.add(List.of(
                    "Page " + page + " Lease Recovery",
                    "This generated document keeps enough readable evidence to exercise worker interruption and retry."
            ));
        }
        return textPdf(pages);
    }

    private static byte[] textPdf(List<List<String>> pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            prepareDeterministicMetadata(document);
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
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            prepareDeterministicMetadata(document);
            document.addPage(new PDPage());
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
            prepareDeterministicMetadata(document);
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
        try (PDDocument document = Loader.loadPDF(source); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission()
            );
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void prepareDeterministicMetadata(PDDocument document) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setProducer("phase4-pdfbox-fixtures-v1");
        information.setCreationDate(java.util.GregorianCalendar.from(Instant.EPOCH.atZone(java.time.ZoneOffset.UTC)));
        information.setModificationDate(java.util.GregorianCalendar.from(Instant.EPOCH.atZone(java.time.ZoneOffset.UTC)));
        document.setDocumentInformation(information);
        COSArray id = new COSArray();
        id.add(new COSString("phase4-sample-v1"));
        id.add(new COSString("phase4-sample-v1"));
        document.getDocument().getTrailer().setItem(COSName.ID, id);
    }

    private static void resetHeapPeaks() {
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapUsedBytes() {
        return heapPools().stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }

    private static UUID stableUuid(String type, String sampleId) {
        return UUID.nameUUIDFromBytes((type + ":" + sampleId).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Manifest(
            String datasetId,
            String datasetVersion,
            String generatorVersion,
            List<SampleSpec> samples
    ) {
    }

    private record SampleSpec(
            String id,
            String language,
            String generator,
            String expectedOutcome,
            String expectedReason,
            Boolean requireCrossPageChunk,
            Boolean requireMultipleParents,
            List<List<String>> pages
    ) {
    }

    private record RuntimeEnvironment(
            String javaVersion,
            String os,
            String arch,
            int availableProcessors,
            long maxHeapBytes
    ) {
    }

    private record DurationSummary(long min, long p50, long p95, long max, long total) {
    }

    private record SampleResult(
            String id,
            String language,
            String expectedOutcome,
            String actualOutcome,
            String expectedReason,
            String actualReason,
            boolean matchesExpectation,
            long fileSizeBytes,
            String sha256,
            long durationMillis,
            long peakJvmHeapUsedBytes,
            int pageCount,
            int parentCount,
            int childCount,
            boolean crossPageChunk,
            boolean multipleParents
    ) {
    }

    private record ParserReport(
            int schemaVersion,
            String datasetId,
            String datasetVersion,
            String generatorVersion,
            String manifestSha256,
            String parserVersion,
            String chunkerVersion,
            String chunkingProfileVersion,
            String tokenCounterVersion,
            RuntimeEnvironment environment,
            int sampleCount,
            int evaluatedSampleCount,
            long expectedParsedCount,
            long successfulParsedCount,
            double electronicParseSuccessRate,
            double expectedOutcomeMatchRate,
            Map<String, Long> quarantineReasonCounts,
            DurationSummary durationMillis,
            long peakJvmHeapUsedBytes,
            List<SampleResult> samples
    ) {
    }
}
