# ArcCivs .68 ground-resource and storage assessment

The final private asset manifest is [final-v2](../../artifacts/arccivs-068-building-chests/final-v2/building-chests-manifest.json) (SHA-256 `908fcf4f3cf5d27d64cf45f857533a28f135468f05c4f7a4e08e8fb0ece9b545`). Its 72 schematic files total 122,048 bytes. The manifest provides catalog-level counts; it does not verify native runtime readback, installation, placed containers, live availability, or client acceptance.

## Ground-item transfer

The proposed .68 flow moves an already-existing native ground `Item` entity, preserving its exact item-stack metadata, into the resident's visible worker bag and then to its home container through a durable receipt and flush acknowledgement. That is a transfer of existing items, not a grant or a credit to abstract Auto stock. Replaying an accepted receipt must complete the same transfer rather than create another copy.

The low/base/high model counts 1 / 10 / 100 completed pickup cycles per modeled worker-day. One scenario participant is one modeled worker; these counts are sensitivity cases, not measured activity or a rate inferred from the concurrency controls. Each cycle is counted as one transfer event. The evaluator therefore reports 1 / 10 / 100 transfers per day and 7 / 70 / 700 per week, with zero net mint. The entity's stack size is not inferred from the event count.

Combat, hunger, sleep and work keep priority. The limit of up to eight entities per operation and a five-second-per-world concurrency bound limit simultaneous work; neither is a daily rate. Successful real pickups may reduce despawn loss, but avoided losses and realized activity are unmeasured.

## Schematic containers and storage reach

The manifest records transformations for 36 current schematics and their 36 exact legacy counterparts. Another 54 non-warehouse schematics and all 9 warehouse schematics are unchanged. Within the 36 transformed schematics, 102 authored rack cells resolve to 86 single chests, 9 barrels, and 7 cleared cells. The new containers are empty in the templates. At 27 nominal slots per chest or barrel, this is 95 containers and 2,565 catalog-template slots.

Those 2,565 slots describe the prepared schematic catalog only. The manifest is marked private-assets-only; `nativeReadbackVerified` and `runtimeClientAcceptance` are false. It does not establish which schematics are installed or built, how many containers are currently placed or available, or how much capacity is in use. Actual live available slots and utilization therefore remain unknown. The catalog preserves 449 decorative bookshelves and 7 pre-existing rack-air cells; only the source-authored rack cells are transformed.

For new personal-build captures, the converted schematics require the new native materials in place of the former rack/shelf placeholders: 102 authored rack cells map to 86 chests, 9 barrels and 7 air cells. This changes construction-material composition; it is not a free item grant. Auto keeps its existing primitive stock-cost behavior and rates. Empty replacements in existing guarded repair are a one-time migration for erroneous imports, not recurring item supply or income. No monetary price is assigned.

The global building index already spans buildings. The .68 local-source scope expands discovery to every ACTIVE building's bounds and foreign controls, allowing recovered items to reach their intended home container. This is a storage-discovery and transfer change; it does not add abstract Auto stock. The catalog capacity is not a claim that containers will be filled or that every ground item will be collected. Successful real pickups may reduce despawn losses, but the amount avoided and realized pickup activity are unmeasured.

## Balance and evidence limits

Vault, premium tokens and Minecraft player XP are each zero before and after. Ground-item transfers are evaluated in a separate event unit and do not affect net mint. No item-to-currency conversion, SELL price, or contract payout is assumed. Current item SELL behavior and the planned SELL-to-contract transition remain unchanged.

For ledger context only, the existing assessment references a read-only 168-hour network snapshot captured **2026-10-23T16:51:48.477Z** in [autonomous-economy-assessment.md](autonomous-economy-assessment.md#direct-currency-and-observed-economy). This background observation is not refreshed or attributed to .68.

The canonical balance evaluator passed this assessment. It reports zero direct Vault/token/player-XP deltas, 1 / 10 / 100 item-transfer events per modeled worker-day, and 7 / 70 / 700 per modeled worker-week. Its `transfer` kind is excluded from net mint/burn. The 2,565-slot figure is catalog-template capacity; actual live capacity remains unverified.
