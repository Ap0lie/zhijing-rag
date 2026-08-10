package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.CreateDrillRequest;
import com.example.rag.evaluation.EvaluationContracts.DrillActionRequest;
import com.example.rag.evaluation.EvaluationContracts.DrillEventView;
import com.example.rag.evaluation.EvaluationContracts.DrillView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/evaluations/drills")
@ConditionalOnProperty(
        prefix = "rag.evaluation",
        name = "drills-enabled",
        havingValue = "true"
)
class AdminEvaluationDrillController {

    private final EvaluationDrillService drills;

    AdminEvaluationDrillController(EvaluationDrillService drills) {
        this.drills = drills;
    }

    @GetMapping
    List<DrillView> drills() {
        return drills.drills();
    }

    @GetMapping("/{drillId}")
    DrillView drill(@PathVariable UUID drillId) {
        return drills.drill(drillId);
    }

    @GetMapping("/{drillId}/events")
    List<DrillEventView> events(@PathVariable UUID drillId) {
        return drills.events(drillId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    DrillView create(
            @Valid @RequestBody CreateDrillRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return drills.create(
                request.drillType(),
                request.executionMode(),
                request.idempotencyKey(),
                request.reason(),
                user
        );
    }

    @PostMapping("/{drillId}/cancel")
    DrillView cancel(
            @PathVariable UUID drillId,
            @Valid @RequestBody DrillActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        requireConfirmation(
                request.confirmation(), "CANCEL_EVALUATION_DRILL"
        );
        return drills.cancel(drillId, request.reason(), user);
    }

    @PostMapping("/{drillId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DrillView retry(
            @PathVariable UUID drillId,
            @Valid @RequestBody DrillActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        requireConfirmation(
                request.confirmation(), "RETRY_EVALUATION_DRILL"
        );
        return drills.retry(drillId, request.reason(), user);
    }

    private static void requireConfirmation(
            String actual,
            String expected
    ) {
        if (!expected.equals(actual)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CONFIRMATION",
                    "确认字段不正确"
            );
        }
    }
}
