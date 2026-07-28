package com.hyperbrain.cognitive.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ThemeQualityGuard (core#50 — LLM theme quality)")
class ThemeQualityGuardTest {

    private final ThemeQualityGuard guard = new ThemeQualityGuard();

    private static final Set<String> TITLES = Set.of("Escribir informe de ventas", "Revisar informe anual");

    @Test
    @DisplayName("accepts a grounded, natural theme whose word appears in the members' titles")
    void accepts_grounded_theme() {
        assertThat(guard.accepts("Informe", TITLES)).isTrue();
    }

    @Test
    @DisplayName("accepts a grounded theme even across an accent difference")
    void accepts_theme_ignoring_accents() {
        assertThat(guard.accepts("informacion", Set.of("Revisar información fiscal"))).isTrue();
    }

    @Test
    @DisplayName("rejects a blank or null theme")
    void rejects_blank_theme() {
        assertThat(guard.accepts(null, TITLES)).isFalse();
        assertThat(guard.accepts("   ", TITLES)).isFalse();
    }

    @Test
    @DisplayName("rejects a generic placeholder theme")
    void rejects_generic_theme() {
        assertThat(guard.accepts("Varios", TITLES)).isFalse();
        assertThat(guard.accepts("Tareas", TITLES)).isFalse();
    }

    @Test
    @DisplayName("rejects a placeholder-plus-index theme like 'Bloque 3'")
    void rejects_indexed_placeholder() {
        assertThat(guard.accepts("Bloque 3", TITLES)).isFalse();
    }

    @Test
    @DisplayName("rejects a hallucinated theme grounded in nothing the block holds")
    void rejects_hallucinated_theme() {
        assertThat(guard.accepts("Jardinería tropical", TITLES)).isFalse();
    }

    @Test
    @DisplayName("rejects an over-long theme that reads as a sentence, not a title")
    void rejects_over_long_theme() {
        String tooLong = "Informe ".repeat(12); // > 60 chars, though 'informe' is grounded
        assertThat(guard.accepts(tooLong, TITLES)).isFalse();
    }
}
