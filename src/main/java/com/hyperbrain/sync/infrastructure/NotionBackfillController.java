package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.application.NotionBackfillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger of the Notion backfill (HU-14 CA-8): {@code POST /internal/notion/backfill}.
 * Internal operations endpoint — the Core is never exposed publicly ({@code daniel-ubuntu}
 * stays private, ADR-011); same trust model as the SentinelAPI {@code /resync}. Thin
 * controller: delegates and serializes the summary.
 */
@RestController
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
class NotionBackfillController {

    private final NotionBackfillService backfillService;

    NotionBackfillController(NotionBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/internal/notion/backfill")
    NotionBackfillService.BackfillSummary backfill() {
        return backfillService.backfill();
    }
}
