# Settlement plan board

PLAN_BOARD is a small single-level wooden building with stone feet, a roof and a large plan surface. It uses the normal site selection, position controls, four rotations, construction queue and deletion behavior. Its builtin schematic is `civs/builtin/plan_board/v1`; no additional private schematic family or resource-pack item is required.

In a personal settlement, choose the board in the building catalog. Administrators can start placement from the selected autonomous village's own screen. That action retains the explicit village UUID through ground selection, preview movement and confirmation; having a separate personal city does not redirect it. Autonomous placement retains the existing admin boundary, while the personal catalog uses ordinary settlement management rights. Permission, world, city identity and deletion checks run again at the final persistence boundary after asynchronous capture.

The builder constructs the board normally. The map appears after its building record becomes ACTIVE and is visible to nearby players. It returns after a server start, updates when the settlement geometry changes and disappears when the board record is deleted. The standard control barrel remains available under the existing inventory access rules. Deleting the record closes the map scene but leaves physical blocks, as with other ordinary buildings.

## Reading the map

- Filled footprints are existing ACTIVE buildings.
- Amber contours are exact marked sites, current construction and accepted queued upgrades. An upgrade keeps the existing body underneath its future outline.
- Roads use validated retained approach routes and registered infrastructure geometry. Shared cells are combined without deleting road cells or joining separate paths.
- North, the settlement name and a short legend provide orientation. The map is a plan of registered geometry, not a live native-world survey or a proof that every path is walkable.

An autonomous planner goal without selected coordinates is not drawn as an invented site. The next building appears only when its real site or accepted capture exists. Resident movement, stock changes and construction counters do not by themselves require rebuilding the whole map.

All map layout and packet preparation use detached data. Rendering uses the existing ArcCore packet display scenes, with no native MapView IDs, item frames or transferable map items. The normal vector layout combines exact road cells; an exact bitmap representation handles unusually complex coverage within the same scene budget. Both representations preserve occupied and empty cells. Source tests and exported views check geometry, rotations, planned overlays and road coverage; native font filtering and client appearance still require ordinary-client observation.

## Price and storage

The board uses one of the existing sixteen ordinary building slots and adds no job, housing, production or abstract warehouse capacity. An autonomous construction costs 12 WOOD plus 8 STONE; physical towns reserve the exact body materials through the current construction receipts. Standard SITE-control installation grants or reuses the ordinary barrel before body construction, including its 27 physical slots. Reading or updating the packet map creates no items, experience or payout. The [balance assessment](plan-board-economy-assessment.md) records these separate units and the body inventory.

## Operations and recovery

Snapshots load accepted plans through existing asynchronous store paths. Publication is fenced against changed geometry, queued upgrades, deletion, reload and shutdown. Stale results cannot replace a newer scene. The source-owned builtin cache keeps only encoded bytes and their SHA; decoded schematic snapshots expire with their preview/build users. Accepted construction continues from its own captured bytes, not a mutable source file.

The new enum value is persistent once a board SITE is accepted. A rollback build must understand PLAN_BOARD, or use a separately reviewed data migration; an older build that predates this type is not a safe blind rollback after placement. Preserve city data and captured plans together. No migration or rollback is performed by merely reading a map.

Source-authored previews live under `build/qa/plan-board` or the explicit `CIVS_PLAN_BOARD_PREVIEW_OUT` export directory. The exported physical blocks, scene parts, transforms, text and source hashes come from the same compiled implementation as the plugin. Offline renders are not native-client screenshots. JAR delivery and activation are recorded separately from source tests and these previews.
