package com.example.rag.chat;

import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.common.ApiException;
import com.example.rag.memory.MemoryPackService;
import com.example.rag.memory.MemorySuggestionService;
import com.example.rag.search.ChunkContextService;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTests {

    @Test
    void incompatibleActiveProfileIsNotFrozenIntoNewRun() {
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        QueryIntelligenceProfileService profiles =
                mock(QueryIntelligenceProfileService.class);
        ProfileView activeProfile = mock(ProfileView.class);
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        when(user.id()).thenReturn(userId);
        when(activeProfile.enabled()).thenReturn(true);
        when(profiles.active()).thenReturn(activeProfile);
        when(profiles.matchesRuntime(activeProfile)).thenReturn(false);
        when(repository.startRun(
                eq(userId), eq(sessionId),
                org.mockito.ArgumentMatchers.any(StartRunCommand.class),
                isNull()
        )).thenReturn(Optional.empty());

        ChatService service = new ChatService(
                repository,
                mock(ChatWorkflow.class),
                mock(ChunkContextService.class),
                mock(ChatUserGuard.class),
                new ChatProperties(),
                profiles,
                mock(MemoryPackService.class),
                mock(MemorySuggestionService.class),
                mock(AnswerSourceService.class),
                new ObjectMapper(),
                mock(ExecutorService.class)
        );

        assertThatThrownBy(() -> service.start(
                sessionId, "继续说明", GraphMode.HYBRID, user
        )).isInstanceOf(ApiException.class);

        var command = org.mockito.ArgumentCaptor.forClass(
                StartRunCommand.class
        );
        verify(repository).startRun(
                eq(userId), eq(sessionId), command.capture(), isNull()
        );
        assertThat(command.getValue().queryIntelligenceProfileVersion())
                .isNull();
    }
}
