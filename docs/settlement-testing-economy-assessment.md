# ArcCivs permission-gated settlement testing mode

This is a planned-flow assessment for the permission-gated test options. It models only incremental automatic construction-material issuance. Existing admin/Creative grants are already item-mint paths and remain outside this before/after delta; they can export physical items, and cumulative lifetime issuance has no imposed cap.

The target contract fixes the new `arccivs.testing` permission to default off. A city owner with that permission may enable immortality (`autoHP >= 1`) and automatic construction materials (default off). The existing per-city `arccivs.chunkload` toggle keeps its legacy on default. The GrocerMC test-city owner explicitly requests all three city toggles on. The owner permission cache refreshes every 15 seconds with a 30-second allowance; loss of permission stops new grants and immortality. Ordinary cities remain opted out. These are task-provided target behaviors, not claims of implementation, delivery, activation or live verification.

Automatic materials are limited to the actual current construction deficit after existing construction storage and builder inventory. The grant goes into a real builder bag through a durable inventory receipt, and recovery does not replay a completed grant. Current source records six equipment slots plus 27 bag slots; each material still obeys its native stack size. Existing construction payment is committed with its exact item debit (`CivsRules.kt:449-480`), and the builder supply path computes shortages against carried items and available storage (`CivsStorage.kt:630-665`).

| Scenario | Test operators | Assumed grants per operator-day | Assumed items per grant | Items per operator-day | Items per network-week |
|---|---:|---:|---:|---:|---:|
| Low | 1 | 1 | 64 | 64 | 448 |
| Base | 2 | 4 | 64 | 256 | 3,584 |
| High | 4 | 12 | 64 | 768 | 21,504 |

These are illustrative mixed-item counts, not measured usage, guaranteed deficits, hard daily limits or an enforced budget. A real grant cannot exceed the eligible deficit or bag capacity. The feature adds no direct Vault, token or player-XP flow (0 → 0 in each separate unit). Items have no assumed cash value; the existing Creative/admin path can export them into existing sale/trade paths, but material-specific prices and the amount players could realize are unknown. The planned SELL-to-contract transition is not treated as active.

Immortality preserves existing possessions and internal resident skill otherwise lost on death; it does not issue items or player XP. The movement correction may restore actual work throughput without changing output per completed cycle or reward quantity. The resulting throughput and any resale effects have not been measured. Permission loss and recovery handling are part of the target contract, not proof from a running server.

A bounded read-only Economy V2 query for `survival` covered 2026-10-16T10:01:47.986Z through 2026-10-23T10:01:47.988Z. The response marked the boundary exact and excluded no crossing or future records; coverage was 2,665 records, 31,040 operations and 22 players. Typed Vault totals were 685,959.9553100035 minted and 1,522,950.11 burned; typed tokens were 51 minted and 0 burned. One Vault source/origin remained unclassified (one operation, 5,000 absolute amount). These server-economy aggregates are not attributed to ArcCivs or the test operators. The MCP clamps `economy_limit` to 100; no material-specific shop selection or concentration-group query was made. Mixed legacy currency aggregates and incomplete top-account lists are not used.

**Assessment:** This is an opt-in administrative test item faucet, not ordinary-player income. Scenario counts show its assumed item volume; they do not bound lifetime issuance. The financial delta is zero only for direct Vault, tokens and player XP. Item resale remains unpriced and unmeasured. Before any later delivery claim, verify the exact permission/owner state, city defaults, the deficit calculation, durable receipts, recovery without replay, and the real bag's native stack/capacity behavior. This assessment does not assert source implementation or runtime acceptance.

See [machine-readable inputs](settlement-testing-economy-assessment.json) and [calculator output](settlement-testing-economy-assessment-result.json).
