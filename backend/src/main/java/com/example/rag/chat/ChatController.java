package com.example.rag.chat;

import com.example.rag.chat.ChatApiContracts.CitationDetail;
import com.example.rag.chat.ChatApiContracts.CreateSessionRequest;
import com.example.rag.chat.ChatApiContracts.RenameSessionRequest;
import com.example.rag.chat.ChatApiContracts.SessionDetailResponse;
import com.example.rag.chat.ChatApiContracts.SessionListResponse;
import com.example.rag.chat.ChatApiContracts.SessionSummary;
import com.example.rag.chat.ChatApiContracts.StartRunRequest;
import com.example.rag.chat.ChatApiContracts.MemorySuggestionStatusResponse;
import com.example.rag.chat.ChatApiContracts.ContextStatusResponse;
import com.example.rag.chat.ChatService.OpenChatRun;
import com.example.rag.memory.MemoryPackService.RunMemoryUsageView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatController {

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/sessions")
    SessionListResponse listSessions(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.listSessions(user);
    }

    @PostMapping("/sessions")
    SessionSummary createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.createSession(request.title(), user);
    }

    @GetMapping("/sessions/{sessionId}")
    SessionDetailResponse session(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.session(sessionId, user);
    }

    @PatchMapping("/sessions/{sessionId}")
    SessionSummary renameSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameSessionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.renameSession(sessionId, request.title(), user);
    }

    @GetMapping("/sessions/{sessionId}/memory-suggestions")
    MemorySuggestionStatusResponse memorySuggestions(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.memorySuggestions(sessionId, user);
    }

    @PostMapping("/sessions/{sessionId}/context/prepare")
    ContextStatusResponse prepareContext(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.prepareContext(sessionId, user);
    }

    @GetMapping("/sessions/{sessionId}/context")
    ContextStatusResponse context(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.context(sessionId, user);
    }

    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> deleteSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        chat.deleteSession(sessionId, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(
            value = "/sessions/{sessionId}/runs",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    ResponseEntity<SseEmitter> startRun(
            @PathVariable UUID sessionId,
            @Valid @RequestBody StartRunRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return stream(chat.start(
                sessionId,
                request.question(),
                request.graphModeRequested(),
                request.answerStrategyRequested(),
                user
        ));
    }

    @PostMapping(
            value = "/runs/{runId}/retry",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    ResponseEntity<SseEmitter> retryRun(
            @PathVariable UUID runId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return stream(chat.retry(runId, user));
    }

    @PostMapping("/runs/{runId}/cancel")
    ResponseEntity<Void> cancelRun(
            @PathVariable UUID runId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        chat.cancel(runId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/citations/{citationId}")
    CitationDetail citation(
            @PathVariable UUID citationId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.citation(citationId, user);
    }

    @GetMapping("/runs/{runId}/memories")
    List<RunMemoryUsageView> memories(
            @PathVariable UUID runId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chat.memories(runId, user);
    }

    private static ResponseEntity<SseEmitter> stream(OpenChatRun run) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Chat-Run-Id", run.runId().toString())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(run.emitter());
    }
}
