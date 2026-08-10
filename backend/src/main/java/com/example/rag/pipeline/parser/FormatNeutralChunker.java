package com.example.rag.pipeline.parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.CHILD;
import static com.example.rag.pipeline.parser.ParsedDocument.ChunkType.PARENT;

/**
 * Deterministic Parent/Child chunk construction shared by every document
 * parser. Parser providers own source normalization and SourceUnit creation;
 * this class only groups and slices already validated ContentBlocks.
 */
final class FormatNeutralChunker {

    private FormatNeutralChunker() {
    }

    static List<ParsedDocument.Chunk> createChunks(
            UUID revisionId,
            List<ParsedDocument.ContentBlock> blocks,
            ChunkingProfile profile
    ) {
        return createChunks(revisionId, blocks, profile, Set.of());
    }

    static List<ParsedDocument.Chunk> createChunks(
            UUID revisionId,
            List<ParsedDocument.ContentBlock> blocks,
            ChunkingProfile profile,
            Set<Integer> parentBreakBeforeBlockOrders
    ) {
        List<Fragment> fragments = new ArrayList<>();
        for (ParsedDocument.ContentBlock block : blocks) {
            fragments.addAll(splitBlock(block, profile.parentMaxTokens()));
        }

        List<List<Fragment>> parentGroups = new ArrayList<>();
        List<Fragment> current = new ArrayList<>();
        int currentTokens = 0;
        int previousBlockOrder = -1;
        for (Fragment fragment : fragments) {
            int tokens = countTokens(fragment.text());
            boolean newSection = fragment.type() == ParsedDocument.BlockType.HEADING
                    && !current.isEmpty();
            boolean forcedBoundary = !current.isEmpty()
                    && fragment.blockOrder() != previousBlockOrder
                    && parentBreakBeforeBlockOrders.contains(fragment.blockOrder());
            if (newSection || forcedBoundary
                    || currentTokens + tokens > profile.parentMaxTokens()) {
                parentGroups.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }
            current.add(fragment);
            currentTokens += tokens;
            previousBlockOrder = fragment.blockOrder();
        }
        if (!current.isEmpty()) {
            parentGroups.add(List.copyOf(current));
        }

        List<ParsedDocument.Chunk> chunks = new ArrayList<>();
        int parentOrder = 0;
        int childOrder = 0;
        for (List<Fragment> group : parentGroups) {
            Material material = materialize(group);
            UUID parentId = stableUuid(
                    "parent",
                    revisionId,
                    profile.version(),
                    parentOrder,
                    sha256(material.text()),
                    spanSignature(material.sourceSpans())
            );
            chunks.add(new ParsedDocument.Chunk(
                    parentId,
                    PARENT,
                    null,
                    parentOrder++,
                    material.startBlockOrder(),
                    material.endBlockOrder(),
                    material.text(),
                    material.headingPath(),
                    material.text().length(),
                    countTokens(material.text()),
                    profile.tokenCounterVersion(),
                    material.sourceSpans()
            ));

            int start = 0;
            while (start < material.text().length()) {
                start = skipWhitespace(material.text(), start);
                if (start >= material.text().length()) {
                    break;
                }
                int end = trimWhitespace(
                        material.text(),
                        start,
                        advanceByTokens(
                                material.text(),
                                start,
                                profile.childMaxTokens()
                        )
                );
                List<ParsedDocument.SourceSpan> spans =
                        spansForRange(material, start, end);
                String text = material.text().substring(start, end);
                List<String> headingPath =
                        headingPathForRange(material, start, end);
                BlockRange blockRange =
                        blockRangeForRange(material, start, end);
                UUID childId = stableUuid(
                        "child",
                        revisionId,
                        profile.version(),
                        parentId,
                        childOrder,
                        sha256(text),
                        spanSignature(spans)
                );
                chunks.add(new ParsedDocument.Chunk(
                        childId,
                        CHILD,
                        parentId,
                        childOrder++,
                        blockRange.start(),
                        blockRange.end(),
                        text,
                        headingPath,
                        text.length(),
                        countTokens(text),
                        profile.tokenCounterVersion(),
                        spans
                ));
                if (end >= material.text().length()) {
                    break;
                }
                int next = rewindByTokens(
                        material.text(),
                        end,
                        profile.childOverlapTokens()
                );
                start = next <= start ? end : next;
            }
        }
        return List.copyOf(chunks);
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

    static UUID stableUuid(Object... parts) {
        byte[] bytes = sha256Bytes(
                String.join(
                        "|",
                        Arrays.stream(parts).map(String::valueOf).toList()
                )
        );
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static List<Fragment> splitBlock(
            ParsedDocument.ContentBlock block,
            int maxTokens
    ) {
        List<Fragment> fragments = new ArrayList<>();
        int start = 0;
        while (start < block.text().length()) {
            start = skipWhitespace(block.text(), start);
            if (start >= block.text().length()) {
                break;
            }
            int end = trimWhitespace(
                    block.text(),
                    start,
                    advanceByTokens(block.text(), start, maxTokens)
            );
            ParsedDocument.SourceSpan source = block.sourceSpan();
            ParsedDocument.SourceSpan span = sliceSpan(
                    source,
                    source.startOffset() + start,
                    source.startOffset() + end,
                    0,
                    end - start,
                    sha256(block.text().substring(start, end))
            );
            fragments.add(new Fragment(
                    block.text().substring(start, end),
                    block.headingPath(),
                    span,
                    block.type(),
                    block.order()
            ));
            start = end;
        }
        return fragments;
    }

    private static Material materialize(List<Fragment> fragments) {
        StringBuilder text = new StringBuilder();
        List<MappedSpan> mappings = new ArrayList<>();
        List<ParsedDocument.SourceSpan> sourceSpans = new ArrayList<>();
        for (Fragment fragment : fragments) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            int start = text.length();
            text.append(fragment.text());
            int end = text.length();
            mappings.add(new MappedSpan(
                    start,
                    end,
                    fragment.span(),
                    fragment.headingPath(),
                    fragment.blockOrder()
            ));
            ParsedDocument.SourceSpan source = fragment.span();
            sourceSpans.add(sliceSpan(
                    source,
                    source.startOffset(),
                    source.endOffset(),
                    start,
                    end,
                    source.sourceTextHash()
            ));
        }
        List<String> headingPath = fragments.stream()
                .map(Fragment::headingPath)
                .filter(path -> !path.isEmpty())
                .findFirst()
                .orElse(List.of());
        int firstBlock = fragments.stream()
                .mapToInt(Fragment::blockOrder)
                .min()
                .orElseThrow();
        int lastBlock = fragments.stream()
                .mapToInt(Fragment::blockOrder)
                .max()
                .orElseThrow();
        return new Material(
                text.toString(),
                headingPath,
                List.copyOf(mappings),
                List.copyOf(sourceSpans),
                firstBlock,
                lastBlock
        );
    }

    private static List<ParsedDocument.SourceSpan> spansForRange(
            Material material,
            int start,
            int end
    ) {
        List<ParsedDocument.SourceSpan> result = new ArrayList<>();
        for (MappedSpan mapped : material.mappings()) {
            int overlapStart = Math.max(start, mapped.textStart());
            int overlapEnd = Math.min(end, mapped.textEnd());
            if (overlapStart >= overlapEnd) {
                continue;
            }
            int relativeStart = overlapStart - mapped.textStart();
            int relativeEnd = overlapEnd - mapped.textStart();
            ParsedDocument.SourceSpan source = mapped.source();
            result.add(sliceSpan(
                    source,
                    source.startOffset() + relativeStart,
                    source.startOffset() + relativeEnd,
                    overlapStart - start,
                    overlapEnd - start,
                    sha256(material.text().substring(overlapStart, overlapEnd))
            ));
        }
        return List.copyOf(result);
    }

    private static ParsedDocument.SourceSpan sliceSpan(
            ParsedDocument.SourceSpan source,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash
    ) {
        String unitAddress = source.address().split("#", 2)[0];
        return new ParsedDocument.SourceSpan(
                source.startSourceUnitId(),
                source.endSourceUnitId(),
                source.startSourceUnitOrder(),
                source.endSourceUnitOrder(),
                source.locatorKind(),
                locatorAddress(unitAddress, startOffset, endOffset),
                startOffset,
                endOffset,
                chunkStartOffset,
                chunkEndOffset,
                sourceTextHash,
                source.normalizationVersion(),
                source.boundingBoxes()
        );
    }

    private static String locatorAddress(
            String unitAddress,
            int startOffset,
            int endOffset
    ) {
        return unitAddress + "#chars=" + startOffset + "-" + endOffset;
    }

    private static List<String> headingPathForRange(
            Material material,
            int start,
            int end
    ) {
        return material.mappings().stream()
                .filter(mapped ->
                        Math.max(start, mapped.textStart())
                                < Math.min(end, mapped.textEnd()))
                .map(MappedSpan::headingPath)
                .filter(path -> !path.isEmpty())
                .findFirst()
                .orElse(material.headingPath());
    }

    private static BlockRange blockRangeForRange(
            Material material,
            int start,
            int end
    ) {
        List<MappedSpan> matches = material.mappings().stream()
                .filter(mapped ->
                        Math.max(start, mapped.textStart())
                                < Math.min(end, mapped.textEnd()))
                .toList();
        int first = matches.stream()
                .mapToInt(MappedSpan::blockOrder)
                .min()
                .orElseThrow();
        int last = matches.stream()
                .mapToInt(MappedSpan::blockOrder)
                .max()
                .orElseThrow();
        return new BlockRange(first, last);
    }

    private static int advanceByTokens(String text, int start, int limit) {
        int tokens = 0;
        int index = start;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && tokens == limit) {
                break;
            }
            if (!Character.isWhitespace(codePoint)) {
                tokens++;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int rewindByTokens(String text, int end, int limit) {
        if (limit == 0) {
            return end;
        }
        int tokens = 0;
        int index = end;
        while (index > 0 && tokens < limit) {
            int codePoint = text.codePointBefore(index);
            index -= Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                tokens++;
            }
        }
        return skipWhitespace(text, index);
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int trimWhitespace(String text, int start, int end) {
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return end;
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String spanSignature(
            List<ParsedDocument.SourceSpan> spans
    ) {
        return spans.stream()
                .map(span ->
                        span.startSourceUnitId() + ":"
                                + span.endSourceUnitId() + ":"
                                + span.locatorKind().name() + ":"
                                + span.startOffset() + ":"
                                + span.endOffset() + ":"
                                + span.chunkStartOffset() + ":"
                                + span.chunkEndOffset() + ":"
                                + span.sourceTextHash() + ":"
                                + span.normalizationVersion() + ":"
                                + span.boundingBoxes())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private record Fragment(
            String text,
            List<String> headingPath,
            ParsedDocument.SourceSpan span,
            ParsedDocument.BlockType type,
            int blockOrder
    ) {
    }

    private record MappedSpan(
            int textStart,
            int textEnd,
            ParsedDocument.SourceSpan source,
            List<String> headingPath,
            int blockOrder
    ) {
    }

    private record Material(
            String text,
            List<String> headingPath,
            List<MappedSpan> mappings,
            List<ParsedDocument.SourceSpan> sourceSpans,
            int startBlockOrder,
            int endBlockOrder
    ) {
    }

    private record BlockRange(int start, int end) {
    }
}
