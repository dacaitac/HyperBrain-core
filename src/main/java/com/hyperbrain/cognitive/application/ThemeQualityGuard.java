package com.hyperbrain.cognitive.application;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards the quality of the theme the LLM proposes for a grouped block (ADR-029, core#50). The LLM's
 * only compositional authority is to <em>name</em> a container; a name that is empty, generic
 * ("Varios", "Tareas", "Bloque 1") or hallucinated (words grounded in nothing the block actually holds)
 * is worse than no name at all — it misleads. When a theme fails this guard the caller falls back to the
 * anchor member's name, exactly as a themeless deterministic block already reads.
 *
 * <p><b>What passes.</b> A theme that is non-blank, not a generic placeholder, of a natural length, and
 * whose words are <em>grounded</em> — at least one meaningful word appears in the block's member titles
 * (a real shared attribute the model drew on), so it cannot be pure invention.
 *
 * <p>Design pattern: Specification — a single {@link #accepts} predicate composing the independent
 * quality rules, kept out of the orchestrator so the naming policy has one home and is unit-testable.
 */
@Component
public class ThemeQualityGuard {

    /** The longest a container name may reasonably be before it reads as a sentence, not a title. */
    static final int MAX_THEME_LENGTH = 60;

    /** Words shorter than this are ignored when grounding a theme (articles, prepositions, "y"/"de"). */
    private static final int MIN_MEANINGFUL_WORD_LENGTH = 4;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern ENDS_WITH_NUMBER = Pattern.compile(".*\\d\\s*$");

    /** Generic placeholders the model must never pass off as a real theme (lower-cased, accent-agnostic). */
    private static final Set<String> GENERIC_THEMES = Set.of(
        "varios", "tareas", "tarea", "bloque", "misc", "otros", "general", "pendientes",
        "trabajo", "cosas", "actividades", "varias");

    /**
     * Whether the proposed theme is a genuine, grounded name for a block holding {@code memberTitles}.
     *
     * @param theme        the theme the model proposed; may be null or blank
     * @param memberTitles the block's member display names — the ground truth the theme must draw on;
     *                     never null
     * @return true when the theme is non-generic, of natural length and grounded in the members; false
     *         when it must fall back to the anchor's name
     */
    public boolean accepts(String theme, Set<String> memberTitles) {
        if (theme == null || theme.isBlank()) {
            return false;
        }
        String normalized = theme.strip();
        if (normalized.length() > MAX_THEME_LENGTH) {
            return false;
        }
        // "Bloque 3", "Tarea 2" — a placeholder plus an index.
        if (ENDS_WITH_NUMBER.matcher(normalized).matches()) {
            return false;
        }
        Set<String> themeWords = meaningfulWords(theme);
        if (themeWords.isEmpty() || themeWords.stream().allMatch(GENERIC_THEMES::contains)) {
            return false;
        }
        // Grounding (anti-hallucination): at least one meaningful theme word must appear in a member
        // title, so the name is derived from a real shared attribute rather than invented.
        Set<String> titleWords = memberTitles.stream()
            .flatMap(title -> meaningfulWords(title).stream())
            .collect(java.util.stream.Collectors.toSet());
        return themeWords.stream().anyMatch(titleWords::contains);
    }

    /** The lower-cased, accent-folded meaningful words of a text (short stopword-length words dropped). */
    private static Set<String> meaningfulWords(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String token : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            String folded = fold(token);
            if (folded.length() >= MIN_MEANINGFUL_WORD_LENGTH) {
                words.add(folded);
            }
        }
        return words;
    }

    /** Strips diacritics so "programación" and "programacion" ground each other. */
    private static String fold(String token) {
        return java.text.Normalizer.normalize(token, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }
}
