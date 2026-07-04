/**
 * Transactional Outbox relay shared across modules: the {@link com.hyperbrain.shared.outbox.OutboxWorker}
 * drains {@code outbox_events} and publishes via the messaging port. Depends on no feature module.
 */
package com.hyperbrain.shared.outbox;
