package com.leash.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Swagger UI at /swagger-ui.html, OpenAPI JSON at /v3/api-docs. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Agent on a Leash - wallet control API").version("0.1.0").description("""
                        The customer-facing API of the wallet control layer. Typical flow:
                        **1.** `POST /policies` turns a sentence into an active policy (no draft or confirm step), \
                        **2.** purchases are decided by `POST /check` (all active policies and the purchase go to OpenAI), or automatically by the Viseca worker after `POST /runs`, \
                        **3.** a `pending_human` decision is answered with `POST /check/{authorization_id}/resolve`, **4.** `PATCH` tightens and `DELETE` revokes a policy.
                        States: `approved` = approve, `denied` = decline, `pending_human` = step_up. Errors are `{"error": {"status", "message"}}`."""))
                .tags(List.of(
                        new Tag().name("Policies").description("Create (active at once), tighten and revoke the customer's wallet policy"),
                        new Tag().name("Check").description("Decide a purchase and answer a pending one"),
                        new Tag().name("Runs").description("Start a Viseca scenario run for a policy; the worker answers its purchases"),
                        new Tag().name("Status").description("What is configured and running")));
    }

    /** Make the generated schemas use our snake_case naming, like the real JSON. */
    @Bean
    ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }
}
