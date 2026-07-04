package com.hyperbrain.shared.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the Transactional Outbox relay. Bound from the {@code app.outbox.*} namespace.
 */
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    /** Delay between drain polls, in milliseconds. */
    private long pollIntervalMs = 2000;

    /** Maximum events claimed per drain. */
    private int batchSize = 50;

    /** Retention window before processed events are purged, in days. */
    private int retentionDays = 7;

    /** Whether the scheduled drain/purge triggers are active. Disabled in tests. */
    private boolean schedulingEnabled = true;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }
}
