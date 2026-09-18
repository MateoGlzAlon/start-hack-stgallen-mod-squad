package com.leash.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.FakeAi;
import com.leash.Json;
import com.leash.Settings;
import com.leash.web.ApiException;
import com.leash.worker.VisecaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyServiceTest {
    @TempDir Path tmp;
    Settings settings;
    PolicyStore store;
    PolicyService service;

    @BeforeEach
    void setUp() {
        settings = new Settings("http://localhost:1", "", false, "http://localhost:1/v1", "", "m", 1000, 1000, tmp.toString(), "none");
        FakeAi ai = new FakeAi(settings).says("""
                {"hard_rules":[
                   {"field":"authorization.billing_amount_chf","operator":"<=","value":200,"currency":"CHF","scope":"purchase","period_days":null},
                   {"field":"items.item_details","operator":"in","value":["black"],"currency":null,"scope":null,"period_days":null}],
                 "uncertainty_policy":"ask","guidance":["Black running shoes"],"open_questions":["Are trail shoes OK?"]}""");
        store = new PolicyStore(settings);
        service = new PolicyService(store, new PolicyCompiler(ai, settings), new VisecaClient(settings));
    }

    private JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    @Test
    void compilesADraftKeepsOnlyEnforceableRulesAndStoresItAsJson() throws Exception {
        Policy p = service.createDraft("Buy me black running shoes for up to CHF 200.");
        assertThat(p.status).isEqualTo("draft");
        assertThat(p.instruction).isEqualTo("Buy me black running shoes for up to CHF 200.");
        assertThat(p.hardRules).hasSize(1);
        assertThat(p.guidance).contains("Black running shoes").anyMatch(g -> g.startsWith("Not enforced automatically") && g.contains("items.item_details"));
        assertThat(p.openQuestions).containsExactly("Are trail shoes OK?");
        // the wire format has no null optional keys
        assertThat(Json.MAPPER.writeValueAsString(p.hardRules.get(0))).doesNotContain("period_days").contains("\"scope\":\"purchase\"");
        // persisted as text JSON, and reloadable
        assertThat(Files.readString(tmp.resolve("policies.json"))).contains(p.id).contains("\"uncertainty_policy\"");
        assertThat(new PolicyStore(settings).get(p.id)).isPresent();
    }

    @Test
    void onlyADraftCanBeConfirmed() {
        Policy p = service.createDraft("x");
        assertThat(service.confirm(p.id).status).isEqualTo("active");
        assertThatThrownBy(() -> service.confirm(p.id)).isInstanceOf(ApiException.class);
    }

    @Test
    void tighteningCanAddRulesButNeverRemoveOrLoosen() throws Exception {
        Policy p = service.createDraft("x");
        service.confirm(p.id);

        assertThatThrownBy(() -> service.patch(p.id, json("{\"hard_rules\":[]}"))).hasMessageContaining("cannot be removed");
        assertThatThrownBy(() -> service.patch(p.id, json("""
                {"hard_rules":[{"field":"authorization.billing_amount_chf","operator":"<=","value":300,"currency":"CHF","scope":"purchase"}]}"""))).hasMessageContaining("cannot be removed");
        assertThatThrownBy(() -> service.patch(p.id, json("{\"uncertainty_policy\":\"approve\"}"))).hasMessageContaining("toward decline");
        assertThatThrownBy(() -> service.patch(p.id, json("""
                {"hard_rules":[{"field":"authorization.billing_amount_chf","operator":"<=","value":200,"currency":"CHF","scope":"purchase"},
                               {"field":"made.up","operator":"=","value":"x"}]}"""))).hasMessageContaining("Invalid rule");

        // the existing rule (200.0 == 200) plus a new one is fine
        var out = service.patch(p.id, json("""
                {"hard_rules":[{"field":"authorization.billing_amount_chf","operator":"<=","value":200.0,"currency":"CHF","scope":"purchase"},
                               {"field":"merchant.merchant_category","operator":"in","value":["sporting_goods"]}],
                 "uncertainty_policy":"decline"}"""));
        assertThat(out.get("hard_rules")).hasSize(2);
        assertThat(out.get("uncertainty_policy").asText()).isEqualTo("decline");
        assertThat(out.get("viseca_sync").asText()).isEqualTo("not_synced");
        assertThatThrownBy(() -> service.patch(p.id, json("{\"uncertainty_policy\":\"ask\"}"))).hasMessageContaining("toward decline");
    }

    @Test
    void revokeIsImmediateAndFinal() throws Exception {
        Policy p = service.createDraft("x");
        service.confirm(p.id);
        assertThat(service.revoke(p.id).get("status").asText()).isEqualTo("revoked");
        assertThat(service.revoke(p.id).get("viseca_sync").asText()).isEqualTo("already_revoked");
        assertThatThrownBy(() -> service.patch(p.id, json("{\"uncertainty_policy\":\"decline\"}"))).isInstanceOf(ApiException.class);
    }
}
