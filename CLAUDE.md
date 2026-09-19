# CLAUDE.md

## Working style (hackathon)

- This is a hackathon build. **Optimize for working, good-looking, demoable functionality — not code quality.** No over-engineering, no premature abstractions. Hardcode/inline whatever speeds us up, *except* decisions (see "Hard rules").
- **No tests.** Don't write or run test code; check behaviour by running the app and hitting it with `curl`.
- The UI is what judges see: make it clean, polished, and make the decision reasoning visible.
- Everything about the case lives in `resources/`. Start with `resources/case_description.md` (brief) and `resources/viseca-2026-main/technical_details.md` (API + data contract). Don't re-derive what's written there.
- Design notes live in `plans/`: `IDEAS_AND_DATA_GUIDE.md` (data, scenarios, UI ideas), `BACKEND_PLAN.md` (the plan the backend was built from) and `EXAMPLE_POLICY_CURLS.md` (fifteen ready-to-run policy curls with what each compiles to) and `EXAMPLE_CHECK_CURLS.md` (17 check cases with expected outcomes; all pass against the real model), and `DEMO_CURLS.md` (the three required demos as copy-paste curls, run in order and verified against the real model), and `REQUIREMENTS_COVERAGE.md` (how each of the four brief requirements — spend, shady merchants, intent, prompt injection — is tackled, with tests and known gaps; update it when behaviour changes).

## Current decisions

- **Backend:** Java 21, Spring Boot 3.5, Maven wrapper (`backend/mvnw`), packages under `com.leash`. Plain `java.net.http` clients, no SDKs, no Spring Data. JDK 21 is at `/usr/lib/jvm/java-21-openjdk-amd64`; the default `java` here is 25, so set `JAVA_HOME` to the 21 one when building outside Docker.
- **UI:** Next.js 14 (App Router, TypeScript, Tailwind 3) in `frontend/`, minimal and responsive (bottom tab bar on phones, top nav on desktop, light/dark by system). Four screens: **Policies** (say it in words → "what I understood": rules checked exactly vs judged by AI, tighten to decline-when-unsure, revoke), **Try** (one-click presets + a purchase form, playing the agent), **Inbox** (paused purchases, Approve/Decline → `/resolve`, polled every 4 s), **Activity** (all decisions, expandable evidence and per-rule checks). It only talks to `/api/*`, which `next.config.mjs` proxies to the backend (`BACKEND_URL`, fixed at build time: `http://backend:8080` in compose, `http://localhost:8080` for `cd frontend && npm run dev`); never to Viseca, no key in the browser. `make provision` starts it on `FRONTEND_PORT` (3000); after UI changes `make restart s=frontend`.
- **Storage:** no database. Policies are a JSON text file (`store/policies.json`, `STORE_DIR`); decisions and the spend ledger are in memory and are lost on restart. Postgres was dropped from compose because nothing used it — re-add it only if we need durable decisions/audit.
- **LLM:** OpenAI chat completions with strict JSON-schema output (`OPENAI_API_KEY`, `OPENAI_MODEL`, default `gpt-4.1`; tested with the real API: `gpt-4o-mini` mixed up requirements between policies, `gpt-4.1` and `gpt-4.1-mini` judged each policy correctly in ~2 s), `temperature: 0` for repeatable answers (retried without it if a model rejects it). Two uses: the **policy compiler** (required to create a policy; without a key it returns 503 and never a made-up policy) and the **purchase judge** (optional; the engine decides without it).
- **API docs:** Swagger UI at `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`), via springdoc 2.8. Every endpoint has a summary, and the POST bodies have working examples (the `/check` one is a real event that gets approved). When you add or change an endpoint, keep its `@Operation` text and example in `web/*Controller.java` up to date.
- **Run it:** `make provision` builds and starts everything in Docker, `make deprovision` stops it and deletes the volumes (saved policies included). Other targets: `make build` (images only, doesn't start) `| status | logs | restart s=backend | example-policies | check-cases | clean` (`example-policies` creates the 15 policies of `plans/EXAMPLE_POLICY_CURLS.md` and skips ones that already exist; `check-cases` runs `scripts/run-check-cases.sh`: 100 purchases against those policies in 22 groups (each group its own run id, `JOBS`=4 groups at a time; `ONLY="currency shoes"` runs just those groups), one line per case as it is decided, exit 1 on any failure, run-scoped ids so it can be repeated). Keys go in `.env` (`make env` copies `.env.example`).
- **Local model (no OpenAI):** `make provision_local [LOCAL_MODEL=qwen2.5:14b]` starts an Ollama container (compose profile `local`, volume `ollama`), pulls the model and restarts the backend with `OPENAI_BASE_URL=http://ollama:11434/v1`, a dummy key, the model name and long timeouts (compile 180 s, judge 90 s). Claude models can't run locally (no public weights), so it serves an open model (default `qwen2.5:7b`). The backend code is unchanged: Ollama speaks the OpenAI API incl. `json_schema` output. Expect weaker judgements and slower answers than `gpt-4.1` (the earlier `gpt-4o-mini` failures show what a small model does with several policies), and too slow for Viseca's 8 s deadline, so the worker path falls back to `uncertainty_policy`. Only the `local` profile starts Ollama: plain `make provision` never does (and doesn't stop a running one either; `make deprovision` removes it, `--profile local` is on its `down`). `make restart s=backend` goes back to the `.env` (OpenAI) settings. Untested with a real model pull as of writing.
- **No draft/confirm step (user decision):** `POST /policies` compiles the sentence and the policy is **active immediately**. The case brief asks for customer confirmation, so the UI should show what was understood right after creation and offer tighten (`PATCH`) and revoke (`DELETE`). Drafts left over in an old `policies.json` become active when the backend starts. (Viseca's own mandate draft → confirm still happens internally when a run starts.)
- **Our states vs the API:** `approved` → `approve`, `denied` → `decline`, `pending_human` → `step_up`.

## Project structure

```
backend/                    Spring Boot API + decision engine + Viseca worker (Dockerfile, mvnw, pom.xml)
  src/main/resources/application.properties   all config, every value env-driven
  src/main/java/com/leash/
    Json, Settings, LeashApplication         one snake_case ObjectMapper; env config
    llm/     OpenAiClient (strict JSON-schema chat), LlmException
    policy/  Policy, Rule, PolicyStore (policies.json), PolicyCompiler (NL → rules), PolicyService (create/tighten/revoke)
    engine/  CheckService (the decision pipeline + memory), PurchaseFacts, RuleEvaluator, Fields (rule vocabulary),
             HistoryIndex (familiarity from history CSV), SellerCheck (shady-seller check), TextScan (injection tripwire), LlmJudge, Decision, Evidence
    worker/  VisecaClient, DecisionWorker (long-poll loop), RunService (start a run for a policy)
    web/     PolicyController, CheckController, RunController, StatusController (Swagger annotations live here), OpenApiConfig, ApiErrors
frontend/                   Next.js UI: app/ (pages: policies, try, inbox, activity), components/ (Nav, DecisionCard, PolicyCard), lib/ (api client, rule wording, purchase presets), Dockerfile
docker-compose.yml          backend; frontend joins via the "ui" profile once frontend/Dockerfile exists
Makefile  .env.example      lifecycle targets; env template (.env is gitignored)
scripts/                    create-example-policies.sh (reads the sentences out of plans/EXAMPLE_POLICY_CURLS.md), run-check-cases.sh (100 purchases → live pass/fail)
plans/                      design notes and the backend plan
resources/                  case brief + Viseca data pack (mounted read-only into the backend at /data)
```

## How the backend works

**Policy lifecycle** (`/policies`): `POST {instruction}` → OpenAI compiles it into an **active** policy (`hard_rules`, `uncertainty_policy`, `guidance`, `open_questions`; Viseca mandate shape), no confirmation step. `PATCH` may only add rules (existing ones must be sent back unchanged) and move `uncertainty_policy` toward `decline`; `guidance`/`open_questions` are replaced. `DELETE` revokes immediately and locally, then tells Viseca. The compiler prompt forbids inventing ids/values and guessing a category for a specific product (running shoes are `sporting_goods` in the data, a model guessed `clothing`); every requirement must land as a rule or a guidance line (colour, size, product type, return window → guidance). Anything the compiler can't express as a rule goes into `guidance` (shown in the created policy and read by the judge); rules with unsupported fields are dropped into `guidance` too. `POST /runs {policy_id, scenario_id}` pushes the policy to Viseca as a mandate (Viseca's draft → confirm) and starts the run.

**Rule vocabulary** (`engine/Fields.java`; the compiler is told this list and the evaluator only understands it): `authorization.billing_amount_chf` (with `scope: period` + `period_days` = approved spend in the window + this order), `authorization.recent_attempt_count_10m`, `authorization.channel`, `authorization.fulfillment_method`, `authorization.order_returnable`, `authorization.order_cancellable`, `merchant.merchant_category`, `merchant.merchant_country`, `merchant.familiar`, `items.item_category`, `items.quantity` (`items.*` must hold for **every** cart line). Ids are deliberately not in the vocabulary: a customer never says them, so the model would invent them. Free shop text (`item_details`, names, descriptions) is never a rule subject. Rule amounts are in CHF; a rule stated in EUR/GBP/USD is converted with the fixed rates (`Fx`), and so is a purchase quoted in them.

**Which policy decides** (`CheckService.decideFor`): `policy_id` if given → that policy. Else, if the event carries a `mandate` (every Viseca event does) → that snapshot, the worker path: rules first, the model only for what they can't settle. Else (**`POST /check` with no policy and no mandate**) → `decideAuto`: **all active policies** (`store/policies.json`; revoked ones are excluded) plus the purchase go to OpenAI in **one call**, and its answer is `approved` / `denied` / `pending_human` plus the `policy_id` that allows it. The model judges each policy separately as `satisfied` / `violated` / `unverified` (`assessments`, kept as evidence) and sees each policy's exact rule results; any satisfied policy → approve, else any unverified one → the uncertainty_policy (a missing fact never means decline while a policy could still apply), else decline. A `pending_human` from the model is turned into `denied` (`no_policy_could_apply`) when every policy already failed a hard rule or was found violated. The engine only vetoes: it still evaluates every policy's hard rules, and the model can't approve under a policy whose rule failed (`model_overruled_by_rules`), under an unknown fact, on `manipulation_suspected`, or without naming a policy. Model off/failed → `autoFallback`: a clean rule pass approves, otherwise the strictest `uncertainty_policy`; if every policy's rules fail → `denied` (`no_matching_policy`, each policy's reason). No active policy → `no_active_policy` (not remembered, so the same `authorization_id` works once a policy exists). Spend windows are counted per policy.

**Decision pipeline** (`CheckService.prepare` + `judgeAmong`, per policy or per candidate set; used by `POST /check` and by the worker):
1. `PurchaseFacts.parse` reads the event without any LLM. The CHF amount is `billing_amount_chf`, or `amount` × the fixed rate (`Fx`, in memory; loaded once at startup by `FxRates`: Viseca `/v1/reference-data` if `TEAM_API_KEY` is set, else the mock exchange-API file `mock/exchange-rates.json` (labelled MOCK; `FX_RATES_FILE` overrides), else the data pack `fx_rates.csv`; nothing hardcoded, `/status` shows the rates and their source; no rate = unknown, never permission) when only `amount` + `currency` are sent (EUR 205 = CHF 194.75 fits a CHF 200 limit); if both are sent and differ by more than a cent the higher one is used, evidence `source: currency` says so and an approval becomes `pending_human` (`amount_mismatch`); a currency without a rate and no CHF amount is a 400. A malformed event is a 400 over HTTP and a fail-safe `step_up` in the worker.
2. Guards: policy revoked/not active (found locally via the Viseca mandate id), mandate/authority/card not active → `denied`.
3. `RuleEvaluator`: every hard rule → pass / fail / **unknown** (missing, null, `"unknown"`, unsupported field). Any fail → `denied` with the failing values in the message.
4. All pass and no soft flag → `approved` with **no LLM call** (the fast path for ordinary purchases). Soft flags that send it to the judge: the policy has `guidance`, `TextScan` found shop text addressing the agent, same shop + same items as an earlier purchase in the run, `related_authorization_id` set, `recent_attempt_count_10m ≥ 1`. Any unknown rule goes to the judge too.
5. `LlmJudge` → approve / decline / step_up + `policy_id` + customer message + evidence. Guards on top: it can't approve past an unknown fact (→ `uncertainty_policy`), it can't approve without naming one of the candidate policies (→ `pending_human`), and `manipulation_suspected` turns an approval into `pending_human`. Shop text is passed in a separate `untrusted_merchant_text` block.
6. Judge off, failed, or too close to `deadline_at` (worker only, 1.5 s margin, judge timeout 15 s, cut to the deadline on the Viseca path) → the outcome of `uncertainty_policy` (`ask` → `pending_human`, `decline` → `denied`, `approve` → `approved`); with several candidate policies, the strictest one wins.
7. Memory: every decision is kept by live `authorization_id`. A redelivered purchase returns the saved decision (spend is counted once). Spend windows use simulated `authorization.timestamp` and count only final approvals, scoped to the run id (else the mandate id). `pending_human` is not spend until the customer approves.
8. Seller check (`SellerCheck`, always on, no LLM, applied to every decision in `CheckService.check` whatever the policy says): from `merchants.csv` + the whole history file. **Lookalike**: the seller's name (as the event states it) is ≥ 85% the same as another catalogue shop that has ≥ 5 approved purchases on the platform and more than the seller, and the card has not bought at the seller ≥ 2 times → an *approval* becomes `pending_human` (`lookalike_seller`, message names the imitated shop; PixelHarbour vs PixelHarbor). A denial stays denied (the code and a note are added). **Platform history**: approved purchases / distinct cards at the seller over all cards (0 = `none`, < 5 = `thin`); only evidence (`source: seller_check`) and the judge's `seller_check` context, never blocks on its own (unfamiliar ≠ wrong; the shoes/groceries cases use an unknown shop id). No history file → nothing is said. Thresholds are constants in `SellerCheck`. Not built (decided against): a history decline-rate score (the data dictionary says historical `status` is not a label), catalogue-consistency check of the event's merchant fields, country-vs-card check.

**Derived facts** (`HistoryIndex`, from `authorization_history.csv`, approved purchases only): `merchant.familiar` = the card has ≥ 2 earlier approved purchases at that merchant (a rule field); `session.device_familiar` and `session.country_familiar` go to the judge only. Unknown card or missing file → unknown, never permission.

**Human path:** `POST /check/{authorization_id}/resolve {decision: approve|decline, customer_message}` works only on `pending_human`. For Viseca purchases it calls Viseca `/resolve` first and only then updates local state.

**Worker** (`DecisionWorker`): starts when `TEAM_API_KEY` is set and `WORKER_ENABLED` isn't false. It long-polls `/v1/decision-requests/next?wait=25`, handles purchases one at a time in delivery order, and posts `/decision` right away, `step_up` included; it never waits for a human. A 204 just polls again. Evidence is sent as objects; if Viseca rejects the body with 400/422 (the format isn't specified) it retries with strings, then without evidence.

**Endpoints** (full list with examples in Swagger UI): `GET /status`, `GET /actuator/health` · `POST|GET /policies`, `GET|PATCH|DELETE /policies/{id}` · `POST /check?policy_id=&run_id=` (body: an event or a poll envelope; no `policy_id` and no `mandate` = try all active policies), `POST /check/{authorization_id}/resolve` · `GET /decisions?run_id=&state=`, `GET /decisions/{authorization_id}` · `POST /runs`, `GET /runs/{run_id}`. Errors are `{"error": {"status", "message"}}`. A decision has `state`, `viseca_decision`, `reason_codes`, `customer_message`, `evidence[]`, `checks[]` (per-rule verdicts), `used_llm`, `decided_by` (`engine` | `customer`), `policy_id` (the policy that decided), `posted_to_viseca`.

**Config (env):** `TEAM_API_KEY`, `VISECA_BASE_URL`, `WORKER_ENABLED`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL`, `COMPILE_TIMEOUT_MS` (30000), `JUDGE_TIMEOUT_MS` (15000; on the Viseca path it is still cut to fit the 8 s deadline), `STORE_DIR` (`store`), `DATA_DIR` (the data pack), host ports `BACKEND_PORT` / `FRONTEND_PORT`. Without Docker (IntelliJ or `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw spring-boot:run`): the backend reads the repo's `.env` itself (`spring.config.import` in `application.properties`), so no IntelliJ env setup is needed; a real environment variable wins over `.env`. Run it with `backend/` as the working directory (the default `DATA_DIR` is relative to it).

**Not done yet / known gaps:** live updates for the UI (poll `GET /decisions?state=pending_human`, no SSE); the 120 s human window isn't tracked; decisions aren't persisted; natural-language tightening (PATCH takes structured rules only); the compiler and the judge have been run against the real OpenAI API, but nothing has been run against the real Viseca API yet (only a mock), so Viseca's `evidence` format is unverified.

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
