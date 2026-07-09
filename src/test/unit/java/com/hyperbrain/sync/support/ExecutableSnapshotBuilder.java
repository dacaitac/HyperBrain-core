package com.hyperbrain.sync.support;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Test-data builder for {@link ExecutableSnapshot}: keeps unit tests insulated from the
 * record's positional constructor (fields default to a minimal TASK/TODO row).
 */
public final class ExecutableSnapshotBuilder {

    private UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private UUID parentId;
    private UUID cycleId;
    private String name = "t";
    private String description;
    private String type = "TASK";
    private String status = "TODO";
    private Double priorityScore;
    private Double urgencyScore;
    private Double effortScore;
    private Boolean isImportant = false;
    private Double frequency;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String sourceCalendar;
    private Integer energyDrain;
    private Integer mentalLoad;
    private Integer impact;
    private boolean systemGenerated = false;

    public static ExecutableSnapshotBuilder snapshot() {
        return new ExecutableSnapshotBuilder();
    }

    public ExecutableSnapshotBuilder id(UUID value) {
        this.id = value;
        return this;
    }

    public ExecutableSnapshotBuilder userId(UUID value) {
        this.userId = value;
        return this;
    }

    public ExecutableSnapshotBuilder parentId(UUID value) {
        this.parentId = value;
        return this;
    }

    public ExecutableSnapshotBuilder cycleId(UUID value) {
        this.cycleId = value;
        return this;
    }

    public ExecutableSnapshotBuilder name(String value) {
        this.name = value;
        return this;
    }

    public ExecutableSnapshotBuilder description(String value) {
        this.description = value;
        return this;
    }

    public ExecutableSnapshotBuilder type(String value) {
        this.type = value;
        return this;
    }

    public ExecutableSnapshotBuilder status(String value) {
        this.status = value;
        return this;
    }

    public ExecutableSnapshotBuilder priorityScore(Double value) {
        this.priorityScore = value;
        return this;
    }

    public ExecutableSnapshotBuilder urgencyScore(Double value) {
        this.urgencyScore = value;
        return this;
    }

    public ExecutableSnapshotBuilder effortScore(Double value) {
        this.effortScore = value;
        return this;
    }

    public ExecutableSnapshotBuilder isImportant(Boolean value) {
        this.isImportant = value;
        return this;
    }

    public ExecutableSnapshotBuilder frequency(Double value) {
        this.frequency = value;
        return this;
    }

    public ExecutableSnapshotBuilder startTime(OffsetDateTime value) {
        this.startTime = value;
        return this;
    }

    public ExecutableSnapshotBuilder endTime(OffsetDateTime value) {
        this.endTime = value;
        return this;
    }

    public ExecutableSnapshotBuilder sourceCalendar(String value) {
        this.sourceCalendar = value;
        return this;
    }

    public ExecutableSnapshotBuilder energyDrain(Integer value) {
        this.energyDrain = value;
        return this;
    }

    public ExecutableSnapshotBuilder mentalLoad(Integer value) {
        this.mentalLoad = value;
        return this;
    }

    public ExecutableSnapshotBuilder impact(Integer value) {
        this.impact = value;
        return this;
    }

    public ExecutableSnapshotBuilder systemGenerated(boolean value) {
        this.systemGenerated = value;
        return this;
    }

    public ExecutableSnapshot build() {
        return new ExecutableSnapshot(id, userId, parentId, cycleId, name, description, type,
            status, priorityScore, urgencyScore, effortScore, isImportant, frequency,
            startTime, endTime, sourceCalendar, energyDrain, mentalLoad, impact, systemGenerated);
    }
}
