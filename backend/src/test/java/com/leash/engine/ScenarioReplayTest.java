package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The parser against the official schema: the example event, and all 45 purchases rebuilt from the CSVs. */
class ScenarioReplayTest {
    private static JsonSchema schema() throws Exception {
        try (InputStream in = Files.newInputStream(Fixtures.DATA.resolve("schemas/authorization_event.schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    @Test
    void exampleEventIsValidAndParses() throws Exception {
        ObjectNode e = Fixtures.example();
        assertThat(schema().validate(e)).isEmpty();
        PurchaseFacts f = PurchaseFacts.parse(e);
        assertThat(f.authorizationId()).isEqualTo("AU_EXAMPLE_0001");
        assertThat(f.billingChf()).isEqualByComparingTo("20.0");
        assertThat(f.merchantId()).isEqualTo("ME_EXAMPLE_0001");
        assertThat(f.itemIds()).containsExactly("IT_EXAMPLE_0001");
    }

    @Test
    void allFortyFivePurchasesRebuildIntoSchemaValidEvents() throws Exception {
        JsonSchema schema = schema();
        int total = 0;
        for (String scen : List.of("SCEN0000", "SCEN0001", "SCEN0002", "SCEN0003", "SCEN0004")) {
            for (JsonNode e : Fixtures.scenarioEvents(scen)) {
                assertThat(schema.validate(e)).as(e.at("/authorization/authorization_id").asText()).isEmpty();
                PurchaseFacts.parse(e);
                total++;
            }
        }
        assertThat(total).isEqualTo(45);
    }

    @Test
    void malformedEventsAreRejectedWithTheMissingFields() {
        ObjectNode e = Fixtures.example();
        ((ObjectNode) e.get("authorization")).remove("billing_amount_chf");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PurchaseFacts.parse(e));
    }
}
