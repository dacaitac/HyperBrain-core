package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.domain.model.CoachSignals;
import org.springframework.stereotype.Component;

/**
 * The deterministic templated coach line (ADR-029 D3): the single degradation fallback the coach voice
 * degrades to whenever the LLM is absent or fails. It is not filler — like the live voice it is anchored to
 * the hard signals (wig_hit, abandoned, streak, adherence) and confronts the same 4DX gap, so a degraded
 * day still gets a legitimate, cited coach line rather than silence. Pure and total: it never fails and
 * never blocks the Scoreboard.
 */
@Component
public class CoachVoiceTemplate {

    /**
     * Renders a deterministic Spanish coach line citing the day's hard signals.
     *
     * @param signals the day's confrontable facts; never null
     * @return a short, signal-anchored coach line; never blank
     */
    public String render(CoachSignals signals) {
        if (signals == null) {
            throw new IllegalArgumentException("signals must not be null");
        }
        int adherencePct = (int) Math.round(signals.adherence() * 100);
        if (signals.abandoned()) {
            return "Ayer soltaste el día: adherencia del " + adherencePct
                + "% y cero replanificaciones. Hoy comprométete primero con tu WIG.";
        }
        if (signals.wigHit()) {
            int streak = signals.wigStreak();
            String racha = streak == 1 ? "1 día" : streak + " días";
            return "Cumpliste tu WIG — racha de " + racha
                + ". Protege hoy el bloque WIG para sostenerla.";
        }
        return "Tu WIG no se ejecutó ayer y la racha se rompió (adherencia del " + adherencePct
            + "%). Reserva el primer bloque de hoy para el WIG.";
    }
}
