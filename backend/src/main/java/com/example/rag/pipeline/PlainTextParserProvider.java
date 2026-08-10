package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.TextDocumentParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
final class PlainTextParserProvider implements ParserProvider {

    private final TextDocumentParser parser;

    PlainTextParserProvider(TextDocumentParser parser) {
        this.parser = parser;
    }

    @Override
    public ParserProviderKind provider() {
        return ParserProviderKind.TEXT;
    }

    @Override
    public Set<DocumentFormat> supportedFormats() {
        return Set.of(DocumentFormat.TXT);
    }

    @Override
    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile chunkingProfile,
            ParseContext context
    ) throws IOException, ParseQuarantineException, ParserProcessingException {
        return parser.parse(input, chunkingProfile, provider());
    }
}
