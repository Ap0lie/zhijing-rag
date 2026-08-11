package com.example.rag.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveContentDetectorTests {

    @Test
    void detectsCredentialsThatMustNotLeaveTheLocalRuntime() {
        assertThat(SensitiveContentDetector.containsCredentials(
                "Authorization: Bearer secret-token-value"
        )).isTrue();
        assertThat(SensitiveContentDetector.containsCredentials(
                "api_key = sk-abcdefghijklmnopqrstuvwxyz123456"
        )).isTrue();
        assertThat(SensitiveContentDetector.containsCredentials(
                "-----BEGIN PRIVATE KEY-----"
        )).isTrue();
        assertThat(SensitiveContentDetector.containsCredentials(
                "Cookie: session=abc123"
        )).isTrue();
    }

    @Test
    void ordinaryConversationIsNotMisclassified() {
        assertThat(SensitiveContentDetector.containsCredentials(
                "请比较本地模型与远程模型的上下文预算。"
        )).isFalse();
    }
}
