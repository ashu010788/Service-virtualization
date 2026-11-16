# Customer Value

## Baseline Problem

Microservices cloud migrations stall when teams cannot test against downstream dependencies. Real payment gateways, KYC providers, and legacy banking APIs are unavailable in sandbox environments due to cost, compliance, or rate limits. Teams waste **3–5 days per sprint** building brittle hand-written mocks that drift from production behavior, lack PCI compliance, and break silently when APIs evolve. Cross-team test pollution — where one squad's test data corrupts another's CI run — causes phantom failures that erode trust in the pipeline.

## Measurable Outcome (30-Day Pilot, 3 Squads)

| Metric | Baseline | Target |
|--------|----------|--------|
| Mock setup time per service | 4–8 hours manual | < 15 min via traffic recording + auto-generation |
| CI pipeline flakiness from mock drift | ~12% failure rate | < 2% (auto-generated stubs match real traffic) |
| Sandbox infrastructure cost | $8K–$15K/month per squad | $0 — stubs run in-process, no cloud instances |
| Cross-team test pollution incidents | 2–3/sprint | 0 (session-isolated state stores) |
| PCI audit remediation for test data | 40+ hours/quarter | 0 — sanitization runs automatically at ingest |
| p99 stub matching latency | N/A (real service calls: 50–200ms) | < 10ms (measured: 0.009ms) |

Success = **50% reduction in CI pipeline time** and **zero test pollution incidents** across all 3 squads within 30 days.

## Risks and Limitations

**Auto-generated stubs can be wrong.** A stub created from a single traffic sample may not cover edge cases (timeouts, partial failures, pagination). Guardrail: confidence scoring rejects low-quality stubs (< 0.70) and queues mid-range (0.70–0.89) for human review. Only high-confidence stubs (≥ 0.90) auto-publish.

**Sanitization has false positives.** Fields like `card_type: "VISA"` contain "card" in the name but aren't PCI data. Guardrail: `KNOWN_SAFE_NAMES` whitelist prevents over-masking of common business fields. Unknown high-entropy strings are flagged for security review, not silently passed.

**Stubs drift from production.** As real APIs evolve, stubs become stale. Guardrail: TTL-based expiry, unused stub detection (no matches in 7 days → flagged), and governance scheduler that auto-cleans expired stubs.

## What We Would NOT Automate

**Stub approval for payment-critical paths.** Auto-generated stubs for payment processing, fraud detection, and compliance endpoints must go through human review — a wrong stub here masks real integration bugs that cost money in production. The platform queues these for review rather than auto-publishing, regardless of confidence score.

**Session TTL policy.** Business teams must define how long test sessions live — this is a resource governance decision, not a technical one.
