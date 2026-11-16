# Evaluation Report — Day 1

## 1. Stub Match Scenarios (`stub_match_scenarios.json` ,`seed_stubs.json`)

**33 labeled scenarios · 33 passed · 0 failed**

| ID | Category | Label | Expected Stub | Status |
|----|----------|-------|--------------|--------|
| SCN-01 | complex_path_matching | [PATH] Fresh wildcard ID with no state falls through get→not_found chain | stub_account_not_found | ✅ PASS |
| SCN-02 | complex_path_matching | [PATH] Exact literal node wins over sibling wildcard | stub_account_list_v1 | ✅ PASS |
| SCN-03 | complex_path_matching | [PATH] Lowercase HTTP method still resolves (trie uppercases at lookup) | stub_account_list_v1 | ✅ PASS |
| SCN-04 | complex_path_matching | [PATH] Trie dead-end — extra segment beyond deepest wildcard | *(no match)* | ✅ PASS |
| SCN-05 | complex_path_matching | [PATH] Trie dead-end — unregistered version prefix (v2 vs v1) | *(no match)* | ✅ PASS |
| SCN-06 | complex_path_matching | [PATH] Wildcard captures hyphenated multi-part ID as one atomic segment | stub_payment_not_found | ✅ PASS |
| SCN-07 | complex_path_matching | [PATH] gRPC method routing is isolated from HTTP path trie | stub_grpc_create_account | ✅ PASS |
| SCN-08 | multi_field_jsonpath | [JSONPATH] Two-field AND match outranks single-field stub | stub_savings_account_create_v1 | ✅ PASS |
| SCN-09 | multi_field_jsonpath | [JSONPATH] Partial multi-field mismatch falls back to single-field EUR stub | stub_account_create_eur_v1 | ✅ PASS |
| SCN-10 | multi_field_jsonpath | [JSONPATH] Total mismatch falls through to conditionless fallback stub | stub_account_create_fallback_v1 | ✅ PASS |
| SCN-11 | multi_field_jsonpath | [JSONPATH] Nested-object field extraction — $.address.country=US wins | stub_customer_create_us_v1 | ✅ PASS |
| SCN-12 | multi_field_jsonpath | [JSONPATH] Nested-object mismatch falls back to generic customer stub | stub_customer_create_v1 | ✅ PASS |
| SCN-13 | multi_field_jsonpath | [JSONPATH] Missing parent object entirely resolves to null, not a crash | stub_customer_create_v1 | ✅ PASS |
| SCN-14 | session_state_mutation | [STATE] CREATE — POST writes a new account into session state | stub_account_create_v1 | ✅ PASS |
| SCN-15 | session_state_mutation | [STATE] READ-AFTER-CREATE — write from SCN-14 is visible | stub_account_get_v1 | ✅ PASS |
| SCN-16 | session_state_mutation | [STATE] UPDATE — PUT overwrites the stored record | stub_account_update_v1 | ✅ PASS |
| SCN-17 | session_state_mutation | [STATE] READ-AFTER-UPDATE — overwrite confirmed in-place | stub_account_get_v1 | ✅ PASS |
| SCN-18 | session_state_mutation | [STATE] CROSS-COLLECTION WRITE — payment in its own collection | stub_payment_create_v1 | ✅ PASS |
| SCN-19 | session_state_mutation | [STATE] CROSS-COLLECTION READ — accounts and payments never collide | stub_payment_get_v1 | ✅ PASS |
| SCN-20 | session_state_mutation | [STATE] DELETE — removes account entry from session state | stub_account_delete_v1 | ✅ PASS |
| SCN-21 | session_state_mutation | [STATE] READ-AFTER-DELETE — deleted key falls through to not_found | stub_account_not_found | ✅ PASS |
| SCN-22 | multi_tenant_isolation | [TENANT] Tenant A creates account in its own session | stub_account_create_v1 | ✅ PASS |
| SCN-23 | multi_tenant_isolation | [TENANT] Tenant B creates account in a separate session | stub_account_create_v1 | ✅ PASS |
| SCN-24 | multi_tenant_isolation | [TENANT] Tenant A reads back its own ACC-0001 | stub_account_get_v1 | ✅ PASS |
| SCN-25 | multi_tenant_isolation | [TENANT] Tenant B reads its own ACC-0001 (physically separate record) | stub_account_get_v1 | ✅ PASS |
| SCN-26 | multi_tenant_isolation | [TENANT] Stranger session gets zero visibility — no cross-tenant leak | stub_account_not_found | ✅ PASS |
| SCN-27 | multi_tenant_isolation | [TENANT] Tenant A deletes its own account | stub_account_delete_v1 | ✅ PASS |
| SCN-28 | multi_tenant_isolation | [TENANT] Tenant B's copy is unaffected by Tenant A's delete | stub_account_get_v1 | ✅ PASS |
| SCN-29 | fallback_stub_match | [FALLBACK] Empty body lands on conditionless fallback stub | stub_account_create_fallback_v1 | ✅ PASS |
| SCN-30 | complex_path_matching | [PATH-NESTED] Two-level wildcard — POST creates transaction under account | stub_account_transaction_create_v1 | ✅ PASS |
| SCN-31 | complex_path_matching | [PATH-NESTED] Composite key READ — both path vars combine into state key | stub_account_transaction_get_v1 | ✅ PASS |
| SCN-32 | complex_path_matching | [PATH-NESTED] Wrong outer accountId — composite key misses, falls to not_found | stub_account_transaction_not_found | ✅ PASS |
| SCN-33 | complex_path_matching | [PATH-NESTED] Correct accountId, wrong transactionId — composite key still misses | stub_account_transaction_not_found | ✅ PASS |

**Result: 33/33 ✅ — all stub match scenarios passed**

---

## 2. Latency Verification

### Sequential Latency (single thread, no contention)

| Percentile | Latency |
|------------|---------|
| p50 | 0.001 ms |
| p95 | 0.002 ms |
| p99 | **0.007 ms** |

**Target: p99 < 10 ms → ✅ PASS (0.007 ms, ~1428× headroom)**

### Concurrent Latency (16 threads)

| Percentile | Latency |
|------------|---------|
| p50 | 0.006 ms |
| p95 | 0.013 ms |
| p99 | **0.033 ms** |

**Target: p99 < 10 ms → ✅ PASS (0.033 ms, ~303× headroom)**

### Throughput (8 threads × 50,000 ops)

| Metric | Value |
|--------|-------|
| Total ops | 400,000 |
| Duration | 0.32 s |
| **RPS** | **1,233,123** |

**Target: 20,000 RPS → ✅ PASS (1.23M RPS, ~61× headroom)**

---

## 3. State Isolation Verification

| Scenario | Result |
|----------|--------|
| 16 concurrent sessions × 500 ops = 8,000 assertions | ✅ 0 violations |
| Cross-session key invisibility (SCN-26) | ✅ confirmed |
| Independent CRUD state per session (SCN-14 → SCN-21) | ✅ confirmed |
| Tenant A delete does not affect Tenant B (SCN-27 → SCN-28) | ✅ confirmed |
| Cross-collection isolation — accounts vs payments (SCN-18 → SCN-19) | ✅ confirmed |

**State isolation is fully verified: zero cross-session leaks across all 8,000 concurrent assertions.**
