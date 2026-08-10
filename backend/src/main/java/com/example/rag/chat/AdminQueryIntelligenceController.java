package com.example.rag.chat;

import com.example.rag.chat.QueryIntelligenceContracts.CreateProfileRequest;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.chat.QueryIntelligenceContracts.PublicationEventView;
import com.example.rag.chat.QueryIntelligenceContracts.PublicationRequest;
import com.example.rag.chat.QueryIntelligenceContracts.RollbackRequest;
import com.example.rag.chat.QueryIntelligenceContracts.RuntimeView;
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
@RequestMapping("/api/v1/admin/query-intelligence")
class AdminQueryIntelligenceController {

    private final QueryIntelligenceProfileService profiles;

    AdminQueryIntelligenceController(
            QueryIntelligenceProfileService profiles
    ) {
        this.profiles = profiles;
    }

    @GetMapping("/profiles")
    List<ProfileView> profiles() {
        return profiles.profiles();
    }

    @GetMapping("/runtime")
    RuntimeView runtime() {
        return profiles.runtime();
    }

    @GetMapping("/events")
    List<PublicationEventView> events() {
        return profiles.events();
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    ProfileView create(
            @Valid @RequestBody CreateProfileRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.create(request, user);
    }

    @PostMapping("/publications")
    PublicationEventView publish(
            @Valid @RequestBody PublicationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.publish(
                request.profileVersion(),
                request.intentRunId(),
                request.multiTurnRunId(),
                request.reason(),
                user
        );
    }

    @PostMapping("/rollbacks")
    PublicationEventView rollback(
            @Valid @RequestBody RollbackRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return profiles.rollback(
                request.profileVersion(), request.reason(), user
        );
    }
}
