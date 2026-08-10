package com.example.rag.memory;

import com.example.rag.memory.MemoryContracts.AdminMemorySummaryView;
import com.example.rag.memory.MemoryContracts.CreateMemoryRequest;
import com.example.rag.memory.MemoryContracts.ForgetMemoryRequest;
import com.example.rag.memory.MemoryContracts.MemoryActionRequest;
import com.example.rag.memory.MemoryContracts.MemoryEventView;
import com.example.rag.memory.MemoryContracts.MemoryItemView;
import com.example.rag.memory.MemoryContracts.MemorySettingsView;
import com.example.rag.memory.MemoryContracts.MemorySourceView;
import com.example.rag.memory.MemoryContracts.ReplaceMemoryRequest;
import com.example.rag.memory.MemoryContracts.UpdateMemorySettingsRequest;
import com.example.rag.memory.MemoryContracts.UserProfileView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/memories")
class MemoryController {

    private final MemoryService memories;

    MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @GetMapping("/settings")
    MemorySettingsView settings(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.settings(user);
    }

    @PutMapping("/settings")
    MemorySettingsView updateSettings(
            @Valid @RequestBody UpdateMemorySettingsRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.updateSettings(user, request);
    }

    @GetMapping
    List<MemoryItemView> list(
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "ALL") String status,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.list(user, type, status);
    }

    @GetMapping("/profile")
    UserProfileView profile(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.profile(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MemoryItemView create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateMemoryRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.create(user, idempotencyKey, request);
    }

    @GetMapping("/{memoryId}")
    MemoryItemView detail(
            @PathVariable UUID memoryId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.detail(user, memoryId);
    }

    @GetMapping("/{memoryId}/sources")
    List<MemorySourceView> sources(
            @PathVariable UUID memoryId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.sources(user, memoryId);
    }

    @GetMapping("/{memoryId}/events")
    List<MemoryEventView> events(
            @PathVariable UUID memoryId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.events(user, memoryId);
    }

    @PostMapping("/{memoryId}/confirm")
    MemoryItemView confirm(
            @PathVariable UUID memoryId,
            @Valid @RequestBody MemoryActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.confirm(user, memoryId, request.reason());
    }

    @PostMapping("/{memoryId}/reject")
    MemoryItemView reject(
            @PathVariable UUID memoryId,
            @Valid @RequestBody MemoryActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.reject(user, memoryId, request.reason());
    }

    @PostMapping("/{memoryId}/replace")
    @ResponseStatus(HttpStatus.CREATED)
    MemoryItemView replace(
            @PathVariable UUID memoryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReplaceMemoryRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.replace(user, memoryId, idempotencyKey, request);
    }

    @PostMapping("/{memoryId}/revoke")
    MemoryItemView revoke(
            @PathVariable UUID memoryId,
            @Valid @RequestBody MemoryActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.revoke(user, memoryId, request.reason());
    }

    @PostMapping("/{memoryId}/forget")
    MemoryItemView forget(
            @PathVariable UUID memoryId,
            @Valid @RequestBody ForgetMemoryRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return memories.forget(user, memoryId, request.reason());
    }
}

@RestController
@RequestMapping("/api/v1/admin/memories")
class AdminMemoryController {

    private final MemoryService memories;

    AdminMemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @GetMapping("/summary")
    AdminMemorySummaryView summary() {
        return memories.adminSummary();
    }
}
