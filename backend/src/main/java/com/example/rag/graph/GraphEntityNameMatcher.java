package com.example.rag.graph;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic query-side matching for graph aliases.
 *
 * <p>This matcher deliberately does not change graph entity identities or
 * stored aliases. Every alias supplied to it must still be backed by the
 * normal graph alias provenance checks.</p>
 */
final class GraphEntityNameMatcher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern LATIN_WORD = Pattern.compile("[a-z]+");
    private static final Set<String> ACRONYM_STOP_WORDS = Set.of(
            "a", "an", "and", "at", "by", "for", "from", "in",
            "of", "on", "or", "the", "to", "with"
    );
    private static final int MAX_QUERY_WORDS = 40;
    private static final int MAX_WORD_LENGTH = 64;

    private GraphEntityNameMatcher() {
    }

    static Query analyze(String value) {
        String legacy = legacyNormalize(value);
        List<String> words = words(value, MAX_QUERY_WORDS);
        Set<String> compactWindows = compactWindows(words);
        return new Query(
                legacy,
                " " + String.join(" ", words) + " ",
                List.copyOf(words),
                Set.copyOf(compactWindows)
        );
    }

    static Optional<NameMatch> match(Query query, String explicitAlias) {
        return match(query, explicitAlias, MatchBudget.unlimited());
    }

    static Optional<NameMatch> match(
            Query query,
            String explicitAlias,
            MatchBudget budget
    ) {
        budget.checkpoint();
        String legacyAlias = legacyNormalize(explicitAlias);
        if (legacyAlias.isEmpty()) {
            return Optional.empty();
        }
        if (legacyContains(query.legacy(), legacyAlias)) {
            return Optional.of(new NameMatch(MatchMode.EXACT_ALIAS, 0, 1.0));
        }

        List<String> aliasWords = words(explicitAlias, 8);
        if (aliasWords.isEmpty()) {
            return Optional.empty();
        }
        String wordAlias = String.join(" ", aliasWords);
        if (aliasWords.size() > 1
                && query.wordSequence().contains(" " + wordAlias + " ")) {
            return Optional.of(new NameMatch(
                    MatchMode.PUNCTUATION_NORMALIZED, 0, 1.0
            ));
        }

        String compactAlias = String.join("", aliasWords);
        if (compactAlias.length() >= 3
                && query.compactWindows().contains(compactAlias)) {
            return Optional.of(new NameMatch(
                    MatchMode.PUNCTUATION_NORMALIZED, 0, 1.0
            ));
        }

        String acronym = acronym(aliasWords);
        if (acronym != null && query.words().contains(acronym)) {
            return Optional.of(new NameMatch(MatchMode.ACRONYM, 0, 1.0));
        }

        if (aliasWords.size() != 1) {
            return Optional.empty();
        }
        String aliasWord = aliasWords.getFirst();
        if (!isFuzzyEligible(aliasWord)) {
            return Optional.empty();
        }
        NameMatch best = null;
        for (String queryWord : query.words()) {
            budget.checkpoint();
            if (!isFuzzyEligible(queryWord)
                    || aliasWord.charAt(0) != queryWord.charAt(0)) {
                continue;
            }
            int maximum = aliasWord.length() <= 8 ? 1 : 2;
            if (Math.abs(aliasWord.length() - queryWord.length()) > maximum) {
                continue;
            }
            int distance = boundedDamerauLevenshtein(
                    aliasWord, queryWord, budget
            );
            if (distance > maximum) {
                continue;
            }
            double similarity = 1.0 - (double) distance
                    / Math.max(aliasWord.length(), queryWord.length());
            if (aliasWord.length() >= 9 && similarity < 0.88) {
                continue;
            }
            NameMatch candidate = new NameMatch(
                    MatchMode.LATIN_FUZZY, distance, similarity
            );
            if (best == null || candidate.betterThan(best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean legacyContains(String query, String alias) {
        if (query.equals(alias)) {
            return true;
        }
        if (alias.matches("[a-z0-9_]+(?: [a-z0-9_]+)*")) {
            String boundedQuery = " " + query.replaceAll(
                    "[^\\p{L}\\p{N}_]+", " "
            ).strip() + " ";
            return boundedQuery.contains(" " + alias + " ");
        }
        return alias.length() >= 2 && query.contains(alias);
    }

    private static String acronym(List<String> words) {
        if (words.size() < 2 || words.size() > 8) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (String word : words) {
            if (!LATIN_WORD.matcher(word).matches()) {
                return null;
            }
            if (!ACRONYM_STOP_WORDS.contains(word)) {
                value.append(word.charAt(0));
            }
        }
        return value.length() >= 3 && value.length() <= 10
                ? value.toString()
                : null;
    }

    private static boolean isFuzzyEligible(String value) {
        return value.length() >= 5
                && value.length() <= MAX_WORD_LENGTH
                && LATIN_WORD.matcher(value).matches();
    }

    private static List<String> words(String value, int maximum) {
        String normalized = Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find() && result.size() < maximum) {
            String word = matcher.group();
            if (word.length() <= MAX_WORD_LENGTH) {
                result.add(word);
            }
        }
        return result;
    }

    private static Set<String> compactWindows(List<String> words) {
        Set<String> result = new LinkedHashSet<>();
        for (int start = 0; start < words.size(); start++) {
            StringBuilder compact = new StringBuilder();
            for (int end = start;
                 end < words.size() && end < start + 8;
                 end++) {
                compact.append(words.get(end));
                if (compact.length() >= 3 && compact.length() <= 64) {
                    result.add(compact.toString());
                }
                if (compact.length() > 64) {
                    break;
                }
            }
        }
        return result;
    }

    private static String legacyNormalize(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(Normalizer.normalize(
                value, Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
    }

    private static int boundedDamerauLevenshtein(
            String left,
            String right,
            MatchBudget budget
    ) {
        budget.reserveCells(
                (long) (left.length() + 1) * (right.length() + 1)
        );
        int[] previousPrevious = new int[right.length() + 1];
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            budget.checkpoint();
            current[0] = leftIndex;
            for (int rightIndex = 1;
                 rightIndex <= right.length();
                 rightIndex++) {
                int substitution = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1)
                        == right.charAt(rightIndex - 1) ? 0 : 1);
                int value = Math.min(
                        Math.min(
                                previous[rightIndex] + 1,
                                current[rightIndex - 1] + 1
                        ),
                        substitution
                );
                if (leftIndex > 1 && rightIndex > 1
                        && left.charAt(leftIndex - 1)
                        == right.charAt(rightIndex - 2)
                        && left.charAt(leftIndex - 2)
                        == right.charAt(rightIndex - 1)) {
                    value = Math.min(
                            value,
                            previousPrevious[rightIndex - 2] + 1
                    );
                }
                current[rightIndex] = value;
            }
            int[] recycled = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = recycled;
        }
        return previous[right.length()];
    }

    static final class MatchBudget {
        private static final MatchBudget UNLIMITED = new MatchBudget();

        private final boolean limited;
        private final long maximumCells;
        private final long maximumNanos;
        private final LongSupplier nanoTime;
        private final long startedNanos;
        private long usedCells;

        private MatchBudget() {
            limited = false;
            maximumCells = Long.MAX_VALUE;
            maximumNanos = Long.MAX_VALUE;
            nanoTime = () -> 0L;
            startedNanos = 0L;
        }

        private MatchBudget(
                long maximumCells,
                long maximumNanos,
                LongSupplier nanoTime
        ) {
            if (maximumCells < 0 || maximumNanos <= 0 || nanoTime == null) {
                throw new IllegalArgumentException("Invalid match budget");
            }
            limited = true;
            this.maximumCells = maximumCells;
            this.maximumNanos = maximumNanos;
            this.nanoTime = nanoTime;
            startedNanos = nanoTime.getAsLong();
        }

        static MatchBudget unlimited() {
            return UNLIMITED;
        }

        static MatchBudget limited(
                long maximumCells,
                long maximumNanos,
                LongSupplier nanoTime
        ) {
            return new MatchBudget(maximumCells, maximumNanos, nanoTime);
        }

        void checkpoint() {
            if (limited
                    && nanoTime.getAsLong() - startedNanos >= maximumNanos) {
                throw new MatchLimitExceededException();
            }
        }

        private void reserveCells(long cells) {
            if (!limited) {
                return;
            }
            checkpoint();
            if (cells > maximumCells - usedCells) {
                throw new MatchLimitExceededException();
            }
            usedCells += cells;
        }
    }

    static final class MatchLimitExceededException extends RuntimeException {
        private MatchLimitExceededException() {
            super("Graph entity-name match limit exceeded", null, false, false);
        }
    }

    enum MatchMode {
        EXACT_ALIAS,
        PUNCTUATION_NORMALIZED,
        ACRONYM,
        LATIN_FUZZY
    }

    record Query(
            String legacy,
            String wordSequence,
            List<String> words,
            Set<String> compactWindows
    ) {
    }

    record NameMatch(MatchMode mode, int editDistance, double similarity) {

        private boolean betterThan(NameMatch other) {
            int byMode = Integer.compare(mode.ordinal(), other.mode.ordinal());
            if (byMode != 0) {
                return byMode < 0;
            }
            int byDistance = Integer.compare(
                    editDistance, other.editDistance
            );
            return byDistance < 0 || (byDistance == 0
                    && similarity > other.similarity);
        }
    }
}
