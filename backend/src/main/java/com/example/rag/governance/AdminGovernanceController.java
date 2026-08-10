package com.example.rag.governance;

import com.example.rag.governance.GovernanceContracts.AuditEventPage;
import com.example.rag.governance.GovernanceContracts.OperationImpact;
import com.example.rag.governance.GovernanceContracts.OperationImpactRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminGovernanceController {

    private final AdminAuditService audit;
    private final OperationImpactService impacts;

    public AdminGovernanceController(AdminAuditService audit, OperationImpactService impacts) {
        this.audit = audit;
        this.impacts = impacts;
    }

    @GetMapping("/audit-events")
    AuditEventPage auditEvents(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size
    ) {
        return audit.page(module, action, actor, object, from, to, cursor, size);
    }

    @PostMapping("/operation-impact/preflight")
    OperationImpact preflight(@Valid @RequestBody OperationImpactRequest request) {
        return impacts.preflight(request);
    }
}
