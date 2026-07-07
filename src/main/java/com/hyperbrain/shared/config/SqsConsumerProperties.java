package com.hyperbrain.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the inbound SQS listener containers. Bound from the
 * {@code app.sync.consumer.*} namespace.
 *
 * <p>These settings apply to every {@code @SqsListener} in the application via the
 * {@code defaultSqsListenerContainerFactory} bean defined in {@link SqsConfig}.
 */
@ConfigurationProperties(prefix = "app.sync.consumer")
public class SqsConsumerProperties {

    /** SQS MaxNumberOfMessages per ReceiveMessage call. SQS hard cap is 10. */
    private int maxMessagesPerPoll = 10;

    /**
     * SQS WaitTimeSeconds for long polling. Eliminates empty-poll round-trips when the queue
     * is idle; SQS returns as soon as messages arrive, so latency is unaffected.
     */
    private int waitTimeSeconds = 20;

    /**
     * Visibility timeout in seconds. Must exceed the p99 processing time of the slowest
     * message handler so that a message is not redelivered while still being processed.
     *
     * <p>Criterion: p99 ingestion path (dedup check + DB write + optional Notion write-back)
     * ≈ 10 s; 3× safety margin = 30 s.
     */
    private int visibilityTimeoutSeconds = 30;

    public int getMaxMessagesPerPoll() {
        return maxMessagesPerPoll;
    }

    public void setMaxMessagesPerPoll(int maxMessagesPerPoll) {
        this.maxMessagesPerPoll = maxMessagesPerPoll;
    }

    public int getWaitTimeSeconds() {
        return waitTimeSeconds;
    }

    public void setWaitTimeSeconds(int waitTimeSeconds) {
        this.waitTimeSeconds = waitTimeSeconds;
    }

    public int getVisibilityTimeoutSeconds() {
        return visibilityTimeoutSeconds;
    }

    public void setVisibilityTimeoutSeconds(int visibilityTimeoutSeconds) {
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
    }
}
