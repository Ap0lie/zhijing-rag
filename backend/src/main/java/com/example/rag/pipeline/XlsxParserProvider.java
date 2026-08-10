package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.SpreadsheetDocumentParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
final class XlsxParserProvider implements ParserProvider {

    private final SpreadsheetDocumentParser parser;

    XlsxParserProvider(SpreadsheetDocumentParser parser) {
        this.parser = parser;
    }

    @Override
    public ParserProviderKind provider() {
        return ParserProviderKind.XLSX_POI;
    }

    @Override
    public Set<DocumentFormat> supportedFormats() {
        return Set.of(DocumentFormat.XLSX);
    }

    @Override
    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile chunkingProfile,
            ParseContext context
    ) throws IOException, ParseQuarantineException,
            ParserProcessingException {
        return parser.parse(input, chunkingProfile, provider());
    }
}
