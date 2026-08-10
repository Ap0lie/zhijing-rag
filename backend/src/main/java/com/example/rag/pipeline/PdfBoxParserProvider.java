package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.PdfDocumentParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
final class PdfBoxParserProvider implements ParserProvider {

    private final PdfDocumentParser parser;

    PdfBoxParserProvider(PdfDocumentParser parser) {
        this.parser = parser;
    }

    @Override
    public ParserProviderKind provider() {
        return ParserProviderKind.PDFBOX;
    }

    @Override
    public Set<DocumentFormat> supportedFormats() {
        return Set.of(DocumentFormat.PDF);
    }

    @Override
    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile chunkingProfile,
            ParseContext context
    ) throws IOException, ParseQuarantineException {
        return parser.parse(
                input.path(),
                input.documentId(),
                input.revisionId(),
                chunkingProfile,
                input.inputHash()
        );
    }
}
