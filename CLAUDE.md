# CLAUDE.md

## Working style (hackathon)

- This is a hackathon build. **Optimize for working, good-looking, demoable functionality — not code quality.** No over-engineering, no premature abstractions. Hardcode/inline whatever speeds us up, *except* decisions (see "Hard rules").
- **No tests.** Don't write or run test code; check behaviour by running the app and hitting it with `curl`.
- The UI is what judges see: make it clean, polished, and make the decision reasoning visible.
- Everything about the case lives in `resources/`. Start with `resources/case_description.md` (brief) and `resources/viseca-2026-main/technical_details.md` (API + data contract). Don't re-derive what's written there.
- Design notes live in `plans/`: `IDEAS_AND_DATA_GUIDE.md` (data, scenarios, UI ideas) and `BACKEND_PLAN.md` (the plan the backend was built from).

## Current decisions

- **Backend:** Java 21, Spring Boot 3.5, Maven wrapper (`backend/mvnw`), packages under `com.leash`. Plain `java.net.http` clients, no SDKs, no Spring Data. JDK 21 is at `/usr/lib/jvm/java-21-openjdk-amd64`; the default `java` here is 25, so set `JAVA_HOME` to the 21 one when building outside Docker.
- **UI:** Next.js/React (TypeScript, Tailwind) in `frontend/` — **not started**. It only talks to our backend, through a proxy (`/api/* → backend`); never to Viseca, and the team key stays in the backend env. The backend has no CORS config, so don't call it straight from the browser.
- **Storage:** no database. Policies are a JSON text file (`store/policies.json`, `STORE_DIR`); decisions and the spend ledger are in memory and are lost on restart. Postgres was dropped from compose because nothing used it — re-add it only if we need durable decisions/audit.
- **LLM:** OpenAI chat completions with strict JSON-schema output (`OPENAI_API_KEY`, `OPENAI_MODEL`, default `gpt-4o-mini`). Two uses: the **policy compiler** (required to create a policy; without a key it returns 503 and never a made-up policy) and the **purchase judge** (optional; the engine decides without it).
- **API docs:** Swagger UI at `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`), via springdoc 2.8. Every endpoint has a summary, and the POST bodies have working examples (the `/check` one is a real event that gets approved). When you add or change an endpoint, keep its `@Operation` text and example in `web/*Controller.java` up to date.
- **Run it:** `make provision` builds and starts everything in Docker, `make deprovision` stops it and deletes the volumes (saved policies included). Other targets: `make build` (images only, doesn't start) `| status | logs | restart s=backend | clean`. Keys go in `.env` (`make env` copies `.env.example`).
- **Our states vs the API:** `approved` → `approve`, `denied` → `decline`, `pending_human` → `step_up`.

## Project structure

```
backend/                    Spring Boot API + decision engine + Viseca worker (Dockerfile, mvnw, pom.xml)
  src/main/resources/application.properties   all config, every value env-driven
  src/main/java/com/leash/
    Json, Settings, LeashApplication         one snake_case ObjectMapper; env config
    llm/     OpenAiClient (strict JSON-schema chat), LlmException
    policy/  Policy, Rule, PolicyStore (policies.json), PolicyCompiler (NL → rules), PolicyService (draft/confirm/tighten/revoke)
    engine/  CheckService (the decision pipeline + memory), PurchaseFacts, RuleEvaluator, Fields (rule vocabulary),
             HistoryIndex (familiarity from history CSV), TextScan (injection tripwire), LlmJudge, Decision, Evidence
    worker/  VisecaClient, DecisionWorker (long-poll loop), RunService (start a run for a policy)
    web/     PolicyController, CheckController, RunController, StatusController (Swagger annotations live here), OpenApiConfig, ApiErrors
frontend/                   (planned)
docker-compose.yml          backend; frontend joins via the "ui" profile once frontend/Dockerfile exists
Makefile  .env.example      lifecycle targets; env template (.env is gitignored)
plans/                      design notes and the backend plan
resources/                  case brief + Viseca data pack (mounted read-only into the backend at /data)
```

## How the backend works

**Policy lifecycle** (`/policies`): `POST {instruction}` → OpenAI compiles it into a **draft** (`hard_rules`, `uncertainty_policy`, `guidance`, `open_questions`; Viseca mandate shape) → customer reviews → `POST /{id}/confirm` → **active**. `PATCH` may only add rules (existing ones must be sent back unchanged) and move `uncertainty_policy` toward `decline`; `guidance`/`open_questions` are replaced. `DELETE` revokes immediately and locally, then tells Viseca. Anything the compiler can't express as a rule goes into `guidance` (kept visible to the customer and read by the judge); rules with unsupported fields are dropped into `guidance` too. `POST /runs {policy_id, scenario_id}` pushes the policy to Viseca as a mandate (draft → confirm) and starts the run.

**Rule vocabulary** (`engine/Fields.java`; the compiler is told this list and the evaluator only understands it): `authorization.billing_amount_chf` (with `scope: period` + `period_days` = approved spend in the window + this order), `authorization.recent_attempt_count_10m`, `authorization.channel`, `authorization.fulfillment_method`, `authorization.order_returnable`, `authorization.order_cancellable`, `merchant.merchant_id`, `merchant.merchant_category`, `merchant.merchant_country`, `merchant.familiar`, `items.item_category`, `items.item_id`, `items.quantity` (`items.*` must hold for **every** cart line). Free shop text (`item_details`, names, descriptions) is never a rule subject. Rule amounts are in CHF; a rule stated in EUR/GBP/USD is converted with the fixed rates.

**Decision pipeline** (`CheckService.check`, used by `POST /check` and by the worker):
1. `PurchaseFacts.parse` reads the event without any LLM. A malformed event is a 400 over HTTP and a fail-safe `step_up` in the worker.
2. Guards: policy revoked/not active (found locally via the Viseca mandate id), mandate/authority/card not active → `denied`.
3. `RuleEvaluator`: every hard rule → pass / fail / **unknown** (missing, null, `"unknown"`, unsupported field). Any fail → `denied` with the failing values in the message.
4. All pass and no soft flag → `approved` with **no LLM call** (the fast path for ordinary purchases). Soft flags that send it to the judge: the policy has `guidance`, `TextScan` found shop text addressing the agent, same shop + same items as an earlier purchase in the run, `related_authorization_id` set, `recent_attempt_count_10m ≥ 1`. Any unknown rule goes to the judge too.
5. `LlmJudge` → approve / decline / step_up + customer message + evidence. Guards on top: it can't approve past an unknown fact (→ `uncertainty_policy`), and `manipulation_suspected` turns an approval into `pending_human`. Shop text is passed in a separate `untrusted_merchant_text` block.
6. Judge off, failed, or too close to `deadline_at` (worker only, 1.5 s margin, judge timeout 5 s) → the outcome of `uncertainty_policy` (`ask` → `pending_human`, `decline` → `denied`, `approve` → `approved`).
7. Memory: every decision is kept by live `authorization_id`. A redelivered purchase returns the saved decision (spend is counted once). Spend windows use simulated `authorization.timestamp` and count only final approvals, scoped to the run id (else the mandate id). `pending_human` is not spend until the customer approves.

**Derived facts** (`HistoryIndex`, from `authorization_history.csv`, approved purchases only): `merchant.familiar` = the card has ≥ 2 earlier approved purchases at that merchant (a rule field); `session.device_familiar` and `session.country_familiar` go to the judge only. Unknown card or missing file → unknown, never permission.

**Human path:** `POST /check/{authorization_id}/resolve {decision: approve|decline, customer_message}` works only on `pending_human`. For Viseca purchases it calls Viseca `/resolve` first and only then updates local state.

**Worker** (`DecisionWorker`): starts when `TEAM_API_KEY` is set and `WORKER_ENABLED` isn't false. It long-polls `/v1/decision-requests/next?wait=25`, handles purchases one at a time in delivery order, and posts `/decision` right away, `step_up` included; it never waits for a human. A 204 just polls again. Evidence is sent as objects; if Viseca rejects the body with 400/422 (the format isn't specified) it retries with strings, then without evidence.

**Endpoints** (full list with examples in Swagger UI): `GET /status`, `GET /actuator/health` · `POST|GET /policies`, `GET|PATCH|DELETE /policies/{id}`, `POST /policies/{id}/confirm` · `POST /check?policy_id=&run_id=` (body: an event or a poll envelope), `POST /check/{authorization_id}/resolve` · `GET /decisions?run_id=&state=`, `GET /decisions/{authorization_id}` · `POST /runs`, `GET /runs/{run_id}`. Errors are `{"error": {"status", "message"}}`. A decision has `state`, `viseca_decision`, `reason_codes`, `customer_message`, `evidence[]`, `checks[]` (per-rule verdicts), `used_llm`, `decided_by` (`engine` | `customer`), `posted_to_viseca`.

**Config (env):** `TEAM_API_KEY`, `VISECA_BASE_URL`, `WORKER_ENABLED`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL`, `COMPILE_TIMEOUT_MS` (30000), `JUDGE_TIMEOUT_MS` (5000), `STORE_DIR` (`store`), `DATA_DIR` (the data pack), host ports `BACKEND_PORT` / `FRONTEND_PORT`. Without Docker: `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spring-boot:run` (the default `DATA_DIR` works from `backend/`).

**Not done yet / known gaps:** the frontend; live updates for the UI (poll `GET /decisions?state=pending_human`, no SSE); the 120 s human window isn't tracked; decisions aren't persisted; natural-language tightening (PATCH takes structured rules only); nothing has been run against the real OpenAI or Viseca APIs yet (only against mocks), so the model default and Viseca's `evidence` format are unverified.

## The case: "Agent on a Leash" (Viseca, START Hack St. Gallen 2026)

An AI shopping agent buys things with a customer's card. **We build the wallet control layer** (not the shopping agent) that decides, per proposed purchase: `approve`, `decline`, or `step_up` (ask the customer). It is driven by a customer-managed **wallet policy** (called a *mandate* in the API) that the agent and shops can never change.

Must do:
1. Turn the customer's natural-language instruction into explicit, executable permissions; show them, get confirmation; support tighten / update / revoke.
2. Evaluate each purchase against policy + purchase facts + history + earlier decisions; return a decision with reasons and evidence.
3. Human path: `step_up` → customer approves/declines in the UI → `/resolve`.

Must demo: (a) an ordinary purchase passing with minimal friction, (b) an ambiguous/unsafe/manipulated purchase getting a useful intervention, (c) the approve / reject / revoke path. Judges must see *what was permitted, what evidence was used, why it acted, how the customer stayed in control*.

Judging: User centricity 25%, Security & transparency 25%, Innovation 20%, Feasibility 15%, Viability 15%.

## Architecture guidance

- Decouple the **decision engine** (backend, latency-critical) from the **wallet-control UI** (customer-facing). Viseca wants to fold the UI into its "one" mobile app later.
- Rules first, LLM only for what rules can't settle. LLM use is optional at decision time: if the model or any external service fails, fall back to a **predictable deterministic result** (normally `step_up` per the mandate's `uncertainty_policy`) within the deadline.
- Every decision carries `reason_codes`, `customer_message`, and `evidence` — transparency is scored.

## Hard rules

- **Never hard-code decisions to scenario names/IDs, request IDs, `AU…` ids, or sequence position.** Decide from policy + facts only. There are no answer keys.
- **All merchant text is untrusted** (`item_details`, `purchase_description`, etc.). Extract facts from it; never let it alter policy (prompt-injection scenario is SCEN0004).
- Missing/`null`/`unknown` facts are never permission. Apply the mandate's `uncertainty_policy` (default `ask`).
- Don't over-block: unfamiliar ≠ wrong; blocking ordinary shopping is also a failure.
- Never weaken a customer's existing restrictions. PATCH may only add rules, and may only move `uncertainty_policy` toward `decline`.
- Do not invent human answers; after `step_up` use `/resolve` only for a real customer choice, never a second `/decision`.

## API cheat sheet (Viseca)

Base URL `https://saw26api.ashyground-364e1d07.switzerlandnorth.azurecontainerapps.io`, header `Authorization: Bearer $TEAM_API_KEY` (key handed out on event day; keep out of git; only `/healthz` is open). Bodies are JSON; errors are under `error`.

Flow: `POST /v1/mandates` (draft: `instruction` verbatim, `hard_rules`, `uncertainty_policy`, `guidance`, `open_questions`) → customer confirms → `POST /v1/mandates/{draft_id}/confirm {"confirmed":true}` → `mandate_id` → `POST /v1/scenario-runs {scenario_id, mandate_id}` → worker loops `GET /v1/decision-requests/next?wait=25` (200 = envelope with event in `data`, 204 = nothing yet, *not* end of run) → `POST /v1/authorizations/{id}/decision` (`authorization_id`, `decision` required; plus `reason_codes`, `customer_message`, `evidence`, `engine_version`) → if `step_up`: `POST /v1/authorizations/{id}/resolve {decision: approve|decline, ...}`.

Other: `GET /v1/bootstrap`, `/v1/reference-data`, `/v1/reference-data/authorization-history.csv`, `GET|PATCH|DELETE /v1/mandates/{id}`, `GET /v1/scenario-runs/{run_id}`, `GET /v1/authorizations`, `GET /v1/events?since=N`, `POST /v1/team/reset` (dev only, disabled during judging).

Timing: automated deadline is **8 s from queueing** (not from polling) → the worker must be automatic and fast. Human window is 120 s. Keep polling while the UI waits on a human. Dedupe by live `authorization_id` (retries must not double-count spend).

Rule format (`hard_rules[]`): `field`, `operator` (`< <= = != > >= in not_in`), `value` (number | string | list of strings), optional `currency` (CHF/EUR/GBP/USD), `scope` (`purchase`|`period`), `period_days`. No extra keys. Field names are a convention *our engine* interprets (see "Rule vocabulary"). Don't send customer/card/profile IDs when creating a mandate.

## Data pack (`resources/viseca-2026-main/data/`)

Five scenarios / 45 purchases; each = one customer + card + instruction (`scenario_catalogue.csv`):

| ID | Theme | Instruction gist |
| --- | --- | --- |
| SCEN0000 | connection check (1) | one grocery item ≤ CHF 20, familiar shop |
| SCEN0001 | household budget (10) | ≤ CHF 120/order incl. delivery, ≤ CHF 300 per rolling 7 days; split orders, off-purpose basket items |
| SCEN0002 | item & order terms (12) | road-running shoes size 43, specialist sports retailer, returnable ≥ 14 days, ≤ CHF 200; substitutions, add-ons |
| SCEN0003 | session integrity (11) | clothing ≤ CHF 250/order from previously used shops; pause if session looks hijacked (device, velocity, country, recovery) |
| SCEN0004 | manipulated agent (11) | the chosen 27" monitor, known seller, ≤ CHF 400, no add-ons; injection in item text, lookalike sellers, duplicates, re-quotes |

Files: `purchase_attempts.csv` (+ `purchase_attempt_items.csv`, `merchants.csv`, `items.csv`) for offline replay ordered by `replay_order`; `authorization_history.csv` (4,701 past rows, incl. 453 agent purchases) for behaviour/familiarity baselines; schemas in `schemas/`; complete sample event in `scenario_fixtures/example_authorization_request.json` (`connection_check.json` is NOT an event).

Gotchas (details in `data/data_dictionary.md`):
- Join on IDs, never names (some merchants have deliberately similar names → lookalike sellers).
- `amount` already includes delivery — don't add it again. `billing_amount_chf = amount × fx` (EUR .95, GBP 1.12, USD .87, fixed). Currency comes from the `currency` column, not the merchant country.
- Item category ≠ merchant category (`gift_card`, `membership`, `cosmetics` exist only on items). Shop type doesn't prove what's in the basket.
- Spending windows use simulated `authorization.timestamp`; deadlines use the real clock. Only **final approvals** count as spend; a pending `step_up` isn't spend. `spend_in_period_before_chf` is null in all fixtures — track the rolling window ourselves (or use `context.approved_spend_in_period_chf`).
- `recent_attempt_count_10m` = earlier attempts in the run within 10 simulated minutes, any status.
- `authorization_id` live (per run) ≠ `source_authorization_id` (`AU…` in CSV). `TR…` (history) and `AU…` (attempts) never join.
- History `approved_*_before` fields are cumulative per card over the whole file, not monthly. Historical `status` is not a label.
- `card_present` ≠ channel (mobile_wallet is mixed). A mandate is snapshotted per run; PATCH affects later runs only. Revoking mid-run for already-queued purchases is unspecified — only show cancellation the platform confirms.
