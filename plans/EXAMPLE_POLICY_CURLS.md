# Example policy curls

Each call sends one sentence to `POST /policies`. OpenAI turns it into rules and the policy is **active immediately** (no confirm step). The backend must be running (`make provision`) with `OPENAI_API_KEY` set in `.env`.

To create all fifteen at once, run `make example-policies` (or `scripts/create-example-policies.sh`). It reads the sentences from the curls below and skips any that already exist.

The "becomes" tables are what the real model produced when these were written. The wording can vary slightly between runs, but the meaning should stay the same.

- **Rules** are checked exactly (a purchase that breaks one is declined).
- **Guidance** is what the model judges from the product facts (colour, size, return window, "no extras").
- **When unsure** is what happens if something can't be verified: `ask` pauses for the customer, `decline` refuses.

---

## 1. Price limit and a colour

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy me black running shoes for up to CHF 200. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 200` |
| Guidance | Only black running shoes |
| When unsure | ask |

## 2. Groceries from a shop you already use

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy one ordinary grocery item for CHF 20 or less from a shop I use regularly. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 20`, `merchant.familiar = true`, `items.item_category in ["groceries"]`, `items.quantity = 1` |
| Guidance | Only buy an ordinary grocery item |
| When unsure | ask |

`merchant.familiar` means the card has at least 2 earlier approved purchases at that shop (from the history file).

## 3. Per-order limit plus a rolling 7-day budget

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Order our household groceries for delivery. Keep each order at or below CHF 120 including delivery, and keep the total across any seven days at or below CHF 300. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 120` (per purchase), `billing_amount_chf <= 300` (period, 7 days), `merchant.merchant_category = groceries`, `fulfillment_method = delivery` |
| Guidance | Only household groceries |
| When unsure | ask |

The 7-day rule adds up final approvals only. A purchase waiting for the customer is not counted as spend.

## 4. Item, seller type and return terms

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Replace my worn road-running shoes in size 43. Buy only from a specialist sports retailer, only if the order can be returned within 14 days or more, and pay no more than CHF 200. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 200`, `merchant.merchant_category = sporting_goods`, `order_returnable = true` |
| Guidance | Only road-running shoes in size 43; only if the order can be returned within at least 14 days |
| When unsure | ask |

The size and the length of the return window can't be an exact rule, so they become guidance for the model.

## 5. Forbidden categories, and decline when unsure

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Only buy groceries, never gift cards, cosmetics or memberships, at most CHF 50 per order. If you are unsure, decline."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 50`, `items.item_category in ["groceries"]`, `items.item_category not_in ["gift_card", "cosmetics", "membership"]` |
| Guidance | none |
| When unsure | **decline** |

Item rules are checked for **every** line in the basket, so one forbidden item is enough to decline the whole order.

## 6. Books, one shop type

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy books for me from a bookshop, up to CHF 60 per order. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 60`, `merchant.merchant_category = books`, `items.item_category in ["books"]` |
| Guidance | none |
| When unsure | ask |

## 7. Tickets in another currency

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy train and public transport tickets from Swiss providers only, up to EUR 50 per ticket. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 47.5` (EUR 50 at the fixed rate), `merchant.merchant_country = CH`, `items.item_category in ["transport"]` |
| Guidance | Only train and public transport tickets from Swiss providers |
| When unsure | ask |

## 8. Pet supplies with a 30-day budget, decline when unsure

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Order pet food and supplies from a pet shop, at most CHF 70 per order and at most CHF 150 in any 30 days. If you are unsure, decline."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 70` (per purchase), `billing_amount_chf <= 150` (period, 30 days), `merchant.merchant_category = pet_care` |
| Guidance | Only pet food and supplies |
| When unsure | **decline** |

## 9. The monitor I chose, from a seller I know

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy the 27-inch monitor I chose, from a seller I have bought from before, for CHF 400 or less. Do not add anything I did not ask for. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 400`, `merchant.familiar = true` |
| Guidance | Only the 27-inch monitor the customer chose; no extras that were not asked for |
| When unsure | ask |

## 10. Clothing, known shops, watch the session

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "The agent may buy clothing for me, up to CHF 250 per order, from shops I have used before. Pause anything that looks like someone other than me is driving the session. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 250`, `merchant.familiar = true`, `items.item_category in ["clothing"]` |
| Guidance | Pause anything that looks like someone else is driving the session (new device, a burst of attempts, an unusual country) |
| When unsure | ask |

## 11. Hotels in Switzerland

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Book hotels in Switzerland for up to CHF 300 per booking. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 300`, `merchant.merchant_category = hotel`, `merchant.merchant_country = CH` |
| Guidance | none |
| When unsure | ask |

## 12. Food delivery with a weekly cap

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Order food delivery for up to CHF 40 per order and no more than CHF 120 in any 7 days. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 40` (per purchase), `billing_amount_chf <= 120` (period, 7 days), `merchant.merchant_category = food_delivery` |
| Guidance | none |
| When unsure | ask |

## 13. Pharmacy basics, decline when unsure

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy over-the-counter health products from a pharmacy for up to CHF 50 per order, nothing else. If you are unsure, decline."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 50`, `merchant.merchant_category = health`, `items.item_category in ["health"]` |
| Guidance | Only over-the-counter health products from a pharmacy, nothing else |
| When unsure | **decline** |

## 14. Software from a shop you already use

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Pay for software licences and subscriptions up to CHF 30 per order, only from a shop I already use. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 30`, `merchant.familiar = true`, `items.item_category in ["software", "subscriptions"]` |
| Guidance | none |
| When unsure | ask |

## 15. Things for the kids, delivered

```bash
curl -s -X POST http://localhost:8080/policies \
  -H 'Content-Type: application/json' \
  -d '{"instruction": "Buy things for my children from a children's shop, up to CHF 80 per order, delivered to my home. Ask me when uncertain."}' | jq
```

| Becomes | |
| --- | --- |
| Rules | `billing_amount_chf <= 80`, `merchant.merchant_category = kids_family`, `fulfillment_method = delivery` |
| Guidance | Only things for the children; delivery to the customer's home |
| When unsure | ask |

---

## Then check a purchase against all your policies

No policy id is needed. Every active policy and the purchase go to OpenAI, which answers `approved`, `denied` or `pending_human`. Change `authorization_id` for each new purchase, because the same id returns the saved decision.

```bash
curl -s -X POST http://localhost:8080/check \
  -H 'Content-Type: application/json' \
  -d '{
    "authorization": {
      "authorization_id": "AU_TEST_1",
      "card_id": "CA0001",
      "timestamp": "2026-08-12T09:00:00Z",
      "billing_amount_chf": 165.0,
      "merchant": {"merchant_id": "ME0999", "merchant_name": "Run Specialists", "merchant_category": "sporting_goods", "merchant_country": "CH"},
      "items": [{"line_no": 1, "item_id": "IT0999", "item_name": "Road running shoes, black, size 43", "item_category": "sporting_goods", "quantity": 1, "unit_price": 165.0, "currency": "CHF", "item_details": "Black road-running shoe, size 43, 30-day returns"}]
    }
  }' | jq -r '"purchase \(.state)\nreason:  \(.customer_message)\ncodes:   \(.reason_codes | join(", "))"'
```

## Manage policies

```bash
# list everything saved
curl -s http://localhost:8080/policies | jq

# tighten: send every existing rule again, plus the new one (rules can only be added)
curl -s -X PATCH http://localhost:8080/policies/POL-xxxxxxxx \
  -H 'Content-Type: application/json' \
  -d '{
    "hard_rules": [
      {"field": "authorization.billing_amount_chf", "operator": "<=", "value": 200, "currency": "CHF", "scope": "purchase"},
      {"field": "merchant.merchant_category", "operator": "in", "value": ["sporting_goods"]}
    ],
    "uncertainty_policy": "decline"
  }' | jq

# revoke (immediate and final)
curl -s -X DELETE http://localhost:8080/policies/POL-xxxxxxxx | jq -r .status
```

Swagger UI with all endpoints: <http://localhost:8080/swagger-ui.html>
