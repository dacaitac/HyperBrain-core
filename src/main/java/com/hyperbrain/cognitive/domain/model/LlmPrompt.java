package com.hyperbrain.cognitive.domain.model;

/**
 * A two-part chat prompt for the {@code LlmGateway}: an instruction {@code system} message (the coach
 * role and the inviolable hard walls) and a {@code user} message (the trusted control data plus the
 * delimited, untrusted titles). Keeping the split explicit lets the gateway map it onto any provider's
 * system/user roles, and lets the prompt builder be unit-tested as a pure function of the day.
 *
 * @param system the system instruction; never null nor blank
 * @param user   the user message (control data + delimited untrusted content); never null nor blank
 */
public record LlmPrompt(String system, String user) {

    public LlmPrompt {
        if (system == null || system.isBlank()) {
            throw new IllegalArgumentException("system must not be blank");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
    }
}
