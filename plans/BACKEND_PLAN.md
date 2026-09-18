# Backend plan (no UI)

Java 21 Spring Boot in `backend/`, using the Maven wrapper. The store is a JSON file, so Postgres isn't needed yet.

## 1. Policy creation

1. **`POST /policies`** takes `{ "instruction": "Buy me black running shoes for up to CHF 200." }`.
2. **`PolicyCompiler`** sends the sentence to OpenAI with a JSON-schema structured output. The output is the Viseca mandate shape, so we can `POST` it to `/v1/mandates` unchanged:
   - `hard_rules[]`: `field`, `operator`, `value`, and optionally `currency` and `scope`.
   - `uncertainty_policy`: defaults to `ask`.
   - `guidance`: soft preferences, such as the colour black.
   - `open_questions`.
3. **`PolicyStore`** writes the result to `data/policies.json` as a draft.
4. **`POST /policies/{id}/confirm`** marks the policy active. **`GET`** returns it, **`PATCH`** can only add rules or move `uncertainty_policy` toward decline, and **`DELETE`** revokes it.

## 2. Rule checking

**`POST /check`** takes a purchase proposal, in the shape of the Viseca event.

1. **Parse without an LLM.** Map the event JSON to a plain `PurchaseFacts` object, using `billing_amount_chf`, category, merchant id and so on. Nothing is sent to an LLM at this step.
2. **Deterministic check.** `RuleEvaluator` runs every hard rule against the facts and returns `PASS`, `FAIL` or `UNKNOWN` per rule:
   - Every rule passes: **approved**. No LLM is called, which keeps ordinary purchases fast.
   - Any rule fails: **denied**.
   - Any rule is unknown, or there is a soft-guidance question (colour, item match): go to step 3.
3. **`LlmJudge`.** Send the policy, the facts, and the merchant text to OpenAI:
   - The merchant text goes in as quoted, untrusted data so it can't change the policy.
   - The model returns a structured `{decision, reason_codes, customer_message}`.
   - On timeout, error, or an unclear answer, the result is `uncertainty_policy`, normally **pending**. This is the deterministic fallback.
4. **Response:** `approved`, `denied` or `pending_human`, each with `reason_codes`, `customer_message` and `evidence`.

| Our state | Viseca decision |
| --- | --- |
| `approved` | `approve` |
| `denied` | `decline` |
| `pending_human` | `step_up` |

Once the customer answers a `pending_human`, `POST /check/{id}/resolve {approve|decline}` records their choice.

## 3. Viseca worker (last)

A background loop polls `/v1/decision-requests/next`, calls the check from section 2, and posts `/decision`. For `step_up` it waits for the customer's `/resolve`. It also dedupes on `authorization_id` and tracks the rolling spend window from final approvals only.

## Build order

1. Scaffold the Spring Boot project and the `OpenAiClient` wrapper, with a `curl` test that turns one sentence into JSON.
2. Policy endpoints and file store.
3. `PurchaseFacts` parser, validated against `authorization_event.schema.json`, plus `RuleEvaluator`.
4. `LlmJudge` and the fallback.
5. Worker, then a replay of SCEN0000 and SCEN0001 to check the flow.

## Differences from CLAUDE.md

- **Keys:** the LLM is OpenAI, so the key is `OPENAI_API_KEY` (the first `.env.example` had `ANTHROPIC_API_KEY`; renamed).
- **Storage:** a JSON file replaces Postgres for now, which is fine for the demo.
