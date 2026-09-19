package com.leash.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.engine.CheckService;
import com.leash.engine.Fx;
import com.leash.engine.HistoryIndex;
import com.leash.llm.OpenAiClient;
import com.leash.policy.PolicyStore;
import com.leash.worker.DecisionWorker;
import com.leash.worker.VisecaClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Status")
@RestController
public class StatusController {
    private final Settings settings;
    private final OpenAiClient openai;
    private final VisecaClient viseca;
    private final DecisionWorker worker;
    private final HistoryIndex history;
    private final PolicyStore policies;
    private final CheckService checks;

    public StatusController(Settings settings, OpenAiClient openai, VisecaClient viseca, DecisionWorker worker,
                            HistoryIndex history, PolicyStore policies, CheckService checks) {
        this.settings = settings;
        this.openai = openai;
        this.viseca = viseca;
        this.worker = worker;
        this.history = history;
        this.policies = policies;
        this.checks = checks;
    }

    @Operation(summary = "What is configured and running", description = "OpenAI and Viseca keys set or not, worker state, history loaded, counters.")
    @GetMapping("/status")
    ObjectNode status() {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        n.putObject("openai").put("configured", openai.configured()).put("model", openai.model());
        ObjectNode v = n.putObject("viseca");
        v.put("configured", viseca.configured()).put("base_url", settings.visecaBaseUrl).put("worker_running", worker.running());
        v.put("purchases_answered", worker.handled());
        if (worker.lastPurchaseAt() != null) v.put("last_purchase_at", worker.lastPurchaseAt().toString());
        n.putObject("history").put("loaded", history.rows() > 0).put("approved_purchases", history.rows());
        ObjectNode fx = n.putObject("fx");
        fx.put("source", Fx.source());
        ObjectNode toChf = fx.putObject("to_chf");
        Fx.rates().forEach((c, r) -> toChf.put(c, r));
        n.put("policies", policies.all().size());
        n.put("decisions", checks.list(null, null).size());
        return n;
    }
}
