package com.hyperbrain.core.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompletionState (ADR-039 isClosed vs isAchieved)")
class CompletionStateTest {

    @ParameterizedTest(name = "{0} → closed={1}, achieved={2}")
    @CsvSource(nullValues = "NULL", value = {
        "DONE, true, true",
        "FAILED, true, false",
        "TODO, false, false",
        "IN_PROGRESS, false, false",
        "PLANNED, false, false",
        "WAITING, false, false",
        "NULL, false, false"})
    @DisplayName("isClosed covers DONE and FAILED; isAchieved is DONE only")
    void predicates(String status, boolean closed, boolean achieved) {
        assertThat(CompletionState.isClosed(status)).isEqualTo(closed);
        assertThat(CompletionState.isAchieved(status)).isEqualTo(achieved);
        assertThat(CompletionState.from(status).isClosed()).isEqualTo(closed);
        assertThat(CompletionState.from(status).isAchieved()).isEqualTo(achieved);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "NULL", value = {
        "DONE, ACHIEVED",
        "FAILED, FAILED",
        "TODO, OPEN",
        "NULL, OPEN"})
    @DisplayName("classifies a raw status into its completion state")
    void classifies(String status, CompletionState expected) {
        assertThat(CompletionState.from(status)).isEqualTo(expected);
    }
}
