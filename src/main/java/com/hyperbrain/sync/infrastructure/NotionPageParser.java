package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.NotionPageEditState;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.service.NotionSchema;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Extracts the property-level view of a raw Notion page object (HU-14): the JSON shape is
 * identical whether the page arrives embedded in an automation webhook, fetched from the API
 * for a thin subscription delivery, or listed by the backfill query. Missing or malformed
 * properties map to null — the inbound mappers own the defaulting rules.
 */
@Component
public class NotionPageParser {

    /**
     * Parses a Tasks database page.
     *
     * @param page the raw page object ({@code object=page})
     * @return the property-level view
     */
    public NotionTaskPage parseTask(JsonNode page) {
        JsonNode props = page.path("properties");
        return new NotionTaskPage(
            normalizeId(page.path("id").asText(null)),
            parseTimestamp(page.path("last_edited_time").asText(null)),
            isArchived(page),
            text(props.path(NotionSchema.PROP_NAME)),
            text(props.path(NotionSchema.PROP_DESCRIPTION)),
            optionName(props.path(NotionSchema.PROP_STATUS), "status"),
            checkbox(props.path(NotionSchema.PROP_COMPLETE)),
            optionName(props.path(NotionSchema.PROP_TYPE), "select"),
            dateBound(props.path(NotionSchema.PROP_DATE), "start"),
            dateBound(props.path(NotionSchema.PROP_DATE), "end"),
            number(props.path(NotionSchema.PROP_PRIORITY_SCORE)),
            number(props.path(NotionSchema.PROP_URGENCE)),
            number(props.path(NotionSchema.PROP_EFFORT)),
            checkbox(props.path(NotionSchema.PROP_IMPORTANT)),
            number(props.path(NotionSchema.PROP_FREQUENCY)),
            optionName(props.path(NotionSchema.PROP_IMPACT), "select"),
            optionName(props.path(NotionSchema.PROP_ENERGY), "select"),
            optionName(props.path(NotionSchema.PROP_MENTAL_LOAD), "select"),
            firstRelationId(props.path(NotionSchema.PROP_CYCLE)),
            firstRelationId(props.path(NotionSchema.PROP_PARENT_TASK)));
    }

    /**
     * Parses a Cycles database page.
     *
     * @param page the raw page object ({@code object=page})
     * @return the property-level view
     */
    public NotionCyclePage parseCycle(JsonNode page) {
        JsonNode props = page.path("properties");
        return new NotionCyclePage(
            normalizeId(page.path("id").asText(null)),
            parseTimestamp(page.path("last_edited_time").asText(null)),
            isArchived(page),
            text(props.path(NotionSchema.PROP_NAME)),
            optionName(props.path(NotionSchema.PROP_TYPE), "select"),
            dateBound(props.path(NotionSchema.PROP_DATE), "start"),
            dateBound(props.path(NotionSchema.PROP_DATE), "end"),
            checkbox(props.path(NotionSchema.PROP_INACTIVE)));
    }

    /**
     * Extracts the authorship view of a raw Notion page object (the outbound staleness guard):
     * who last edited the page and when. The editor id is normalized (lowercase, no dashes) so it
     * compares cleanly against the configured integration bot id regardless of dash formatting.
     *
     * @param page the raw page object ({@code object=page})
     * @return the {@code last_edited_by.id} / {@code last_edited_time} pair
     */
    public NotionPageEditState parseEditState(JsonNode page) {
        return new NotionPageEditState(
            normalizeId(page.path("last_edited_by").path("id").asText(null)),
            parseTimestamp(page.path("last_edited_time").asText(null)));
    }

    /**
     * Normalizes a Notion id to the {@code sync_mappings.external_id} convention:
     * lowercase, no dashes.
     *
     * @param id raw Notion id, or null
     * @return the normalized id, or null
     */
    public static String normalizeId(String id) {
        return id != null ? id.replace("-", "").toLowerCase(Locale.ROOT) : null;
    }

    private static boolean isArchived(JsonNode page) {
        return page.path("archived").asBoolean(false) || page.path("in_trash").asBoolean(false);
    }

    private static String text(JsonNode property) {
        JsonNode items = property.has("title") ? property.path("title") : property.path("rich_text");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        StringBuilder content = new StringBuilder();
        for (JsonNode item : items) {
            String plain = item.path("plain_text").asText(null);
            content.append(plain != null ? plain : item.path("text").path("content").asText(""));
        }
        return content.isEmpty() ? null : content.toString();
    }

    private static String optionName(JsonNode property, String kind) {
        return property.path(kind).path("name").asText(null);
    }

    private static Boolean checkbox(JsonNode property) {
        JsonNode value = property.path("checkbox");
        return value.isBoolean() ? value.asBoolean() : null;
    }

    private static Double number(JsonNode property) {
        JsonNode value = property.path("number");
        return value.isNumber() ? value.asDouble() : null;
    }

    private static String dateBound(JsonNode property, String bound) {
        return property.path("date").path(bound).asText(null);
    }

    private static String firstRelationId(JsonNode property) {
        JsonNode relations = property.path("relation");
        if (!relations.isArray() || relations.isEmpty()) {
            return null;
        }
        return normalizeId(relations.get(0).path("id").asText(null));
    }

    private static OffsetDateTime parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
