package com.hyperbrain.sync.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Guard tests for CA-9: writing a formula/rollup Notion property is a programming error.
 */
@DisplayName("NotionSchema — read-only property guard (CA-9)")
class NotionSchemaTest {

    @ParameterizedTest(name = "writing ''{0}'' is rejected")
    @CsvSource({"Costo", "Theme Priority", "Presupuestado", "Ejecutado"})
    @DisplayName("assertWritable rejects every read-only property")
    void rejects_read_only_property(String property) {
        Map<String, Object> props = Map.of(property, Map.of("number", 1.0));

        assertThatIllegalStateException()
            .isThrownBy(() -> NotionSchema.assertWritable(props))
            .withMessageContaining(property)
            .withMessageContaining("CA-9");
    }

    @Test
    @DisplayName("assertWritable accepts a map of writable properties")
    void accepts_writable_properties() {
        Map<String, Object> props = Map.of(
            NotionSchema.PROP_NAME, Map.of(),
            NotionSchema.PROP_STATUS, Map.of(),
            NotionSchema.PROP_PRIORITY_SCORE, Map.of());

        NotionSchema.assertWritable(props);

        assertThat(props).hasSize(3);
    }
}
