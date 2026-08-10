package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.document.DocumentRuntimePolicyService;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.PipelineJobLeaseService.ParserDecision;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.PdfPreflightInspector;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
final class ParserRoutingService {

    private final ParserRegistry registry;
    private final PdfPreflightInspector preflight;
    private final MineruProperties mineruProperties;
    private final PipelineJobLeaseService leases;
    private final DocumentRuntimePolicyService runtimePolicies;

    ParserRoutingService(
            ParserRegistry registry,
            PdfPreflightInspector preflight,
            MineruProperties mineruProperties,
            PipelineJobLeaseService leases,
            DocumentRuntimePolicyService runtimePolicies
    ) {
        this.registry = registry;
        this.preflight = preflight;
        this.mineruProperties = mineruProperties;
        this.leases = leases;
        this.runtimePolicies = runtimePolicies;
    }

    ParsedDocument parse(ParserInput input, ClaimedJob task, ChunkingProfile profile)
            throws IOException, ParseQuarantineException, ParserProcessingException {
        if (input.documentFormat() != DocumentFormat.PDF) {
            return parseNativeDocument(input, task, profile);
        }
        PdfPreflightInspector.Result inspection = preflight.inspect(
                input.path(),
                mineruProperties.maxPages()
        );
        ParserProvider.ParseContext context =
                new ParserProvider.ParseContext(inspection.pageCount());
        ParserEngine requested = task.requestedParser();
        if (requested == ParserEngine.PDFBOX) {
            runtimePolicies.requireProviderEnabled(input.documentFormat(), ParserProviderKind.PDFBOX);
            record(task, inspection, ParserEngine.PDFBOX, "ADMIN_OVERRIDE_PDFBOX", profile.parserVersion());
            return registry.require(input.documentFormat(), ParserProviderKind.PDFBOX)
                    .parse(input, profile, context);
        }
        if (requested == ParserEngine.MINERU || inspection.requiresMineru()) {
            String reason = requested == ParserEngine.MINERU
                    ? "ADMIN_OVERRIDE_MINERU"
                    : inspection.routeReason();
            return parseWithMineru(input, task, profile, inspection, context, reason);
        }

        record(task, inspection, ParserEngine.PDFBOX, inspection.routeReason(), profile.parserVersion());
        try {
            runtimePolicies.requireProviderEnabled(input.documentFormat(), ParserProviderKind.PDFBOX);
            return registry.require(input.documentFormat(), ParserProviderKind.PDFBOX)
                    .parse(input, profile, context);
        } catch (ParseQuarantineException exception) {
            if (exception.reason() != ParseQuarantineException.Reason.SCANNED_PDF
                    && exception.reason() != ParseQuarantineException.Reason.GIBBERISH_TEXT
                    && exception.reason() != ParseQuarantineException.Reason.LOW_QUALITY_TEXT) {
                throw exception;
            }
            return parseWithMineru(
                    input,
                    task,
                    profile,
                    inspection,
                    context,
                    "PDFBOX_QUALITY_FALLBACK"
            );
        }
    }

    private ParsedDocument parseNativeDocument(
            ParserInput input,
            ClaimedJob task,
            ChunkingProfile profile
    ) throws IOException, ParseQuarantineException,
            ParserProcessingException {
        if (task.requestedParser() != ParserEngine.AUTO) {
            throw new ParserProcessingException(
                    "PARSER_OVERRIDE_UNSUPPORTED",
                    "Parser override is only available for PDF revisions"
            );
        }
        ParserProviderKind provider = switch (input.documentFormat()) {
            case TXT -> ParserProviderKind.TEXT;
            case MARKDOWN -> ParserProviderKind.MARKDOWN;
            case HTML -> ParserProviderKind.HTML;
            case DOCX -> ParserProviderKind.DOCX_POI;
            case PPTX -> ParserProviderKind.PPTX_POI;
            case XLSX -> ParserProviderKind.XLSX_POI;
            case CSV -> ParserProviderKind.CSV_STREAM;
            default -> throw new ParserProcessingException(
                    "PARSER_FORMAT_UNSUPPORTED",
                    "No parser is enabled for " + input.documentFormat()
            );
        };
        runtimePolicies.requireProviderEnabled(input.documentFormat(), provider);
        ParsedDocument parsed = registry
                .require(input.documentFormat(), provider)
                .parse(
                        input,
                        profile,
                        new ParserProvider.ParseContext(1)
                );
        record(
                task,
                provider,
                switch (provider) {
                    case XLSX_POI -> "XLSX_EVENT_STREAM";
                    case CSV_STREAM -> "CSV_STREAM_AUTO_DETECT";
                    default -> "FORMAT_PROVIDER";
                },
                parsed.parserVersion(),
                parsed.sourceUnits().size(),
                false,
                false,
                false,
                parsed.contentBlocks().stream().anyMatch(block ->
                        block.type() == ParsedDocument.BlockType.TABLE),
                !parsed.images().isEmpty(),
                null,
                null
        );
        return parsed;
    }

    private ParsedDocument parseWithMineru(
            ParserInput input,
            ClaimedJob task,
            ChunkingProfile profile,
            PdfPreflightInspector.Result inspection,
            ParserProvider.ParseContext context,
            String reason
    ) throws IOException, ParseQuarantineException, ParserProcessingException {
        runtimePolicies.requireProviderEnabled(input.documentFormat(), ParserProviderKind.MINERU);
        if (!mineruProperties.enabled()) {
            record(task, inspection, ParserEngine.MINERU, "MINERU_REQUIRED", null);
            throw new ParserProcessingException(
                    "MINERU_REQUIRED",
                    "This PDF requires MinerU; start the optional mineru profile and retry"
            );
        }
        if (!mineruProperties.gpuAvailable()) {
            record(task, inspection, ParserEngine.MINERU, "GPU_PROFILE_CONFLICT", null);
            throw new ParserProcessingException(
                    "GPU_PROFILE_CONFLICT",
                    "MinerU cannot run while the active GPU profile is " + mineruProperties.gpuActiveProfile()
            );
        }
        record(task, inspection, ParserEngine.MINERU, reason, mineruProperties.version());
        return registry.require(input.documentFormat(), ParserProviderKind.MINERU)
                .parse(input, profile, context);
    }

    private void record(
            ClaimedJob task,
            PdfPreflightInspector.Result inspection,
            ParserEngine selected,
            String reason,
            String version
    ) throws ParserProcessingException {
        record(
                task,
                ParserProviderKind.valueOf(selected.name()),
                reason,
                version,
                inspection.pageCount(),
                inspection.scannedCandidate(),
                inspection.ocrRequired(),
                inspection.multicolumnCandidate(),
                inspection.tableCandidate(),
                inspection.imageCandidate(),
                selected == ParserEngine.MINERU
                        ? mineruProperties.modelRevision()
                        : null,
                selected == ParserEngine.MINERU
                        ? mineruProperties.modelManifestChecksum()
                        : null
        );
    }

    private void record(
            ClaimedJob task,
            ParserProviderKind provider,
            String reason,
            String version,
            int sourceUnitCount,
            boolean scannedCandidate,
            boolean ocrRequired,
            boolean multicolumnCandidate,
            boolean tableCandidate,
            boolean imageCandidate,
            String modelRevision,
            String modelManifestChecksum
    ) throws ParserProcessingException {
        boolean recorded = leases.recordParserDecision(
                task.id(),
                task.attempt(),
                new ParserDecision(
                        provider,
                        reason,
                        version,
                        sourceUnitCount,
                        scannedCandidate,
                        ocrRequired,
                        multicolumnCandidate,
                        tableCandidate,
                        imageCandidate,
                        modelRevision,
                        modelManifestChecksum
                )
        );
        if (!recorded) {
            throw new ParserProcessingException("PIPELINE_LEASE_LOST", "Parser decision lease was lost");
        }
    }
}
