package com.teachmi.backend.learning.gemini;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class JsonResponseSchemaTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={
            "{\"type\":\"mystery\"}",
            "{\"type\":\"array\"}",
            "{\"type\":\"string\",\"pattern\":\".*\"}",
            "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}",
            "{\"type\":\"string\",\"minLength\":10,\"maxLength\":2}",
            "{\"type\":\"string\",\"minLength\":4294967296}",
            "{\"type\":\"string\",\"enum\":[]}"
    })
    void unsupportedOrInvalidContractsFailBeforeCallingTheProvider(String schema) {
        assertThatThrownBy(() -> JsonResponseSchema.check(json.readTree(schema))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everyRequiredFieldAndUnexpectedPropertyIsChecked() {
        var schema=json.readTree("""
                {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":2},
                "name":{"type":"string","minLength":1,"maxLength":4}},"required":["count","name"],"additionalProperties":false}
                """);
        JsonResponseSchema.check(schema);
        JsonResponseSchema.validate(json.readTree("{\"count\":1,\"name\":\"ok\"}"),schema);
        JsonResponseSchema.validate(json.readTree("{\"count\":1.0,\"name\":\"ok\"}"),schema);
        for(String payload:java.util.List.of("{\"count\":0,\"name\":\"ok\"}","{\"count\":1.5,\"name\":\"ok\"}",
                "{\"count\":3,\"name\":\"ok\"}","{\"count\":1,\"name\":\"\"}","{\"count\":1,\"name\":\"longer\"}",
                "{\"count\":1}","{\"count\":1,\"name\":\"ok\",\"extra\":true}")) {
            assertThatThrownBy(() -> JsonResponseSchema.validate(json.readTree(payload),schema)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void arrayBoundsEnumsAndNullabilityAreEnforced() {
        var schema=json.readTree("{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":[\"string\",\"null\"],\"enum\":[\"ready\",null]}}");
        JsonResponseSchema.check(schema);
        JsonResponseSchema.validate(json.readTree("[\"ready\",null]"),schema);
        for(String payload:java.util.List.of("[]","[\"wrong\"]","[\"ready\",null,\"ready\"]")) {
            assertThatThrownBy(() -> JsonResponseSchema.validate(json.readTree(payload),schema)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
