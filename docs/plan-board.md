# Settlement plan board

PLAN_BOARD is a single-level wooden building with stone feet, a roof and a large plan surface. It uses the normal site selection, position controls, four rotations, construction queue and deletion behavior. New boards use `civs/builtin/plan_board/v2`: the control barrel is centred below the raised map, clear of its contents. Existing `v1` boards retain their exact body and side barrel. Both source versions remain available; updating the display does not move a control or migrate its inventory. No additional private schematic family or resource-pack item is required.

In a personal settlement, choose the board in the building catalog. Administrators can start placement from the selected autonomous village's own screen. That action retains the explicit village UUID through ground selection, preview movement and confirmation; having a separate personal city does not redirect it. Autonomous placement retains the existing admin boundary, while the personal catalog uses ordinary settlement management rights. Permission, world, city identity and deletion checks run again at the final persistence boundary after asynchronous capture.

The builder constructs the board normally. The map appears after its building record becomes ACTIVE and is visible to nearby players. It returns after a server start, updates when the settlement geometry changes and disappears when the board record is deleted. The standard control barrel remains available under the existing inventory access rules. Deleting the record closes the map scene but leaves physical blocks, as with other ordinary buildings.

## Reading the map

- Filled footprints are existing ACTIVE buildings.
- Amber contours are exact marked sites, current construction and accepted queued upgrades. An upgrade keeps the existing body underneath its future outline.
- Purple open corners and dashed streets/perimeter are an autonomous growth forecast, separate from accepted plans. The nearby note explicitly says that locations still need terrain checks.
- Roads use validated retained approach routes and registered infrastructure geometry. Shared cells are combined without deleting road cells or joining separate paths.
- North, the settlement name and a short legend provide orientation. The map is a plan of registered geometry, not a live native-world survey or a proof that every path is walkable.

The forecast completes accepted construction on a detached copy, then reuses `CivsAutonomy.nextGoal` and `CivsSettlementLayout.candidates`/`infill` with the current and maximum-level authored templates. It reserves at most eight new sites and stops at the first upgrade, unavailable candidate or existing building limit. Streets and wall reservations come from the same canonical settlement frame; known occupied footprints and retained roads are excluded from the dashed layer. This is a sketch of candidate reservations, not a terrain-approved construction queue or a completion promise. A failed template/candidate leaves the existing map available and labels the forecast partial. Personal settlements show accepted geometry only. Resident movement, stock changes and construction counters do not by themselves rebuild the map.

All map layout and packet preparation use detached data. Rendering uses the existing ArcCore packet display scenes, with no native MapView IDs, item frames or transferable map items. The normal vector layout combines exact road cells; an exact bitmap representation handles unusually complex coverage within the same scene budget. Both representations preserve occupied and empty cells. Map and legend fit inside the sandstone face; warm light key text uses its own dark backing. Display coordinates rotate around block centres so quarter-turns match the actual integer schematic cells. Source tests and exported views check geometry, rotations, planned overlays and road coverage; native font filtering and client appearance still require ordinary-client observation.

## Price and storage

The board uses one of the existing sixteen ordinary building slots and adds no job, housing, production or abstract warehouse capacity. An autonomous construction costs 12 WOOD plus 8 STONE; physical towns reserve the exact body materials through the current construction receipts. V2 rearranges the same 18 spruce logs, 6 stone bricks, 15 sandstone blocks, 14 stairs and 7 slabs; the required material counts are unchanged. Standard SITE-control installation grants or reuses the ordinary barrel before body construction, including its 27 physical slots. Forecasting and reading/updating the packet map create no sites, items, experience, payments or payouts. The [balance assessment](plan-board-economy-assessment.md) records these separate units and the body inventory.

## Operations and recovery

Snapshots load accepted plans through existing asynchronous store paths. Publication is fenced against changed geometry, queued upgrades, deletion, reload and shutdown. Stale results cannot replace a newer scene. The source-owned builtin cache keeps only encoded bytes and their SHA; decoded schematic snapshots expire with their preview/build users. Accepted construction continues from its own captured bytes, not a mutable source file.

The new enum value is persistent once a board SITE is accepted. A rollback build must understand PLAN_BOARD, or use a separately reviewed data migration; an older build that predates this type is not a safe blind rollback after placement. Preserve city data and captured plans together. No migration or rollback is performed by merely reading a map.

Source-authored previews live under `build/qa/plan-board` or the explicit `CIVS_PLAN_BOARD_PREVIEW_OUT` export directory. The exported physical blocks, scene parts, transforms, text and source hashes come from the same compiled implementation as the plugin. Offline renders are not native-client screenshots. JAR delivery and activation are recorded separately from source tests and these previews.

`CIVS_PLAN_BOARD_SCENARIO` optionally supplies a private JSON with `city`, `snapshot` and origin-relative authored `templates` keyed by `TYPE-level`. The export runs the real forecast and renders both the retained V1 and new V2 board at 0°/270°. Physical blocks are transformed through the integer schematic helper independently of the display transform, preventing a shared preview error from hiding a one-block displacement. Keep this private scenario and third-party schematics outside tracked sources.
