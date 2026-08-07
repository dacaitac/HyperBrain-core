package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/*
 * Design pattern: single-algorithm domain service (pure function of its inputs).
 * Reason: laying the day's windows is one fixed policy, and it must be reproducible from the same
 * inputs on every run — the conservative replan depends on the same slot yielding the same window
 * when nothing around it moved.
 */

/**
 * Lays the day's windows: resolves the {@link DayTemplate} onto the target day against the real wake
 * instant, clips every band to the planning window and to the hard walls, and returns what survives.
 *
 * <p><b>This is the structure that replaces the cursor sweep.</b> The engine it supersedes advanced
 * from the start of the day and returned the first free gap, so with nothing acting as a wall it
 * compressed every task against the first hour — the wall of flat blocks ADR-040 exists to undo. A
 * window is now an object of the run, and work goes inside windows.
 *
 * <p><b>What clips a window</b> is only ever the time axis (ADR-040 D1): the sleep frontier, the
 * {@code AGENDA} walls, the blocks that already hold time and the protected meal anchors. Nothing
 * discounts capacity, and nothing trims the tail of the day.
 *
 * <p><b>A clipped window keeps its slot.</b> When a hard commitment eats into a band, the window
 * survives as its <b>largest remaining fragment</b> and carries the same slot id — which is exactly the
 * case ADR-040 D14 argues for the slot column: a displaced block is no longer on its original hours, so
 * the hours stop identifying it while the band still does. Only one window per slot survives, so a
 * slot never yields two competing blocks.
 *
 * <p><b>Gaps are admitted.</b> A slot whose band is entirely spoken for simply does not yield a window.
 * There is no target occupancy and nothing to fill (ADR-040 D2).
 */
public class DayWindowResolver {

    private final DayTemplate template;

    /**
     * @param template the day's shape; never null
     */
    public DayWindowResolver(DayTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        this.template = template;
    }

    /**
     * Lays the windows of one concrete day.
     *
     * @param targetDay  the calendar day being planned; never null
     * @param zone       the user's timezone; never null
     * @param wake       the real wake instant the template rides on; never null
     * @param lowerBound the earliest instant this run may place at (wake, or now on a replan); never null
     * @param upperBound the bedtime edge; never null, after {@code lowerBound}
     * @param walls      the hard walls to clip against; never null, may be empty
     * @return the day's usable windows, chronologically ordered, at most one per slot; never null
     */
    public List<DayWindow> resolve(LocalDate targetDay, ZoneId zone, OffsetDateTime wake,
                                   OffsetDateTime lowerBound, OffsetDateTime upperBound,
                                   List<OccupiedInterval> walls) {
        if (lowerBound == null || upperBound == null || walls == null) {
            throw new IllegalArgumentException("bounds and walls must not be null");
        }
        List<OccupiedInterval> sortedWalls = walls.stream()
            .sorted(Comparator.comparing(OccupiedInterval::start))
            .toList();

        List<DayWindow> resolved = new ArrayList<>();
        for (DayWindow window : template.resolve(targetDay, zone, wake)) {
            if (!window.schedulable()) {
                continue;
            }
            DayWindow bounded = clampToBounds(window, lowerBound, upperBound);
            if (bounded == null) {
                continue;
            }
            DayWindow usable = largestFragmentClear(bounded, sortedWalls);
            if (usable != null) {
                resolved.add(usable);
            }
        }
        resolved.sort(Comparator.comparing(DayWindow::start));
        return List.copyOf(resolved);
    }

    /** The window narrowed to the planning bounds, or null when nothing of it lies inside them. */
    private static DayWindow clampToBounds(DayWindow window, OffsetDateTime lowerBound,
                                           OffsetDateTime upperBound) {
        OffsetDateTime start = window.start().isBefore(lowerBound) ? lowerBound : window.start();
        OffsetDateTime end = window.end().isAfter(upperBound) ? upperBound : window.end();
        return end.isAfter(start) ? window.narrowedTo(start, end) : null;
    }

    /**
     * The largest stretch of the window that no wall touches, or null when every minute of it is spoken
     * for. Walking the walls in order and keeping the widest survivor is what lets a band displaced by a
     * hard commitment keep serving its slot instead of disappearing from the day.
     */
    private static DayWindow largestFragmentClear(DayWindow window, List<OccupiedInterval> walls) {
        OffsetDateTime bestStart = null;
        OffsetDateTime bestEnd = null;
        OffsetDateTime cursor = window.start();

        for (OccupiedInterval wall : walls) {
            if (!wall.overlaps(window.start(), window.end())) {
                continue;
            }
            if (wall.start().isAfter(cursor)) {
                OffsetDateTime fragmentEnd = wall.start().isBefore(window.end())
                    ? wall.start() : window.end();
                if (isWider(cursor, fragmentEnd, bestStart, bestEnd)) {
                    bestStart = cursor;
                    bestEnd = fragmentEnd;
                }
            }
            if (wall.end().isAfter(cursor)) {
                cursor = wall.end();
            }
            if (!cursor.isBefore(window.end())) {
                break;
            }
        }
        if (cursor.isBefore(window.end()) && isWider(cursor, window.end(), bestStart, bestEnd)) {
            bestStart = cursor;
            bestEnd = window.end();
        }
        return bestStart == null ? null : window.narrowedTo(bestStart, bestEnd);
    }

    private static boolean isWider(OffsetDateTime start, OffsetDateTime end,
                                   OffsetDateTime bestStart, OffsetDateTime bestEnd) {
        if (!end.isAfter(start)) {
            return false;
        }
        if (bestStart == null) {
            return true;
        }
        return java.time.Duration.between(start, end)
            .compareTo(java.time.Duration.between(bestStart, bestEnd)) > 0;
    }
}
