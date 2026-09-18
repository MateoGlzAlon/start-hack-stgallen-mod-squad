package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.worker.RunService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/runs")
public class RunController {
    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    /** {"policy_id": "POL-...", "scenario_id": "SCEN0000"} */
    @PostMapping
    ObjectNode start(@RequestBody JsonNode body) {
        return runs.start(body.path("policy_id").asText(null), body.path("scenario_id").asText(null));
    }

    @GetMapping("/{runId}")
    ObjectNode get(@PathVariable String runId) {
        return runs.get(runId);
    }
}
