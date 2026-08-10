package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.CreateFeedbackRequest;
import com.example.rag.evaluation.EvaluationContracts.FeedbackView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatFeedbackController {

    private final EvaluationGovernanceService governance;

    ChatFeedbackController(EvaluationGovernanceService governance) {
        this.governance = governance;
    }

    @PostMapping("/runs/{runId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    FeedbackView create(
            @PathVariable UUID runId,
            @Valid @RequestBody CreateFeedbackRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return governance.createFeedback(
                runId, request.rating(), request.comment(),
                request.consentToShare(), user
        );
    }
}
