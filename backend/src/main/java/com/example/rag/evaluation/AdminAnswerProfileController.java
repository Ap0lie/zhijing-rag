package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.AnswerProfilePublicationRequest;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfilePublicationView;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfileRollbackRequest;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfileView;
import com.example.rag.evaluation.EvaluationContracts.CreateAnswerProfileRequest;
import com.example.rag.evaluation.EvaluationContracts.RuntimeAnswerProfileView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/answer-profiles")
class AdminAnswerProfileController {

    private final AnswerProfileService profiles;

    AdminAnswerProfileController(AnswerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    List<AnswerProfileView> profiles() {
        return profiles.profiles();
    }

    @GetMapping("/runtime")
    RuntimeAnswerProfileView runtime() {
        return profiles.runtime();
    }

    @GetMapping("/events")
    List<AnswerProfilePublicationView> events() {
        return profiles.events();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AnswerProfileView create(
            @Valid @RequestBody CreateAnswerProfileRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.create(request, user);
    }

    @PostMapping("/publications")
    AnswerProfilePublicationView publish(
            @Valid @RequestBody AnswerProfilePublicationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.publish(
                request.profileVersion(), request.reason(), user
        );
    }

    @PostMapping("/rollbacks")
    AnswerProfilePublicationView rollback(
            @Valid @RequestBody AnswerProfileRollbackRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.rollback(
                request.profileVersion(), request.reason(), user
        );
    }
}
