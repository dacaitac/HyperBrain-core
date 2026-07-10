package com.hyperbrain.planner.domain.model;

/**
 * The executable kinds the Planner distinguishes when materializing the day. A planner-local mirror
 * of {@code core_executable.type} (kept here so the domain stays isolated from the core module's
 * internals — ADR-005 / ModuleIsolationTest), carrying only the distinctions the floor needs:
 * {@code LEAD_MEASURE} is the WIG's action type reserved first (F1), and {@code AGENDA} is the
 * read-only wall (ADR-009).
 */
public enum ExecutableType {
    TASK,
    HABIT,
    LEAD_MEASURE,
    ACTIVITY,
    AGENDA,
    LEARNING_SESSION
}
