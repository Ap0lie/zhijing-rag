package com.example.rag.pipeline.parser;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserProviderKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.ENCRYPTED_PDF;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.GIBBERISH_TEXT;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.LOW_QUALITY_TEXT;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.PAGE_LIMIT_EXCEEDED;
import static com.example.rag.pipeline.parser.ParseQuarantineException.Reason.SCANNED_PDF;
import static com.example.rag.pipeline.parser.ParsedDocument.BlockType.HEADING;
import static com.example.rag.pipeline.parser.ParsedDocument.BlockType.PARAGRAPH;

/** A deterministic PDFBox parser with no database or worker lifecycle concerns. */
public final class PdfDocumentParser {

    private static final int MAX_PAGE_COUNT = 1_000;
    private static final String PDF_NORMALIZATION_VERSION = "pdf-nfkc-whitespace-v1";
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+.+");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:[、.)])?\\s+.+");
    private static final Pattern CHINESE_HEADING = Pattern.compile("^第[一二三四五六七八九十百千万零〇0-9]+([章节篇部])(?:\\s*.*)$");

    public ParsedDocument parse(
            byte[] pdfBytes,
            UUID documentId,
            UUID revisionId,
            ChunkingProfile profile
    ) throws IOException, ParseQuarantineException {
        Objects.requireNonNull(pdfBytes, "pdfBytes");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(profile, "profile");

        Instant startedAt = Instant.now();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return parseLoadedDocument(
                    document,
                    documentId,
                    revisionId,
                    profile,
                    startedAt,
                    sha256(pdfBytes)
            );
        } catch (InvalidPasswordException exception) {
            throw new ParseQuarantineException(
                    ENCRYPTED_PDF,
                    "A password is required to open this PDF",
                    exception
            );
        }
    }

    public ParsedDocument parse(
            Path pdfPath,
            UUID documentId,
            UUID revisionId,
            ChunkingProfile profile,
            String inputHash
    ) throws IOException, ParseQuarantineException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(inputHash, "inputHash");

        Instant startedAt = Instant.now();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return parseLoadedDocument(
                    document,
                    documentId,
                    revisionId,
                    profile,
                    startedAt,
                    inputHash
            );
        } catch (InvalidPasswordException exception) {
            throw new ParseQuarantineException(
                    ENCRYPTED_PDF,
                    "A password is required to open this PDF",
                    exception
            );
        }
    }

    public ParsedDocument parse(
            Path pdfPath,
            UUID documentId,
            UUID revisionId,
            ChunkingProfile profile
    ) throws IOException, ParseQuarantineException {
        return parse(pdfPath, documentId, revisionId, profile, sha256(pdfPath));
    }

    private static ParsedDocument parseLoadedDocument(
            PDDocument document,
            UUID documentId,
            UUID revisionId,
            ChunkingProfile profile,
            Instant startedAt,
            String inputHash
    ) throws IOException, ParseQuarantineException {
        if (document.isEncrypted()) {
            throw new ParseQuarantineException(ENCRYPTED_PDF, "Encrypted PDFs are not parsed");
        }
        if (document.getNumberOfPages() > MAX_PAGE_COUNT) {
            throw new ParseQuarantineException(
                    PAGE_LIMIT_EXCEEDED,
                    "PDF page count exceeds the " + MAX_PAGE_COUNT + "-page parsing limit"
            );
        }

        List<PageText> pages = extractPages(document);
        validateQuality(document, pages, profile);
        List<ParsedDocument.SourceUnit> sourceUnits = pageSourceUnits(revisionId, pages);
        List<ParsedDocument.ContentBlock> blocks = extractBlocks(
                revisionId,
                pages,
                sourceUnits,
                profile
        );
        return assemble(
                documentId,
                revisionId,
                blocks,
                sourceUnits,
                ParserProviderKind.PDFBOX,
                profile,
                profile.parserVersion(),
                startedAt,
                null,
                List.of(),
                List.of(),
                null,
                inputHash
        );
    }

    public ParsedDocument parseStructuredMarkdown(
            String markdown,
            ParsedStructure structure,
            UUID documentId,
            UUID revisionId,
            int pageCount,
            ChunkingProfile profile,
            String parserVersion
    ) throws ParseQuarantineException {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Instant startedAt = Instant.now();
        String normalized = normalize(markdown);
        TextQuality quality = inspect(normalized);
        if (quality.meaningfulCharacters() < profile.minMeaningfulCharacters()) {
            throw new ParseQuarantineException(
                    LOW_QUALITY_TEXT,
                    "MinerU did not return enough readable Markdown"
            );
        }
        if (quality.noiseRatio() > profile.maxNoiseRatio()
                || quality.readableRatio() < profile.minReadableRatio()) {
            throw new ParseQuarantineException(
                    GIBBERISH_TEXT,
                    "MinerU Markdown did not pass quality checks"
            );
        }
        List<ParsedDocument.SourceUnit> sourceUnits =
                structuredPageSourceUnits(revisionId, structure.blocks(), pageCount);
        List<ParsedDocument.ContentBlock> blocks = extractStructuredBlocks(
                revisionId,
                structure.blocks(),
                sourceUnits,
                profile
        );
        List<ParsedStructure.Table> tables = bindTables(structure.tables(), sourceUnits);
        List<ParsedStructure.Image> images = bindImages(structure.images(), sourceUnits);
        return assemble(
                documentId,
                revisionId,
                blocks,
                sourceUnits,
                ParserProviderKind.MINERU,
                profile,
                parserVersion,
                startedAt,
                normalized + '\n',
                tables,
                images,
                structure.packageMetadata(),
                null
        );
    }

    private static ParsedDocument assemble(
            UUID documentId,
            UUID revisionId,
            List<ParsedDocument.ContentBlock> blocks,
            List<ParsedDocument.SourceUnit> sourceUnits,
            ParserProviderKind parserProvider,
            ChunkingProfile profile,
            String parserVersion,
            Instant startedAt,
            String markdown,
            List<ParsedStructure.Table> tables,
            List<ParsedStructure.Image> images,
            ParsedStructure.PackageMetadata suppliedMetadata,
            String inputHash
    ) {
        List<ParsedDocument.Chunk> chunks = createChunks(revisionId, blocks, profile);
        String normalizedMarkdown = markdown == null ? toMarkdown(blocks) : markdown;
        int characterCount = blocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::characterCount)
                .sum();
        int tokenCount = blocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::tokenCount)
                .sum();
        ParsedStructure.PackageMetadata metadata;
        if (suppliedMetadata != null) {
            metadata = suppliedMetadata;
        } else if (inputHash != null) {
            String outputHash = sha256(normalizedMarkdown);
            metadata = new ParsedStructure.PackageMetadata(
                    "pdfbox",
                    parserVersion,
                    null,
                    inputHash,
                    outputHash,
                    "pdfbox-text-v1",
                    "{\"schema\":\"pdfbox-text-v1\",\"parser\":\"pdfbox\","
                            + "\"parserVersion\":\"" + parserVersion + "\",\"inputHash\":\""
                            + inputHash + "\",\"outputHash\":\"" + outputHash + "\"}"
            );
        } else {
            throw new IllegalArgumentException("Parser package metadata is missing");
        }
        return ParsedPackageIntegrity.seal(new ParsedDocument(
                documentId,
                revisionId,
                DocumentFormat.PDF,
                parserProvider,
                sourceUnits,
                normalizedMarkdown,
                blocks,
                chunks,
                metadata,
                tables,
                images,
                characterCount,
                tokenCount,
                profile.version(),
                parserVersion,
                profile.chunkerVersion(),
                profile.tokenCounterVersion(),
                Duration.between(startedAt, Instant.now()).toMillis()
        ));
    }

    private static List<PageText> extractPages(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<PageText> pages = new ArrayList<>(document.getNumberOfPages());
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            stripper.setStartPage(index + 1);
            stripper.setEndPage(index + 1);
            pages.add(new PageText(index + 1, normalize(stripper.getText(document))));
        }
        return pages;
    }

    private static List<ParsedDocument.SourceUnit> pageSourceUnits(
            UUID revisionId,
            List<PageText> pages
    ) {
        return pages.stream()
                .map(page -> pageSourceUnit(revisionId, page.number(), page.text()))
                .toList();
    }

    private static List<ParsedDocument.SourceUnit> structuredPageSourceUnits(
            UUID revisionId,
            List<ParsedStructure.Block> blocks,
            int pageCount
    ) {
        List<StringBuilder> pages = new ArrayList<>(pageCount);
        for (int index = 0; index < pageCount; index++) {
            pages.add(new StringBuilder());
        }
        int previousPage = 0;
        for (int index = 0; index < blocks.size(); index++) {
            ParsedStructure.Block block = blocks.get(index);
            int page = block.boundingBox().pageNumber();
            if (block.order() != index || page < previousPage || page < 1 || page > pageCount) {
                throw new IllegalArgumentException("Structured block order or page is invalid");
            }
            previousPage = page;
            StringBuilder canonical = pages.get(page - 1);
            if (!canonical.isEmpty()) {
                canonical.append("\n\n");
            }
            canonical.append(block.text());
        }
        List<ParsedDocument.SourceUnit> result = new ArrayList<>(pageCount);
        for (int index = 0; index < pages.size(); index++) {
            result.add(pageSourceUnit(revisionId, index + 1, pages.get(index).toString()));
        }
        return List.copyOf(result);
    }

    private static ParsedDocument.SourceUnit pageSourceUnit(
            UUID revisionId,
            int page,
            String canonicalText
    ) {
        return new ParsedDocument.SourceUnit(
                stableUuid("source-unit", revisionId, SourceUnitKind.PAGE.name(), page),
                page,
                SourceUnitKind.PAGE,
                "page:" + page,
                canonicalText,
                sha256(canonicalText),
                PDF_NORMALIZATION_VERSION,
                "{\"page\":" + page + "}"
        );
    }

    private static Map<Integer, ParsedDocument.SourceUnit> sourceUnitsByOrder(
            List<ParsedDocument.SourceUnit> sourceUnits
    ) {
        Map<Integer, ParsedDocument.SourceUnit> result = new HashMap<>();
        for (ParsedDocument.SourceUnit sourceUnit : sourceUnits) {
            if (result.put(sourceUnit.order(), sourceUnit) != null) {
                throw new IllegalArgumentException("SourceUnit order is not unique");
            }
        }
        return Map.copyOf(result);
    }

    private static List<ParsedStructure.Table> bindTables(
            List<ParsedStructure.Table> tables,
            List<ParsedDocument.SourceUnit> sourceUnits
    ) {
        Map<Integer, ParsedDocument.SourceUnit> units = sourceUnitsByOrder(sourceUnits);
        return tables.stream()
                .map(table -> table.bind(requireSourceUnit(units, table.boundingBox().pageNumber())))
                .toList();
    }

    private static List<ParsedStructure.Image> bindImages(
            List<ParsedStructure.Image> images,
            List<ParsedDocument.SourceUnit> sourceUnits
    ) {
        Map<Integer, ParsedDocument.SourceUnit> units = sourceUnitsByOrder(sourceUnits);
        return images.stream()
                .map(image -> image.bind(requireSourceUnit(units, image.boundingBox().pageNumber())))
                .toList();
    }

    private static ParsedDocument.SourceUnit requireSourceUnit(
            Map<Integer, ParsedDocument.SourceUnit> sourceUnits,
            int order
    ) {
        ParsedDocument.SourceUnit sourceUnit = sourceUnits.get(order);
        if (sourceUnit == null) {
            throw new IllegalArgumentException("Structured result references an unknown SourceUnit");
        }
        return sourceUnit;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00a0', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean horizontalWhitespace = false;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                trimTrailingSpaces(result);
                result.append('\n');
                horizontalWhitespace = false;
            } else if (Character.isWhitespace(codePoint)) {
                horizontalWhitespace = true;
            } else {
                if (horizontalWhitespace && !result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
                    result.append(' ');
                }
                result.appendCodePoint(codePoint);
                horizontalWhitespace = false;
            }
        }
        trimTrailingSpaces(result);
        return result.toString().strip();
    }

    private static void trimTrailingSpaces(StringBuilder value) {
        while (!value.isEmpty() && value.charAt(value.length() - 1) == ' ') {
            value.setLength(value.length() - 1);
        }
    }

    private static void validateQuality(
            PDDocument document,
            List<PageText> pages,
            ChunkingProfile profile
    ) throws IOException, ParseQuarantineException {
        String text = pages.stream().map(PageText::text).reduce("", (left, right) -> left + "\n" + right);
        TextQuality quality = inspect(text);
        boolean containsImage = containsImage(document);

        if (containsImage && quality.meaningfulCharacters() < profile.minMeaningfulCharacters()) {
            throw new ParseQuarantineException(SCANNED_PDF, "The PDF appears to contain scanned pages without text");
        }
        if (quality.nonWhitespaceCharacters() >= profile.minMeaningfulCharacters()
                && (quality.noiseRatio() > profile.maxNoiseRatio()
                || quality.readableRatio() < profile.minReadableRatio())) {
            throw new ParseQuarantineException(GIBBERISH_TEXT, "Extracted PDF text did not pass quality checks");
        }
        if (quality.meaningfulCharacters() < profile.minMeaningfulCharacters()) {
            throw new ParseQuarantineException(LOW_QUALITY_TEXT, "The PDF does not contain enough readable text");
        }
    }

    private static TextQuality inspect(String text) {
        int nonWhitespace = 0;
        int meaningful = 0;
        int noise = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            nonWhitespace++;
            if (Character.isLetterOrDigit(codePoint)) {
                meaningful++;
            }
            int type = Character.getType(codePoint);
            if (codePoint == 0xfffd || Character.isISOControl(codePoint)
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
                noise++;
            }
        }
        double noiseRatio = nonWhitespace == 0 ? 0 : (double) noise / nonWhitespace;
        double readableRatio = nonWhitespace == 0 ? 0 : (double) meaningful / nonWhitespace;
        return new TextQuality(nonWhitespace, meaningful, noiseRatio, readableRatio);
    }

    private static boolean containsImage(PDDocument document) throws IOException {
        for (PDPage page : document.getPages()) {
            if (page.getResources() == null) {
                continue;
            }
            for (COSName name : page.getResources().getXObjectNames()) {
                PDXObject object = page.getResources().getXObject(name);
                if (object instanceof PDImageXObject) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ParsedDocument.ContentBlock> extractBlocks(
            UUID revisionId,
            List<PageText> pages,
            List<ParsedDocument.SourceUnit> sourceUnits,
            ChunkingProfile profile
    ) {
        List<ParsedDocument.ContentBlock> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        Map<Integer, ParsedDocument.SourceUnit> units = sourceUnitsByOrder(sourceUnits);
        int order = 0;
        for (PageText page : pages) {
            ParsedDocument.SourceUnit sourceUnit = requireSourceUnit(units, page.number());
            int cursor = 0;
            while (cursor < page.text().length()) {
                int lineEnd = page.text().indexOf('\n', cursor);
                lineEnd = lineEnd < 0 ? page.text().length() : lineEnd;
                int start = cursor;
                int end = lineEnd;
                while (start < end && Character.isWhitespace(page.text().charAt(start))) {
                    start++;
                }
                while (end > start && Character.isWhitespace(page.text().charAt(end - 1))) {
                    end--;
                }
                if (start < end) {
                    String text = page.text().substring(start, end);
                    int depth = headingDepth(text);
                    ParsedDocument.BlockType type = depth == 0 ? PARAGRAPH : HEADING;
                    if (depth > 0) {
                        while (headingPath.size() >= depth) {
                            headingPath.removeLast();
                        }
                        headingPath.add(text);
                    }
                    ParsedDocument.SourceSpan span = sourceSpan(
                            sourceUnit,
                            start,
                            end,
                            0,
                            text.length(),
                            sha256(text),
                            List.of()
                    );
                    UUID id = stableUuid("block", revisionId, order, page.number(), start, end, sha256(text));
                    blocks.add(new ParsedDocument.ContentBlock(
                            id,
                            order++,
                            type,
                            text,
                            headingPath,
                            countCharacters(text),
                            countTokens(text),
                            profile.tokenCounterVersion(),
                            span
                    ));
                }
                cursor = lineEnd + 1;
            }
        }
        return List.copyOf(blocks);
    }

    private static List<ParsedDocument.ContentBlock> extractStructuredBlocks(
            UUID revisionId,
            List<ParsedStructure.Block> sourceBlocks,
            List<ParsedDocument.SourceUnit> sourceUnits,
            ChunkingProfile profile
    ) {
        List<ParsedDocument.ContentBlock> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        Map<Integer, Integer> pageOffsets = new HashMap<>();
        Map<Integer, ParsedDocument.SourceUnit> units = sourceUnitsByOrder(sourceUnits);
        int previousPage = 0;
        for (int index = 0; index < sourceBlocks.size(); index++) {
            ParsedStructure.Block source = sourceBlocks.get(index);
            if (source.order() != index
                    || !units.containsKey(source.boundingBox().pageNumber())
                    || source.boundingBox().pageNumber() < previousPage) {
                throw new IllegalArgumentException("Structured block order or page is invalid");
            }
            previousPage = source.boundingBox().pageNumber();
            ParsedDocument.SourceUnit sourceUnit = requireSourceUnit(units, previousPage);
            int start = pageOffsets.getOrDefault(previousPage, 0);
            int end = start + source.text().length();
            pageOffsets.put(previousPage, end + 2);

            if (source.type() == HEADING) {
                int depth = source.headingLevel() == 0
                        ? Math.max(1, headingDepth(source.text()))
                        : source.headingLevel();
                while (headingPath.size() >= depth) {
                    headingPath.removeLast();
                }
                headingPath.add(source.text());
            }
            String hash = sha256(source.text());
            ParsedDocument.SourceSpan span = sourceSpan(
                    sourceUnit,
                    start,
                    end,
                    0,
                    source.text().length(),
                    hash,
                    List.of(source.boundingBox().bind(sourceUnit))
            );
            blocks.add(new ParsedDocument.ContentBlock(
                    stableUuid(
                            "block",
                            revisionId,
                            source.order(),
                            previousPage,
                            start,
                            end,
                            hash
                    ),
                    source.order(),
                    source.type(),
                    source.text(),
                    headingPath,
                    countCharacters(source.text()),
                    countTokens(source.text()),
                    profile.tokenCounterVersion(),
                    span
            ));
        }
        return List.copyOf(blocks);
    }

    private static int headingDepth(String text) {
        Matcher markdown = MARKDOWN_HEADING.matcher(text);
        if (markdown.matches()) {
            return markdown.group(1).length();
        }
        Matcher numbered = NUMBERED_HEADING.matcher(text);
        if (numbered.matches()) {
            return Math.min(6, 1 + (int) numbered.group(1).chars().filter(value -> value == '.').count());
        }
        Matcher chinese = CHINESE_HEADING.matcher(text);
        if (chinese.matches()) {
            return "章篇部".contains(chinese.group(1)) ? 1 : 2;
        }
        long latinLetters = text.codePoints()
                .filter(Character::isLetter)
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN)
                .count();
        boolean containsLowercaseLatin = text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN)
                .anyMatch(Character::isLowerCase);
        if (text.length() <= 80 && latinLetters >= 4 && !containsLowercaseLatin) {
            return 1;
        }
        return 0;
    }

    private static String toMarkdown(List<ParsedDocument.ContentBlock> blocks) {
        StringBuilder markdown = new StringBuilder();
        for (ParsedDocument.ContentBlock block : blocks) {
            if (!markdown.isEmpty()) {
                markdown.append("\n\n");
            }
            if (block.type() == HEADING) {
                markdown.append("#".repeat(Math.max(1, Math.min(6, block.headingPath().size())))).append(' ');
            }
            markdown.append(block.text());
        }
        return markdown.append('\n').toString();
    }

    private static List<ParsedDocument.Chunk> createChunks(
            UUID revisionId,
            List<ParsedDocument.ContentBlock> blocks,
            ChunkingProfile profile
    ) {
        return FormatNeutralChunker.createChunks(revisionId, blocks, profile);
    }

    private static ParsedDocument.SourceSpan sourceSpan(
            ParsedDocument.SourceUnit sourceUnit,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash,
            List<ParsedStructure.BoundingBox> boundingBoxes
    ) {
        return new ParsedDocument.SourceSpan(
                sourceUnit.id(),
                sourceUnit.id(),
                sourceUnit.order(),
                sourceUnit.order(),
                SourceLocatorKind.PAGE,
                locatorAddress(
                        sourceUnit.stableAddress(),
                        sourceUnit.stableAddress(),
                        startOffset,
                        endOffset
                ),
                startOffset,
                endOffset,
                chunkStartOffset,
                chunkEndOffset,
                sourceTextHash,
                sourceUnit.normalizationVersion(),
                boundingBoxes
        );
    }

    private static String locatorAddress(
            String startAddress,
            String endAddress,
            int startOffset,
            int endOffset
    ) {
        String units = startAddress.equals(endAddress)
                ? startAddress
                : startAddress + ".." + endAddress;
        return units + "#chars=" + startOffset + "-" + endOffset;
    }

    static int countTokens(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
        }
        return count;
    }

    private static int countCharacters(String text) {
        return text.length();
    }

    private static UUID stableUuid(Object... parts) {
        byte[] bytes = sha256Bytes(String.join("|", Arrays.stream(parts).map(String::valueOf).toList()));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
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

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record PageText(int number, String text) {
    }

    private record TextQuality(
            int nonWhitespaceCharacters,
            int meaningfulCharacters,
            double noiseRatio,
            double readableRatio
    ) {
    }

}
