package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link LlmPrompt} for the Scoreboard coach voice (ADR-029 D3) as a pure function of the day's
 * hard signals, so it is unit-testable without a provider. The whole design intent lives in the system
 * message: the model is told its job is <b>not</b> to generate plausible text but to confront the gap
 * between the committed lead measure (executing the WIG) and yesterday's observed behavior — and it is
 * fenced to the given signals only (wig_hit, abandoned, streak, adherence), never inventing data or tasks.
 * All the confrontable facts are handed over as trusted JSON control data; there is no untrusted user text
 * on this surface, so no injection fence is needed.
 */
@Component
public class CoachVoicePromptBuilder {

    static final String SYSTEM = """
        Eres el coach 4DX de HyperBrain. Tu trabajo NO es generar texto plausible ni motivación genérica: \
        es CONFRONTAR la brecha entre la medida de avance comprometida (ejecutar el WIG del día) y la \
        conducta observada, con cadencia y franqueza.

        Habla ÚNICAMENTE de las señales duras que se te entregan como datos: si se ejecutó el WIG \
        (wig_hit), si el día se abandonó (abandoned), la racha de días con WIG cumplido (wig_streak) y la \
        adherencia (adherence). NUNCA inventes datos, tareas, cifras ni contexto que no estén en esas \
        señales.

        Devuelve UNA sola frase breve en español (la lengua del usuario), directa y accionable, que cite la \
        señal relevante y proponga el siguiente compromiso. Sin listas, sin preámbulo, sin comillas: solo \
        la frase.""";

    private final ObjectMapper objectMapper;

    public CoachVoicePromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Renders the hard signals into a system + user prompt.
     *
     * @param signals the day's confrontable facts; never null
     * @return the prompt to send to the gateway
     */
    public LlmPrompt build(CoachSignals signals) {
        if (signals == null) {
            throw new IllegalArgumentException("signals must not be null");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("date", signals.date().toString());
        root.put("wig_hit", signals.wigHit());
        root.put("abandoned", signals.abandoned());
        root.put("wig_streak", signals.wigStreak());
        root.put("adherence", signals.adherence());

        String controlData;
        try {
            controlData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to render coach voice signals", ex);
        }

        String user = """
            SEÑALES DURAS DEL ÚLTIMO DÍA (los únicos hechos que puedes citar):
            %s
            """.formatted(controlData);
        return new LlmPrompt(SYSTEM, user);
    }
}
