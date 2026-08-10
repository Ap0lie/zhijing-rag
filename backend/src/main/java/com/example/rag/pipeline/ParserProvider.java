package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;

import java.io.IOException;
import java.util.Set;

/**
 * Narrow format/provider boundary. Lifecycle, ACL, leases and persistence stay
 * in the Java worker; a provider only turns one bounded input into a parsed
 * package.
 */
public interface ParserProvider {

    ParserProviderKind provider();

    Set<DocumentFormat> supportedFormats();

    ParsedDocument parse(
            ParserInput input,
            ChunkingProfile chunkingProfile,
            ParseContext context
    ) throws IOException, ParseQuarantineException, ParserProcessingException;

    record ParseContext(int expectedSourceUnitCount) {
        public ParseContext {
            if (expectedSourceUnitCount < 1) {
                throw new IllegalArgumentException("expectedSourceUnitCount must be positive");
            }
        }
    }
}
