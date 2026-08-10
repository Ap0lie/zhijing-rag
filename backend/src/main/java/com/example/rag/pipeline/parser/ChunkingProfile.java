package com.example.rag.pipeline.parser;

/** Versioned, deterministic limits used by the Phase 4 parser and chunker. */
public record ChunkingProfile(
        String version,
        int parentMaxTokens,
        int childMaxTokens,
        int childOverlapTokens,
        int minMeaningfulCharacters,
        double maxNoiseRatio,
        double minReadableRatio,
        String parserVersion,
        String chunkerVersion,
        String tokenCounterVersion
) {

    public ChunkingProfile {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        if (parentMaxTokens <= 0 || childMaxTokens <= 0 || childMaxTokens > parentMaxTokens) {
            throw new IllegalArgumentException("chunk token limits are invalid");
        }
        if (childOverlapTokens < 0 || childOverlapTokens >= childMaxTokens) {
            throw new IllegalArgumentException("child overlap must be smaller than childMaxTokens");
        }
        if (minMeaningfulCharacters < 1) {
            throw new IllegalArgumentException("minMeaningfulCharacters must be positive");
        }
        if (maxNoiseRatio < 0 || maxNoiseRatio > 1 || minReadableRatio < 0 || minReadableRatio > 1) {
            throw new IllegalArgumentException("quality ratios must be between 0 and 1");
        }
        requireVersion(parserVersion, "parserVersion");
        requireVersion(chunkerVersion, "chunkerVersion");
        requireVersion(tokenCounterVersion, "tokenCounterVersion");
    }

    public static ChunkingProfile phase4Default() {
        return new ChunkingProfile(
                "phase4-v1",
                1_200,
                300,
                40,
                40,
                0.02,
                0.30,
                "pdfbox-3.0.8",
                "parent-child-v1",
                "unicode-codepoint-v1"
        );
    }

    private static void requireVersion(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
