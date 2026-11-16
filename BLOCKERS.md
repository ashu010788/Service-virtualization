# Blockers & Open Questions

## Active Blockers

### Cross-API State Linkage In Record-Ingest Batch Stub Creation 

**Status:** Blocked — no viable solution without LLM/AI, which is not permitted.

During **batch recording** (ingesting multiple related requests in sequence), the auto-generated stubs can get issues for `stateAction` WRITE/READ wired correctly for **individual** endpoints. 

1. **Which body field** should serve as the state key linking the POST response to a later GET lookup (is it `account_id`, `order_id`, `customer_id`, or a nested field like `$.data.ref`?).
2. **Which collection name** to share so that a stub on `/api/v1/orders` can read state written by a stub on `/api/v1/accounts`.
3. **Which response fields** from the creating request should be carried forward into the reading request's `keyExpr` / `valueExpr`.

**What would solve it:** An LLM/AI-assisted ingest mode that analyzes the full batch of recorded traffic, infers entity relationships from field names, values, and request ordering, and auto-wires `stateAction` linkages across stubs. **However, LLM/AI integration is not currently allowed**, making this a hard blocker for fully automated batch recording.

**Current workaround:** After record ingest, **manually edit** the generated stubs' `stateAction` blocks to wire up cross-API state sharing (set matching `collection`, `keyExpr`, and `valueExpr` across the related stubs). This requires domain knowledge of the API under virtualization and does not scale.
