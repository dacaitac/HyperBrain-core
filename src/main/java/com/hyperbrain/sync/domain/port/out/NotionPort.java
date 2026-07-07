package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;

import java.util.List;
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

    /**
     * Retrieves the current state of a page as raw JSON (HU-14): subscription webhook
     * deliveries are thin, so the Consumer fetches the page to map its properties.
     *
     * @param pageId the Notion page id (with or without dashes)
     * @return the raw page object JSON
     * @throws NotionPageNotFoundException if the page was permanently deleted or access was lost
     * @throws NotionApiException          if the API rejects the call or stays unavailable
     */
    String retrievePage(String pageId);

    /**
     * Lists every page of a data source, following pagination to the end (HU-14 backfill,
     * CA-8). Suitable for the MVP data volumes (hundreds of pages); each underlying request
     * honors the rate-limit throttle.
     *
     * @param dataSourceId the data source to query (Tasks or Cycles)
     * @return the raw page object JSONs, in query order
     * @throws NotionApiException if the API rejects a call or stays unavailable
     */
    List<String> queryAllPages(String dataSourceId);
}
