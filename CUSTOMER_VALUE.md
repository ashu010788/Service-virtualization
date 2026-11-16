# Customer Value

## Baseline Problem

Microservices cloud migrations stall when teams cannot test against downstream dependencies. Real payment gateways and banking APIs are unavailable in sandbox due to per-call cost ($0.05/hit), rate limits, and PCI restrictions. Teams waste **3-5 days per sprint** hand-writing brittle mocks that drift from production and break silently when APIs evolve. Concurrent CI/CD pipelines sharing test data cause phantom failures that erode pipeline trust and block releases.

## Measurable Outcome (30-Day Pilot, 3 Squads)

| Metric | Baseline | Target |
|--------|----------|--------|
| Mock setup time per service | 4-8 hours manual | < 15 min via traffic recording + auto-generation |
| CI flakiness from mock drift | ~12% failure rate | < 2% (stubs match real captured traffic) |
| Downstream sandbox API cost | $0.05/hit ($8K-$15K/mo/squad) | $0 - stubs serve locally |
| Cross-team test pollution | 2-3 incidents/sprint | 0 (session-isolated state stores) |
| PCI audit remediation | 40+ hours/quarter | 0 - auto-sanitization at ingest |

Success = **zero test pollution** and **> 50% reduction in mock setup time** across all 3 squads within 30 days.

## Risks and Limitations

**A. Auto-generated stubs may lack coverage.** (1) A single traffic sample misses edge cases. Guardrail: confidence scoring rejects stubs < 0.70, queues 0.70-0.89 for review, auto-publishes only >= 0.90. (2) Cross-API state linkage in batch ingest can fail — the generator cannot infer which response field should become a state key linking `POST /accounts` to `GET /accounts/{id}`. Heuristics cover common patterns; complex workflows need manual tuning.

**B. Sanitization has false positives and negatives.** `card_type: "VISA"` gets wrongly flagged; card numbers in unexpected fields may be missed. Guardrails: `KNOWN_SAFE_NAMES` whitelist prevents over-masking; regex + Luhn checksum catches card patterns regardless of field name. Recall >= 95%, validated by 24-case adversarial suite.

**C. Stubs drift from production.** As APIs evolve, stubs go stale. Guardrail: governance scheduler removes auto-generated stubs unmatched after 7 days, manual stubs inactive 30+ days, and stubs past explicit TTL.

## What We Would NOT Automate

**Cross-API state linkage.** Auto-generated stubs are per request/response — they cannot reliably determine which fields link across APIs (e.g. `account_id` returned by `POST /accounts` must become the state key for `GET /accounts/{id}`). Without manual review, a create-then-read flow may silently break: the GET stub looks up state under a wrong key and returns 404. A human must verify and adjust `stateAction` configuration for multi-step workflows.

**Payment-critical stub approval.** Stubs for payment, fraud, and compliance endpoints require human review — a wrong stub masks bugs that cost money in production. The platform queues these regardless of confidence score.

**Low-confidence stub promotion.** Stubs scoring 0.70-0.89 are held in `PENDING_REVIEW`; below 0.70 are rejected. A human must inspect and approve before they enter the live registry.

**Drift and error simulation.** Latency injection, jitter, and error rates must be manually configured per route — the platform cannot infer realistic degradation profiles for a given downstream service.
