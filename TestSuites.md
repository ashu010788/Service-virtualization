# Test Suites Summary

> **Total: 313 tests across 25 test classes, 0 failures**  
> **Date:** 2026-11-16 | **Runtime:** ~30s (excl. Kafka broker startup)

---

## Category Breakdown

| # | Category | Tests |
|---|----------|------:|
| 1 | **Stub Matching & Routing** | 96 |
| 2 | **Control Plane REST API** | 43 |
| 3 | **PCI/PII Sanitization** | 42 |
| 4 | **Forwarding & Outbound Proxy** | 27 |
| 5 | **Stub Management & Governance** | 22 |
| 6 | **Stub Generator (Ingest Pipeline)** | 16 |
| 7 | **Path Trie (CoW)** | 14 |
| 8 | **Session & Traffic Gaps** | 13 |
| 9 | **UI Page Rendering** | 11 |
| 10 | **Drift Simulation** | 7 |
| 11 | **Embedded Kafka** | 7 |
| 12 | **Performance & Benchmarks** | 6 |
| 13 | **Recording Critical Path** | 4 |
| 14 | **Stub Router Concurrency** | 5 |
| | **TOTAL** | **313** |

---

## By Test Type

| Type | Tests | Description |
|------|------:|-------------|
| Unit Tests | 187 | Engine internals: Trie, matchers, sanitizer, stub generator, drift, router concurrency, session isolation, critical path, governance |
| Integration Tests (SpringBoot) | 120 | Full HTTP round-trips: REST API, session lifecycle, ingest pipeline, forwarding proxy, outbound proxy, UI rendering, embedded Kafka |
| Performance Benchmarks | 6 | Latency p99, throughput RPS, session isolation under load, sanitization precision/recall, resilience |

