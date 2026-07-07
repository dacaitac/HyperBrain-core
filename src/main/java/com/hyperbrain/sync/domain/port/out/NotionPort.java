package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;

import java.util.Map;

/**
 * Outbound port to the Notion REST API (HU-10). Property maps follow the Notion JSON
 * property-value structure and are produced by the domain mappers; implementations only
 * transport them.
 *
 * <p>Implementations must respect Notion's rate limit (~3 requests/second average, RNF-01):
 * throttle outgoing calls and back off on {@code 429}. All methods are synchronous —
 * per ADR-011 Notion needs no result queue because {@code createPage} returns the page id
 * in the same call.
 */
public interface NotionPort {

    /**
     * Creates a page in a Notion data source.
     *
     * @param dataSourceId the target data source (Tasks or Cycles)
     * @param properties   Notion property map for the new page
     * @return the created page id, normalized without dashes
     * @throws NotionApiException if the API rejects the call or stays unavailable after retries
     */
    String createPage(String dataSourceId, Map<String, Object> properties);

    /**
     * Patches the properties of an existing page.
     *
     * @param pageId     the Notion page id (with or without dashes)
     * @param properties Notion property map with the values to write
     * @throws NotionPageNotFoundException if the page was deleted or archived in Notion
     * @throws NotionApiException          if the API rejects the call or stays unavailable
     */
    void updatePage(String pageId, Map<String, Object> properties);

    /**
     * Archives a page ({@code archived=true}); Notion's equivalent of a delete.
     *
     * @param pageId the Notion page id (with or without dashes)
     * @throws NotionPageNotFoundException if the page no longer exists
     * @throws NotionApiException          if the API rejects the call or stays unavailable
     */
    void archivePage(String pageId);
}
