package com.example.rag.pipeline;

import com.example.rag.pipeline.parser.ParsedStructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public interface MineruProvider {

    Result parse(Path pdfPath, String inputHash, UUID revisionId, int expectedSourceUnitCount)
            throws ParserProcessingException;

    /**
     * Compatibility entry point for focused tests and callers that already own a
     * bounded byte array. Production parsing uses the Path overload so the original
     * document is never duplicated into the worker heap.
     */
    default Result parse(byte[] pdfBytes, UUID revisionId, int expectedSourceUnitCount)
            throws ParserProcessingException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("rag-mineru-compat-", ".pdf");
            Files.write(temporary, pdfBytes);
            return parse(temporary, sha256(pdfBytes), revisionId, expectedSourceUnitCount);
        } catch (IOException exception) {
            throw new ParserProcessingException(
                    "MINERU_INPUT_UNAVAILABLE",
                    "Could not prepare the bounded compatibility input",
                    exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The worker's normal Path flow owns and removes its temporary file.
                }
            }
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Result(String markdown, String version, ParsedStructure structure) {
    }
}
