# Blockers & Open Questions

## Active Blockers

### Record-Ingest Batch Stub Creation — Cross-API State Linkage

**Status:** Blocked — no viable solution without LLM/AI, which is not permitted.

During **batch recording** (ingesting multiple related requests in sequence), the auto-generated stubs get `stateAction` WRITE/READ wired correctly for **individual** endpoints. However, when multiple APIs need to **share state across each other** — e.g. a POST `/api/v1/accounts` creates an entity and a later GET `/api/v1/orders` needs to reference that account — the ingest pipeline **cannot reliably determine**:

1. **Which body field** should serve as the state key linking the POST response to a later GET lookup (is it `account_id`, `order_id`, `customer_id`, or a nested field like `$.data.ref`?).
2. **Which collection name** to share so that a stub on `/api/v1/orders` can read state written by a stub on `/api/v1/accounts`.
3. **Which response fields** from the creating request should be carried forward into the reading request's `keyExpr` / `valueExpr`.

**Why manual heuristics cannot solve this:**
- Field names vary wildly across APIs (`id`, `uuid`, `ref`, `account_number`, `resourceId`, `externalRef`, etc.).
- Relationships can be nested or indirect (e.g. an order references an account which references a customer).
- A single recording batch may contain dozens of interleaved requests where the entity linkage is **implicit in the domain model**, not explicit in the request/response structure.
- Deterministic rules (name matching, type inference) produce too many false positives and miss indirect relationships — every new API surface breaks the heuristic.

**What would solve it:** An LLM/AI-assisted ingest mode that analyzes the full batch of recorded traffic, infers entity relationships from field names, values, and request ordering, and auto-wires `stateAction` linkages across stubs. **However, LLM/AI integration is not currently allowed**, making this a hard blocker for fully automated batch recording.

**Current workaround:** After batch ingest, **manually edit** the generated stubs' `stateAction` blocks to wire up cross-API state sharing (set matching `collection`, `keyExpr`, and `valueExpr` across the related stubs). This requires domain knowledge of the API under virtualization and does not scale.
