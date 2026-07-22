package com.hyperbrain.cognitive.domain.port.out;

import com.hyperbrain.cognitive.domain.LlmGatewayException;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;

/**
 * The out-port to a large language model (ADR-005): a single provider-agnostic verb so the cognitive
 * application depends on an abstraction, never on Spring AI or a concrete vendor. Two adapters realize
 * it — a Spring AI {@code ChatClient} adapter (real provider, switched on in H4) and a deterministic
 * stub (tests) — and swapping them changes no application code.
 *
 * <p>Implementations must apply the caller's timeout and treat an empty completion as a failure, so the
 * proposer's degradation path (ADR-019) is driven by exceptions, not by silent empty strings.
 */
public interface LlmGateway {

    /**
     * Completes the given prompt and returns the raw model text (expected to be the JSON the proposer
     * parses). Blocking; the implementation bounds the wait with its configured timeout.
     *
     * @param prompt the system + user prompt; never null
     * @return the raw completion text; never null nor blank
     * @throws LlmGatewayException on transport failure, timeout, or an empty/blank completion
     */
    String complete(LlmPrompt prompt);
}
