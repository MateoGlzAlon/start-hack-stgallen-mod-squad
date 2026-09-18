package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.worker.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Runs")
@RestController
@RequestMapping("/runs")
public class RunController {
    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    /** {"policy_id": "POL-...", "scenario_id": "SCEN0000"} */
    @Operation(summary = "Start a Viseca scenario run for an active policy",
            description = "Pushes the policy to Viseca as a mandate (draft, then confirm) if needed and starts the run. The worker then decides each purchase "
                    + "automatically. Needs TEAM_API_KEY.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"policy_id": "POL-1234abcd", "scenario_id": "SCEN0000"}"""))))
    @PostMapping
    ObjectNode start(@RequestBody JsonNode body) {
        return runs.start(body.path("policy_id").asText(null), body.path("scenario_id").asText(null));
    }

    @Operation(summary = "Run progress from Viseca plus our decisions for it")
    @GetMapping("/{runId}")
    ObjectNode get(@PathVariable String runId) {
        return runs.get(runId);
    }
}
