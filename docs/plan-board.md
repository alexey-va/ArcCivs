# Settlement plan board

PLAN_BOARD is a single-level wooden building with stone feet, a roof and a large plan surface. It uses normal site selection, four rotations, construction and deletion. New boards use `civs/builtin/plan_board/v3`: an 11×8×3 body with the normal control barrel centred below the map. V1/V2 source captures remain readable. A narrowly versioned same-level refit uses the existing construction journal and preserves the building ID, anchor, entrance, rotation and exact control/inventory. The V1 refit shifts the new face around its existing side barrel; its reserved union footprint includes the old feet without moving the barrel. Reading or refreshing packet text does not replace physical blocks.

In a personal settlement, choose the board in the building catalog. Administrators can start placement from the selected autonomous village's own screen. That action retains the explicit village UUID through ground selection, preview movement and confirmation; having a separate personal city does not redirect it. Autonomous placement retains the existing admin boundary, while the personal catalog uses ordinary settlement management rights. Permission, world, city identity and deletion checks run again at the final persistence boundary after asynchronous capture.

The builder constructs the board normally. The map appears after its building record becomes ACTIVE and is visible to nearby players. It returns after a server start, updates when the settlement geometry changes and disappears when the board record is deleted. The standard control barrel remains available under the existing inventory access rules. Deleting the record closes the map scene but leaves physical blocks, as with other ordinary buildings.

## Reading the map

- Filled footprints are existing ACTIVE buildings.
- Amber contours show marked sites, construction and queued upgrades. An upgrade retains its existing body underneath the outline.
- Purple outlines show unbuilt plots, roads, gates and walls from the persisted autonomous settlement plan.
- Roads combine validated retained approaches and adopted future routes without filling gaps between separate paths.
- The village name, north marker and two compact left-aligned legend columns provide orientation.

`CivAutonomy.plan` is the same spatial source used by runtime construction. It reserves the complete 16-building profile with stable IDs, maximum upgrade/work-field envelopes and exact graded routes. Canonical placement and Terraform rules check the captured terrain; a dry adaptive perimeter follows the available land and places gates on preserved exterior-road crossings. Existing paid buildings, approaches and infrastructure remain pinned. Only a successful complete plan is adopted and drawn. A failed attempt backs off while ordinary development continues; no partial candidate is presented as a full future village.

Planning creates no SITE, payment, stock, capacity or native block. Research, resources and normal construction priority determine when a reserved plot becomes an order. SITE activation retains its adopted envelope/route and refreshes native before-images. Native protection and support checks still own each physical order. Personal settlements show their existing sites and accepted construction. Residents, stock and counters do not themselves rebuild the map.

New roads start as narrow paved footpaths along their exact graded centreline. Ordinary approach earthworks retain the wider walkable corridor, while existing accepted roads and gates keep their complete geometry. No later widening job is added by this change.

All map layout and packet preparation use detached data. Rendering uses the existing ArcCore packet display scenes, with no native MapView IDs, item frames or transferable map items. The normal vector layout combines exact road cells; an exact bitmap representation handles unusually complex coverage within the same scene budget. Both representations preserve occupied and empty cells. Map and legend fit inside the sandstone face; warm light key text uses its own dark backing. Display coordinates rotate around block centres so quarter-turns match the actual integer schematic cells. Source tests and exported views check geometry, rotations, planned overlays and road coverage; native font filtering and client appearance still require ordinary-client observation.

## Price and storage

The board uses one of the existing 16 ordinary building slots and adds no job, housing, production or abstract warehouse capacity. New autonomous construction remains 12 WOOD plus 8 STONE. Physical construction pays exact authored materials: v3 has 30 spruce logs, 6 stone bricks, 36 sandstone blocks, 22 stairs and 11 slabs. V1/V2 bodies had 60 solid blocks; v3 has 105. Existing autonomous appearance maintenance uses the agreed zero-stock refit contract, with no item drops, credits or bonus inventory. The centred V1 refit writes 97 new solid states and retains 8 sandstone cells; V2 refit writes 85 and retains 20 solid cells. The 27-slot control barrel and its contents remain in place. The [.94 balance assessment](workflow-094-assessment.json) records the distinct resource and inventory units.

## Operations and recovery

Snapshots load accepted plans through existing asynchronous store paths. Publication is fenced against changed geometry, queued upgrades, deletion, reload and shutdown. Stale results cannot replace a newer scene. The source-owned builtin cache keeps only encoded bytes and their SHA; decoded schematic snapshots expire with their preview/build users. Accepted construction continues from its own captured bytes, not a mutable source file.

The new enum value is persistent once a board SITE is accepted. A rollback build must understand PLAN_BOARD, or use a separately reviewed data migration; an older build that predates this type is not a safe blind rollback after placement. Preserve city data and captured plans together. No migration or rollback is performed by merely reading a map.

Source-authored previews live under `build/qa/plan-board` or the explicit `CIVS_PLAN_BOARD_PREVIEW_OUT` export directory. The exported physical blocks, scene parts, transforms, text and source hashes come from the same compiled implementation as the plugin. Offline renders are not native-client screenshots. JAR delivery and activation are recorded separately from source tests and these previews.

`CIVS_PLAN_BOARD_SCENARIO` optionally supplies a private JSON with `city`, `snapshot`, captured `surface` and exact origin-relative authored `templates` keyed by `TYPE-level`. The export runs the real complete planner and renders new v3 and centred V1 refit boards at 0°/270°. Physical blocks use independent integer schematic transforms. `board-materials.json` records full bodies and actual refit writes; `candidate-infrastructure.json` is explicitly unadopted diagnostic data, while `plan-feasibility.json` records whether complete adoption succeeded. Keep private city scenarios and third-party schematics outside tracked sources.
