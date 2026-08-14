package com.example.rag.graph;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphEntityNameMatcherTests {

    @Test
    void exactMatchingKeepsExistingCaseAndNfkcBehavior() {
        assertMode(
                "How does ＭＩＣＲＯＳＯＦＴ work?",
                "Microsoft",
                GraphEntityNameMatcher.MatchMode.EXACT_ALIAS
        );
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("training"), "AI"
        )).isEmpty();
    }

    @Test
    void punctuationAndWhitespaceAreNormalizedWithoutCollapsingSymbols() {
        assertMode(
                "Is USA a member?",
                "U.S.A.",
                GraphEntityNameMatcher.MatchMode.PUNCTUATION_NORMALIZED
        );
        assertMode(
                "What did OConnor publish?",
                "O’Connor",
                GraphEntityNameMatcher.MatchMode.PUNCTUATION_NORMALIZED
        );
        assertMode(
                "New York",
                "New-York",
                GraphEntityNameMatcher.MatchMode.PUNCTUATION_NORMALIZED
        );
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("C#"), "C++"
        )).isEmpty();
    }

    @Test
    void explicitFullAliasCanMatchItsBoundedLatinAcronym() {
        assertMode(
                "What does NATO coordinate?",
                "North Atlantic Treaty Organization",
                GraphEntityNameMatcher.MatchMode.ACRONYM
        );
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("US policy"),
                "United States"
        )).isEmpty();
    }

    @Test
    void latinFuzzyMatchingIsBoundedAndDeterministic() {
        var transposition = GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("Micorsoft products"),
                "Microsoft"
        );
        assertThat(transposition).isPresent();
        assertThat(transposition.orElseThrow().mode())
                .isEqualTo(GraphEntityNameMatcher.MatchMode.LATIN_FUZZY);
        assertThat(transposition.orElseThrow().editDistance()).isOne();

        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("Iraq"), "Iran"
        )).isEmpty();
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("Model 1502"), "Model 1501"
        )).isEmpty();
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("微软"), "微阮"
        )).isEmpty();
    }

    @Test
    void cumulativeFuzzyCellBudgetFailsClosedBeforeAllocation() {
        String word = "a" + "b".repeat(63);
        String alias = "a" + "z".repeat(63);
        long oneMatrix = (long) (word.length() + 1)
                * (alias.length() + 1);
        GraphEntityNameMatcher.MatchBudget budget =
                GraphEntityNameMatcher.MatchBudget.limited(
                        oneMatrix - 1,
                        Long.MAX_VALUE,
                        () -> 0L
                );

        assertThatThrownBy(() -> GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze(word), alias, budget
        )).isInstanceOf(
                GraphEntityNameMatcher.MatchLimitExceededException.class
        );
    }

    @Test
    void cooperativeDeadlineUsesInjectedMonotonicClock() {
        AtomicLong now = new AtomicLong();
        GraphEntityNameMatcher.MatchBudget budget =
                GraphEntityNameMatcher.MatchBudget.limited(
                        Long.MAX_VALUE,
                        10,
                        now::get
                );
        now.set(10);

        assertThatThrownBy(() -> GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze("Microsoft"),
                "Microsft",
                budget
        )).isInstanceOf(
                GraphEntityNameMatcher.MatchLimitExceededException.class
        );
    }

    private static void assertMode(
            String query,
            String alias,
            GraphEntityNameMatcher.MatchMode mode
    ) {
        assertThat(GraphEntityNameMatcher.match(
                GraphEntityNameMatcher.analyze(query), alias
        )).isPresent().get().extracting(
                GraphEntityNameMatcher.NameMatch::mode
        ).isEqualTo(mode);
    }
}
