package com.example.rag.chat;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatRunRecovery implements ApplicationRunner {

    private final ChatPersistenceRepository repository;

    public ChatRunRecovery(ChatPersistenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        repository.recoverInterruptedRuns();
    }
}
