package com.example.rag.common;

import java.util.List;
import java.util.regex.Pattern;

public final class SensitiveContentDetector {

    private static final List<Pattern> CREDENTIAL_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----"),
            Pattern.compile(
                    "(?i)\\b(?:password|passwd|pwd|cookie|token|api[ _-]?key"
                            + "|secret|authorization)\\b\\s*[:=]\\s*\\S{4,}"
            ),
            Pattern.compile("(?:密码|令牌|密钥)\\s*[:：=]\\s*\\S{4,}"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                            + "\\.[A-Za-z0-9_-]{8,}\\b"
            )
    );

    private SensitiveContentDetector() {
    }

    public static boolean containsCredentials(String value) {
        return value != null && CREDENTIAL_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(value).find());
    }
}
