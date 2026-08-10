package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.PipelineStage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
@RequestMapping("/api/v1/admin/pipeline-revisions")
public class AdminPipelineRevisionController {

    private final PipelineRevisionService revisions;

    public AdminPipelineRevisionController(PipelineRevisionService revisions) {
        this.revisions = revisions;
    }

    @GetMapping
    PipelineRevisionContracts.RevisionPage list(
            @RequestParam(defaultValue = "false") boolean attention,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) PipelineStage stage,
            @RequestParam(required = false) DocumentFormat format,
            @RequestParam(required = false) ParserProviderKind parser,
            @RequestParam(required = false) @Size(max = 200) String documentQuery,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return revisions.list(
                attention, status, stage, format, parser,
                documentQuery, from, to, page, size
        );
    }
}
