package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public final class ParserRegistry {

    private final Map<DocumentFormat, Map<ParserProviderKind, ParserProvider>> providers;

    public ParserRegistry(Collection<ParserProvider> providers) {
        Map<DocumentFormat, Map<ParserProviderKind, ParserProvider>> registered =
                new EnumMap<>(DocumentFormat.class);
        for (ParserProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            for (DocumentFormat format : provider.supportedFormats()) {
                ParserProvider previous = registered
                        .computeIfAbsent(format, ignored -> new EnumMap<>(ParserProviderKind.class))
                        .putIfAbsent(provider.provider(), provider);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate parser provider " + format + "/" + provider.provider()
                    );
                }
            }
        }
        Map<DocumentFormat, Map<ParserProviderKind, ParserProvider>> immutable = new EnumMap<>(
                DocumentFormat.class
        );
        registered.forEach((format, byProvider) ->
                immutable.put(format, Map.copyOf(new LinkedHashMap<>(byProvider))));
        this.providers = Map.copyOf(immutable);
    }

    public ParserProvider require(DocumentFormat format, ParserProviderKind provider) {
        ParserProvider resolved = providers
                .getOrDefault(format, Map.of())
                .get(provider);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Parser provider " + provider + " does not support " + format
            );
        }
        return resolved;
    }

    public List<ParserProviderKind> providersFor(DocumentFormat format) {
        List<ParserProviderKind> result = new ArrayList<>(
                providers.getOrDefault(format, Map.of()).keySet()
        );
        result.sort(Comparator.comparing(Enum::name));
        return List.copyOf(result);
    }
}
