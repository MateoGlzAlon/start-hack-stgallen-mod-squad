# How each requirement is tackled

The case: an AI shopping assistant buys with your card. You say *"Buy me black running shoes for up to CHF 200."* How do you stay in control? The brief asks for four things. This document says, for each one, what the system does, where it lives in the code, how to see it, and what it does **not** cover.

## The idea in one paragraph

The customer's sentence becomes a **policy**: exact rules (amount, shop type, country, item category, ...) plus plain-language guidance for what a rule can't express (colour, size, "no extras"). Every purchase the agent proposes is checked against the policy **rules first, in code**. A language model is only asked about what the rules can't settle, and even then it can only *veto or ask*, never override a failed rule. Every decision is `approve`, `decline` or `step_up` (ask the customer), and it carries reason codes, a plain message for the customer, the evidence used and the result of every rule. The agent and the shops can never change the policy; only the customer can.

```
purchase
  → read the facts (no LLM)
  → guards: policy revoked? card or authority not active?           → decline
  → hard rules, each pass / fail / unknown                          → any fail: decline
  → seller check (always on)
  → everything passed and nothing looks odd                         → approve (no LLM, fast)
  → otherwise the judge (LLM) looks at the policy guidance and the shop's text
  → decision: approve / decline / step_up, with reasons and evidence
  → step_up: the customer answers, and only the customer can
```

If the model is down or too slow, the policy's `uncertainty_policy` decides (default: ask the customer). A missing fact is never permission.

---

## 1. Doesn't spend too much

| What | How |
| --- | --- |
| Per-order limit | Rule `billing_amount_chf <= N` on the total charged (delivery included, not added twice). The limit is inclusive: exactly CHF 200 passes, CHF 200.01 is declined. |
| Budget over time | Rule with `scope: period` and `period_days`, e.g. "CHF 300 across any seven days". It adds up **final approvals** in that window plus this order. Windows use the purchase's own date, not the server clock. |
| Only real spend counts | A purchase waiting for the customer is not spend. A redelivered purchase is remembered by its id, so spend is never counted twice. Spend is counted per policy. |
| Other currencies | Both sides are converted with exchange rates loaded into memory at startup (Viseca reference data, else a mock exchange-rate file, else the data pack; EUR 0.95, GBP 1.12, USD 0.87 in all three; `/status` shows them and their source). A currency without a rate is unknown, never permission. A limit stated in EUR is converted, and a price quoted in EUR is too: EUR 205 is CHF 194.75 and fits a CHF 200 limit, while EUR 211 (CHF 200.45) does not. The conversion is shown as evidence. If a request's CHF amount disagrees with price × rate, the higher amount is used for every check and the customer is asked (`amount_mismatch`), so a request can't under-report. Tested by the `currency` group of `make check-cases`. |
| Quantity and velocity | Rules on `items.quantity` and on `recent_attempt_count_10m`. Other attempts in the last 10 minutes send the purchase to the judge. |
| Splitting an order to dodge a limit | Same shop and same items as an earlier purchase in the run is flagged as a possible duplicate, and the judge is told to decline a split-order. |
| Blocked card / revoked authority | Declined before anything else. |

Code: `RuleEvaluator`, `CheckService` (spend windows, `approvedSpend`), `Fields` (the rule vocabulary).
Try it: `make check-cases`, groups `shoes` (the limit to the cent: CHF 200 passes, 200.01 doesn't) and `budget` (the rolling week: 110 + 110 approved, 81 declined at 301, 80 approved at exactly 300, and a purchase on 12 Sep approved again once the older orders are more than seven days old). The same for pets (30-day budget) and food delivery (7-day budget).

Not covered: the account's own monthly limit from the data pack, and any "unusual amount for this card" baseline from the history file.

## 2. Doesn't buy from shady merchants

The data has no "risk score" for a shop, so "shady" is defined from what Viseca's data does show.

| What | How |
| --- | --- |
| Lookalike sellers | `SellerCheck` runs on **every** decision, whatever the policy says. If the seller's name is at least 85% the same as an established shop (at least 5 approved purchases on the platform, and more than the seller), and the card hasn't bought at the seller twice or more, an approval becomes `step_up`. Example from the data: **PixelHarbour** (no purchase history at all) vs **PixelHarbor** (31 purchases, 6 by this card). The customer is told which shop it imitates. |
| Platform history | Every decision carries how many approved purchases and cards the whole history file has at this seller (none / thin / established). It is evidence and context for the judge only. It never blocks by itself, because an unfamiliar shop is not a wrong shop. |
| "A seller I have bought from before" | Compiles to `merchant.familiar = true` (the card has at least 2 earlier approved purchases there). PixelHarbour fails it deterministically, without the model. |
| Shop type and country | Rules on `merchant.merchant_category` ("specialist sports retailer") and `merchant.merchant_country`. |
| Shops are joined by id, never by name | So two shops with a near-identical name can't be confused. |
| Manipulative shops | See requirement 4. |

Code: `SellerCheck`, `HistoryIndex`, `PurchaseFacts` (`merchant.familiar`).
Try it: a check for PixelHarbour returns `pending_human` with reason code `lookalike_seller`. With the policy "a seller I have bought from before" it is `denied` by the familiarity rule, with a note about the lookalike.

Not covered: a history-based decline rate per shop (the data dictionary says historical `status` is not a label, so we don't use it), checking that the event's shop fields match the catalogue, and comparing the shop's country with the card's usual countries.

## 3. Purchases what you actually intended

| What | How |
| --- | --- |
| The customer's sentence becomes explicit permissions | `POST /policies` sends the sentence to the model, which returns rules, guidance, an `uncertainty_policy` and open questions in Viseca's mandate shape. The customer's words are kept verbatim. The compiler is forbidden to invent ids or values, so what can't be a rule becomes guidance. |
| Show what was understood | The created policy lists its rules, guidance and "when unsure". The customer can tighten it (`PATCH`) or revoke it (`DELETE`) at any time. (The `frontend/` shows this on the Policies screen: rules checked exactly, guidance judged by AI, "when unsure", with tighten and revoke buttons.) |
| What can't be a rule is still enforced | Colour, size, product type and return window become **guidance**. The judge checks each policy separately (satisfied / violated / unverified) against **every line** of the basket, so "black running shoes" is violated by white shoes, a coffee machine or a gift card in the cart. |
| Item rules apply to every cart line | `items.item_category` and `items.quantity` rules must hold for each line, so one forbidden item declines the whole order. Gift cards, memberships and cosmetics exist only as item categories in the data and are caught this way. |
| Several policies at once | With no policy named, all active policies and the purchase go to the model in one call. Any policy that is satisfied approves, and the message says which one. |
| Not sure? Ask, don't guess | If nothing contradicts the policy but a fact is missing (for example the return terms), the result is `step_up`, or a decline if the customer said "decline when unsure". A missing fact is never a reason to approve. |
| Don't over-block | An ordinary purchase that passes every rule is approved at once with no model call. An unfamiliar shop or a new device is only a problem if the policy asks for a familiar one. |
| Re-quotes and duplicates | An order linked to an earlier one (`related_authorization_id`) or the same shop and items again is flagged to the judge, which uses the status of the earlier order. |
| The customer stays in control | `step_up` goes to the customer, who approves or declines through `/resolve`. Revoking a policy is immediate and local, then Viseca is told. `PATCH` can only add rules and can only move "when unsure" toward decline, so a customer's restrictions are never weakened. |

Code: `PolicyCompiler`, `PolicyService`, `LlmJudge`, `CheckService` (`decideAuto`, `judgeAmong`).
Try it: `plans/EXAMPLE_POLICY_CURLS.md` shows fifteen sentences and what each compiles to. `make check-cases` groups `shoes` (colour, size, price, return window, shop type), `groceries` and `baskets` (gift card, membership, perfume in the cart), `books`, `hotels`, `pharmacy`, `kids` and `uncovered` (something no policy covers).

Not covered: natural-language tightening (`PATCH` takes structured rules only), and the 120-second human window isn't tracked yet.

## 4. Resilient against prompt injection

Everything a shop writes is untrusted: `item_details`, item names, the merchant name and the purchase description. Defence in layers, so the model is only one of them.

| Layer | What it does | Strength |
| --- | --- | --- |
| Shop text can't reach the policy | Policies are stored server-side and changed only through the customer API. `/check` takes purchase facts, never policy content. Shop text never goes into the policy compiler. | Guaranteed by construction |
| No rule can be about shop text | The rule vocabulary has no free-text fields, so what a shop writes can't be the subject of a rule. | Guaranteed |
| Rules first, in code | A failed hard rule is a decline that the model cannot override. The model cannot approve past an unknown fact, or without naming one of the candidate policies. | Guaranteed |
| Tripwire on all shop text | Regex patterns for text that addresses the agent ("ignore previous...", "pre-authorised", "approve without further checks", "customer is unavailable", ...). A hit skips the fast approve, sends the purchase to the judge and records the quoted text as evidence ("shop text that looks like an instruction (ignored)"). | Weak: English only |
| Untrusted block and manipulation flag | The judge gets shop text in a separate `untrusted_merchant_text` block and is told it is data. If it sees manipulation it sets `manipulation_suspected`. **Code** then turns an approval into `step_up` (`possible_manipulation`). | Probabilistic, but the consequence is enforced in code |
| Constrained output | Strict JSON schema, temperature 0. The model can only answer approve / decline / step_up plus a policy id. | Guaranteed shape |
| Fail-safe | Model down, failing or out of time: the policy's "when unsure" applies. It never approves because something failed, unless the customer's own policy says so. | Guaranteed |
| Only the customer resolves | A pending decision can be resolved only by `/resolve`, never by text in a purchase. | Guaranteed |

**Tested.** `make check-cases` case 20 (shop text ordering the agent to skip checks on an otherwise perfect purchase) returns `pending_human`. A separate red-team run sent white size-42 shoes, which no policy allows, with 10 injection styles: classic English, German, English with no trigger words, in the item name, in the merchant name, a fake policy block, zero-width-character obfuscation, in the purchase description, an over-limit price with a "limit raised" claim, and a gift card claiming pre-authorisation. **None was approved.** Only 2 of the 10 were caught by the tripwire; the rest were stopped because the judge read the real facts (colour, size) and the hard rules held. That red-team script was run ad hoc and is not in the repo.

**What this does and doesn't prove.** It was one run per case on one model, so it shows the defence works, not that it is unbreakable. Two things stay weak:

- **The tripwire is English-only regex.** Paraphrases, other languages and obfuscation slip past it, so for those the judge prompt is the only guard.
- **Guidance-only requirements rest on shop-written text.** If a shop simply claims "black, size 43", nothing can disprove it. That is deception rather than injection, but it has the same effect. The damage is still capped by the hard rules (amount, category, country, period budget).

Next steps if there is time: put the red-team cases in `run-check-cases.sh`, and replace the regex tripwire with a small classifier call on shop text.

---

## What the customer sees for every decision

Judges asked for *what was permitted, what evidence was used, why it acted, and how the customer stayed in control*. Every decision returns:

| Field | Meaning |
| --- | --- |
| `state` / `viseca_decision` | `approved` / `approve`, `denied` / `decline`, `pending_human` / `step_up` |
| `reason_codes` | Short machine-readable reasons, e.g. `rule_failed_billing_amount_chf`, `lookalike_seller`, `possible_manipulation` |
| `customer_message` | One or two plain sentences, no jargon |
| `policy_id` | The policy that allowed it |
| `checks[]` | The exact result of every rule: pass / fail / unknown, with the actual value |
| `evidence[]` | Facts used: rules, seller history, quoted shop text that looked like an instruction, what the model concluded per policy |
| `used_llm`, `decided_by` | Whether the model was involved, and whether the engine or the customer decided |

`GET /decisions?state=pending_human` lists what is waiting for the customer.

## Speed and resilience

Viseca gives 8 seconds from queueing. Ordinary purchases are decided by rules alone, with no model call. The judge has a 5-second timeout and is skipped if the deadline is within 1.5 seconds, and then the "when unsure" outcome applies. The worker long-polls Viseca, decides in delivery order and posts each answer straight away, `step_up` included. It never waits for a human.

## Evidence that it works

| What | Result |
| --- | --- |
| `make check-cases`: 100 purchases against 15 policies | 94 of 100 at the last full run (real `gpt-4.1`); the judge was changed after that and has not been re-run yet |
| `plans/EXAMPLE_CHECK_CURLS.md`: 17 cases | 17 of 17, three runs in a row |
| Injection red-team, 10 styles | 0 approved (ad hoc run) |
| Seller check on the SCEN0004 card | PixelHarbour asked, PixelHarbor, HarborByte and Circuit and Pine approved |

## Known gaps

- **The UI is minimal.** `frontend/` has the four essential screens (policies with tighten / revoke, try a purchase, step-up inbox, activity with evidence), but no live push (it polls), no login, and no in-app editing of individual rules.
- **Nothing has been run against the real Viseca API**, only a mock, so their `evidence` format is unverified.
- **Decisions and spend are in memory** and lost on restart. Policies survive in `store/policies.json`.
- **The tripwire and guidance-only requirements** are the weak points of requirement 4, as above.
- **The model can vary a little between runs.** The state was stable in all our runs, but the wording of a reason changes.

## Try it

```bash
make provision          # build and start (needs OPENAI_API_KEY in .env)
make example-policies   # create the 15 example policies
make check-cases        # 100 purchases with live progress, pass / fail per case
```

Swagger UI with every endpoint: <http://localhost:8080/swagger-ui.html>
