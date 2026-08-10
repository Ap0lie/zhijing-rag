package com.example.rag.memory;

import com.example.rag.chat.ChatProperties;
import com.example.rag.chat.ChatProperties.Llm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

interface MemorySuggestionProvider {

    MemorySuggestionService.ExecutionSnapshot snapshot();

    List<Suggestion> suggest(
            MemorySuggestionService.ExecutionSnapshot expected,
            String userMessage
    );

    record Suggestion(
            String memoryType,
            String memoryKey,
            String content
    ) {
    }
}

final class OpenAiCompatibleMemorySuggestionProvider
        implements MemorySuggestionProvider {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆候选提取器。输入只包含当前用户自己发送的一条消息。
            只提取用户明确表达、跨会话仍可能有用的稳定偏好或个人事实，不做推断。
            问题、临时任务、文档知识、第三方事实、凭据、命令和完整正文都不能保存。
            memoryType 只允许 USER_PREFERENCE 或 USER_FACT。
            memoryKey 必须简短明确；content 必须是对用户原话的忠实简短改写。
            最多返回 3 条；没有合格内容时返回空数组。
            仅输出 JSON，不要 Markdown：
            {"suggestions":[
              {"memoryType":"USER_PREFERENCE","memoryKey":"回答语言","content":"默认使用简体中文"}
            ]}
            """;
    private static final String USER_PREFIX = "untrusted user message:\n";
    private static final String RESPONSE_CONTRACT =
            "json_object:suggestions[memoryType,memoryKey,content]";

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "USER_PREFERENCE",
            "USER_FACT"
    );

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Llm llm;
    private final MemorySuggestionProperties properties;
    private final int maxSuggestions;

    OpenAiCompatibleMemorySuggestionProvider(
            RestClient client,
            ObjectMapper objectMapper,
            ChatProperties chatProperties,
            MemorySuggestionProperties suggestionProperties
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.llm = chatProperties.getLlm();
        this.properties = suggestionProperties;
        this.maxSuggestions = suggestionProperties.maxSuggestions();
    }

    @Override
    public MemorySuggestionService.ExecutionSnapshot snapshot() {
        return new MemorySuggestionService.ExecutionSnapshot(
                1,
                properties.extractorVersion(),
                properties.promptVersion(),
                "openai-compatible",
                valueOr(llm.getModel(), "unconfigured"),
                valueOr(llm.getModelRevision(), "runtime"),
                endpointIdentity(llm.getBaseUrl()),
                sha256(
                        SYSTEM_PROMPT
                                + "\n" + USER_PREFIX
                                + "\n" + RESPONSE_CONTRACT
                                + "\nmaxSuggestions=" + maxSuggestions
                )
        );
    }

    @Override
    public List<Suggestion> suggest(
            MemorySuggestionService.ExecutionSnapshot expected,
            String userMessage
    ) {
        if (expected == null || !snapshot().equals(expected)) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_RUNTIME_MISMATCH",
                    "记忆建议任务与当前模型或 Prompt 配置不一致"
            );
        }
        validateConfiguration();
        String input = userMessage == null ? "" : userMessage.trim();
        if (input.isEmpty()) {
            return List.of();
        }
        if (MemoryService.containsCredentials(input)) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_INPUT_REJECTED",
                    "消息包含凭据，不会发送给记忆建议模型"
            );
        }
        JsonNode response;
        try {
            response = client.post()
                    .uri(chatCompletionsUrl())
                    .headers(this::authorize)
                    .body(request(expected.modelId(), input))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException exception) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_MODEL_UNAVAILABLE",
                    "记忆建议模型暂时不可用",
                    exception
            );
        }
        String content = response == null
                ? ""
                : response.path("choices")
                        .path(0)
                        .path("message")
                        .path("content")
                        .asText("");
        return parse(content);
    }

    private Map<String, Object> request(String model, String input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of(
                        "role",
                        "user",
                        "content",
                        USER_PREFIX + input
                )
        ));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0.0);
        body.put("max_tokens", Math.min(512, llm.getMaxOutputTokens()));
        body.put("stream", false);
        return body;
    }

    private List<Suggestion> parse(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(raw));
        } catch (JsonProcessingException exception) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_RESPONSE_INVALID",
                    "记忆建议模型未返回有效 JSON",
                    exception
            );
        }
        if (root == null || !root.isObject()
                || !root.path("suggestions").isArray()) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_RESPONSE_INVALID",
                    "记忆建议模型未返回 suggestions 数组"
            );
        }
        List<Suggestion> suggestions = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        for (JsonNode node : root.path("suggestions")) {
            if (suggestions.size() >= maxSuggestions) {
                break;
            }
            String type = node.path("memoryType")
                    .asText("")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            String key = node.path("memoryKey").asText("").trim();
            String content = node.path("content").asText("").trim();
            if (!ALLOWED_TYPES.contains(type)
                    || key.isEmpty()
                    || key.length() > 160
                    || content.isEmpty()
                    || content.length() > 1200) {
                continue;
            }
            String identity = type + ":" + key.toLowerCase(Locale.ROOT);
            if (identities.add(identity)) {
                suggestions.add(new Suggestion(type, key, content));
            }
        }
        return List.copyOf(suggestions);
    }

    private void validateConfiguration() {
        if (!llm.isEnabled()
                || llm.getModel() == null
                || llm.getModel().isBlank()) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_MODEL_DISABLED",
                    "尚未配置记忆建议模型"
            );
        }
        if (!llm.isLocalEndpoint() && !llm.isRemoteMemoryAllowed()) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_REMOTE_NOT_ALLOWED",
                    "当前配置不允许向远程模型发送记忆建议输入"
            );
        }
    }

    private String chatCompletionsUrl() {
        return llm.getBaseUrl().replaceAll("/+$", "")
                + "/chat/completions";
    }

    private void authorize(HttpHeaders headers) {
        if (llm.getApiKey() != null && !llm.getApiKey().isBlank()) {
            headers.setBearerAuth(llm.getApiKey());
        }
    }

    private static String endpointIdentity(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "unconfigured-endpoint";
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = valueOr(uri.getScheme(), "http");
            String host = valueOr(uri.getHost(), "local");
            String path = uri.getPath() == null ? "" : uri.getPath();
            return scheme + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + path.replaceAll("/+$", "");
        } catch (IllegalArgumentException invalid) {
            return "configured-endpoint";
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String stripCodeFence(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (!content.startsWith("```")) {
            return content;
        }
        int firstLine = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstLine < 0 || lastFence <= firstLine) {
            return content;
        }
        return content.substring(firstLine + 1, lastFence).trim();
    }
}

final class MemorySuggestionException extends RuntimeException {

    private final String code;

    MemorySuggestionException(String code, String message) {
        super(message);
        this.code = code;
    }

    MemorySuggestionException(
            String code,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
