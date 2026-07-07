package com.hyperbrain.sync.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the Notion write-back (HU-10). Bound from the {@code app.sync.notion.*}
 * namespace; the token and database ids are provisioned via SOPS in HyperBrain-Infra (CA-12).
 */
@ConfigurationProperties(prefix = "app.sync.notion")
public class NotionSyncProperties {

    /** Master switch; when false no Notion bean is registered and the drain skips Notion. */
    private boolean enabled = false;

    /** Notion API root; overridden in integration tests to point at the WireMock stub. */
    private String baseUrl = "https://api.notion.com";

    /** Integration token (secret; never logged). */
    private String token = "";

    /** Notion API version header; data sources require 2025-09-03. */
    private String notionVersion = "2025-09-03";

    /** Data source id of the Tasks database. */
    private String tasksDataSourceId = "";

    /** Data source id of the Cycles database. */
    private String cyclesDataSourceId = "";

    /** Attempts per request before the failure escalates (429/5xx/IO, CA-8/CA-13). */
    private int maxAttempts = 3;

    /** Minimum spacing between requests, ms (~3 rps Notion rate limit, RNF-01). */
    private long minRequestIntervalMs = 350;

    /** Base delay of the exponential backoff, ms (doubles per attempt). */
    private long backoffBaseMs = 500;

    /** HTTP connect timeout, ms. */
    private long connectTimeoutMs = 5000;

    /** HTTP read timeout, ms. */
    private long readTimeoutMs = 15000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNotionVersion() {
        return notionVersion;
    }

    public void setNotionVersion(String notionVersion) {
        this.notionVersion = notionVersion;
    }

    public String getTasksDataSourceId() {
        return tasksDataSourceId;
    }

    public void setTasksDataSourceId(String tasksDataSourceId) {
        this.tasksDataSourceId = tasksDataSourceId;
    }

    public String getCyclesDataSourceId() {
        return cyclesDataSourceId;
    }

    public void setCyclesDataSourceId(String cyclesDataSourceId) {
        this.cyclesDataSourceId = cyclesDataSourceId;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getMinRequestIntervalMs() {
        return minRequestIntervalMs;
    }

    public void setMinRequestIntervalMs(long minRequestIntervalMs) {
        this.minRequestIntervalMs = minRequestIntervalMs;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
