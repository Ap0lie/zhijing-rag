package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.PdfDocumentParser;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
final class MineruParserProvider implements ParserProvider {

    private final MineruProvider mineru;
    private final PdfDocumentParser assembler;

    MineruParserProvider(MineruProvider mineru, PdfDocumentParser assembler) {
        this.mineru = mineru;
        this.assembler = assembler;
    }

    @Override
    public ParserProviderKind provider() {
        return ParserProviderKind.MINERU;
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
    ) throws ParseQuarantineException, ParserProcessingException {
        MineruProvider.Result result = mineru.parse(
                input.path(),
                input.inputHash(),
                input.revisionId(),
                context.expectedSourceUnitCount()
        );
        return assembler.parseStructuredMarkdown(
                result.markdown(),
                result.structure(),
                input.documentId(),
                input.revisionId(),
                context.expectedSourceUnitCount(),
                chunkingProfile,
                "mineru-" + result.version()
        );
    }
}
