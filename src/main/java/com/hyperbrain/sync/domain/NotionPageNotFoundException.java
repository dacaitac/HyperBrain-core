package com.hyperbrain.sync.domain;

/**
 * Raised when a Notion page referenced by a {@code sync_mapping} no longer exists ({@code 404}),
 * e.g. it was deleted manually in Notion. The write-back repairs the mapping instead of
 * retrying forever (HU-10, CA-15).
 */
public class NotionPageNotFoundException extends NotionApiException {

    public NotionPageNotFoundException(String pageId) {
        super("Notion page not found: " + pageId);
    }
}
