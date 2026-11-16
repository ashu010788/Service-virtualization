# Blockers & Open Questions

## Resolved

### Day 1 — Stub Matching Engine
- **Trie Concurrency**: Copy-on-Write Trie with volatile root. Lock-free reads, synchronized writes. `insertDirect()` for startup (no copy), `insert()` for runtime (CoW).
- **Body Matching**: Manual JSONPath — `$.field`, `$.nested.field`, `$.items[0].field`. No Jayway dependency. Does NOT support wildcards (`$..field`), filters, or slicing.
- **Session Isolation**: Nested ConcurrentHashMap (3 levels). Per-session AtomicLong counters. Verified: 16 sessions × 5,000 ops, zero violations.

### Day 2 — Recording & Stub Generation
- **PCI/PII Sanitization**: 4-layer detection (field name → value type → regex pattern → Luhn). Compiled static final patterns. Masks: `****1111` (cards), `****@masked.com` (emails), `****6789` (SSN), `****` (CVV, tokens, phone).
- **Confidence Scoring**: ≥0.90 auto-publish, 0.70–0.89 pending review, <0.70 reject.
- **API Response Security**: `originalValue` in `fieldDetails` is masked for sensitive fields (via `@JsonProperty` override). `action` field excluded from API response (`@JsonIgnore`).

### Day 3 — Web UI & Documentation
- **Web UI**: Thymeleaf + HTMX + Bootstrap 5. Dashboard, Stubs, Sessions, Traffic, Drift, Recording pages.
- **Write-ups**: CUSTOMER_VALUE.md, SCALABILITY.md, README.md complete.

## Known Limitations (Not Blockers)
- **gRPC**: Conceptual stubs (JSON-based). No protobuf binary handling.
- **Kafka**: Async events write to session state. No real Kafka broker.
- **Persistence**: In-memory only. Server restart clears all data.
- **`card_type` false positive risk**: Field name fuzzy matching (`contains("card")`) could over-classify. Mitigated by `KNOWN_SAFE_NAMES` whitelist checked first.
- **Confidence threshold tuning**: 0.90/0.70 thresholds are heuristic. Would tune via A/B testing on real traffic in production.
