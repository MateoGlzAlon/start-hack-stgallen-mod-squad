package com.leash.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.Settings;
import com.leash.engine.CheckService;
import com.leash.engine.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Always-on long-poll loop: next purchase -> engine -> POST /decision. Never waits for a human:
 * a step_up is posted right away and the customer's answer arrives later through POST /check/{id}/resolve.
 * Purchases are handled one at a time, in delivery order, so the spend ledger sees them in order.
 */
@Component
public class DecisionWorker {
    private static final Logger log = LoggerFactory.getLogger(DecisionWorker.class);

    private final VisecaClient viseca;
    private final CheckService checks;
    private final Settings settings;
    private volatile boolean running;
    private volatile Instant lastPurchaseAt;
    private volatile int handled;

    public DecisionWorker(VisecaClient viseca, CheckService checks, Settings settings) {
        this.viseca = viseca;
        this.checks = checks;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!settings.workerEnabled || !viseca.configured()) {
            log.info("Viseca worker not started (WORKER_ENABLED={}, TEAM_API_KEY {})", settings.workerEnabled, viseca.configured() ? "set" : "missing");
            return;
        }
        running = true;
        Thread.ofPlatform().name("decision-worker").daemon(true).start(this::loop);
        log.info("Viseca worker started, polling {}", settings.visecaBaseUrl);
    }

    public boolean running() { return running; }

    public int handled() { return handled; }

    public Instant lastPurchaseAt() { return lastPurchaseAt; }

    private void loop() {
        while (running) {
            try {
                Optional<JsonNode> envelope = viseca.next(25);
                envelope.ifPresent(this::handle);          // 204 => nothing yet, poll again
            } catch (Exception e) {
                log.warn("Polling failed: {} - retrying shortly", e.getMessage());
                sleep(2000);
            }
        }
    }

    void handle(JsonNode envelope) {
        String runId = envelope.path("run_id").asText(null);
        JsonNode event = envelope.path("data");
        String authId = envelope.path("authorization_id").asText(event.path("authorization").path("authorization_id").asText(null));
        lastPurchaseAt = Instant.now();
        Decision d;
        try {
            d = checks.check(event, null, runId, "viseca");
        } catch (RuntimeException e) {
            log.error("Could not evaluate {} - asking the customer", authId, e);
            if (authId == null) return;
            d = checks.failSafeStepUp(authId, runId, "viseca", "invalid_event", "I could not read this purchase request, so I am asking you to review it.");
        }
        if (d.postedToViseca()) return;               // redelivery of something we already answered

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                viseca.postDecision(d);
                checks.markPosted(d.authorizationId(), true, null);
                handled++;
                log.info("{} -> {} ({})", d.authorizationId(), d.visecaDecision(), String.join(",", d.reasonCodes()));
                return;
            } catch (RuntimeException e) {
                log.warn("Posting decision for {} failed (attempt {}): {}", d.authorizationId(), attempt, e.getMessage());
                checks.markPosted(d.authorizationId(), false, e.getMessage());
                sleep(300L * attempt);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
