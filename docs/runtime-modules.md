# Runtime modules and extension points

ArcCivs has one settlement engine. Player and autonomous villages share resident
life, profession work, navigation, native containers and durable item accounting.
Autonomy supplies planning, staffing and the explicit deficit grants around that
engine. A feature must not introduce a parallel Auto-only work loop.

## Reference and decision

The comparison uses eight official MineColonies source files at commit
`61292848b81ee4a7a33443beb52aabdc446355b8`, inspected on 29 October 2026.
Its [citizen AI](https://github.com/ldtteam/minecolonies/blob/61292848b81ee4a7a33443beb52aabdc446355b8/src/main/java/com/minecolonies/core/entity/ai/workers/CitizenAI.java#L79-L105)
dispatches to a separate [job state machine](https://github.com/ldtteam/minecolonies/blob/61292848b81ee4a7a33443beb52aabdc446355b8/src/main/java/com/minecolonies/core/entity/ai/workers/AbstractAISkeleton.java#L27-L95).
Buildings compose [persistent modules](https://github.com/ldtteam/minecolonies/blob/61292848b81ee4a7a33443beb52aabdc446355b8/src/main/java/com/minecolonies/core/colony/buildings/AbstractBuilding.java#L155-L219),
and the colony owns a [token-based request manager](https://github.com/ldtteam/minecolonies/blob/61292848b81ee4a7a33443beb52aabdc446355b8/src/main/java/com/minecolonies/core/colony/requestsystem/management/manager/StandardRequestManager.java#L45-L110).

ArcCivs adopts the separation of responsibilities and explicit work/request
identity. It retains its own packet residents, owning-thread native capture,
bounded detached planning and asynchronous inventory journal. It does not need
the mod's native entity lifecycle, NBT hierarchy or a second request framework.
These are architectural comparisons; no upstream implementation is imported.

## Current execution path

```mermaid
flowchart LR
    profession[Profession discovery] --> work[CivWorldWork / CitizenPlan]
    work --> resident[Resident work cycle]
    resident --> navigation[Shared navigation]
    navigation --> observation[Observed arrival and action]
    observation --> authority[Current native authority]
    authority --> journal[Existing inventory journal]
    journal --> published[Published settlement revision]
```

Discovery proposes immutable work. It never credits items merely because time
passed or a route finished. The resident cycle observes the actual action. The
write lane rechecks the actor proof, permissions and real debit, then persists
the accepted result before publication. Cosmetic work without items remains
outside the inventory journal.

### Resident execution

`CivsCitizens` owns admission, pulse ordering, event integration and retirement.
`CitizenRuntime` holds shared resident identities, clocks, aggregate budgets and
lifecycle facts. Each activity owns its private queues and cursors:

- `CivsCitizenPlanning` selects the current immutable plan.
- `CivsCitizenWork` orders competing needs and advances the common work cycle.
- `CivsCitizenRoutes`, `Motion` and `Collisions` own travel, recovery and yielding.
- `CivsCitizenLife` owns meals, tavern service, rest and lessons.
- `CivsCitizenLogistics` and `Couriers` own observed transfers and their callbacks.
- `CivsCitizenLeisure`, `Shelters` and `Combat` own their interruption and cleanup.

The generic world-work path consumes `CitizenPlan` and `CivWorldWork`. Ordinary
new gathering professions do not add branches to its phase loop. The existing
builder, guard, child and tavern-specific behavior remains explicit.

The worksite composition registers `CivsFarmWorksites`, `CivsForestryWorksite`
and `CivsMineWorksite` behind `CivsProfessionWorksite`. They share the existing
discovery budget and return `Selected`, `Retained`, `Pending` or `Unavailable`;
the selected value is still the same immutable `CivWorldWork`. Each owner keeps
its own zone knowledge, current-target validation and assignment/chunk cleanup.
Saved mine receipt recovery stays separate from this discovery contract.
`BuildingType.isWorldWork` is the domain capability; the compatibility `TYPES`
set derives from it rather than maintaining a second handwritten list.

### Native world and inventories

`CivsWorld` owns one aggregate native scan queue and budget. `CivsWorldScan` owns
one admitted request's captured state; it cannot create another scheduler.
`CivsTerrainPolicy` supplies the common natural-ground and equivalent-state
rules. `CivsRoadWorld` owns current road-maintenance authority and geometry.

`CivsStorage` owns reservation, protection and reconciliation. Preparation is
separated into `CivsStoragePlanner`, `CivsResidentStorage`, `CivsStorageContainers`
and `CivsStorageItems`. Their output still enters `CivsInventoryTransaction` and
the same `CivsInventoryJournal`; there is no alternative native debit path.
`CivsWorkerCommits` confirms observed work through that existing city authority.

`CivsRules` remains the pure domain entrypoint. `CivsConstructionRules` owns
construction and payment transitions; `CivsSnapshotValidation` owns immutable
saved-state integrity. World observations are not snapshot-integrity checks.

`CivsService` owns city mutation, publication, load and drain. Its read-only
`CivsCityRecords` view lets request owners observe current records without
granting another publication path. Placement drafts, accepted building orders,
resident supplies, cargo, storage actions and deletion each keep their own
request/cursor state in their concrete owner. The root still persists city
records and applies accepted inventory receipts through the original journal.

### Menus

`CivsUi` owns the native dialog flow, current visit, click consumption and
asynchronous completion fence. The `Civs*Screens` files are Kotlin extensions
that compose existing pages against this one owner. A new page reuses `present`
and `perform`; it must not create another history, visit map or click guard.

## Add behavior at the actual seam

For a new gathering profession, add its domain type/recipe/tool/display
definitions and a worksite owner that produces the existing `CivWorldWork`.
Reuse the common discovery budget, current native authority and resident cycle.
Keep profession-specific zone knowledge with that owner, and register the
profession in the worksite composition. Do not edit the generic movement,
meal, journal or item-transfer implementations for a normal new job.

For a genuinely new activity competing with work, give it a concrete owner and
explicit resident state. Add one scheduling point at the intended priority;
define interruption and retirement with that owner. Test interruption during
walking, observed action and asynchronous completion. Reuse Life or Leisure
when the behavior is another variant of their existing activities.

Use a common contract for actual interchangeable implementations. A dynamic
registry, generic action DSL or factory with one implementation does not make
an activity easier to add. Keep the extension surface smaller than the feature
logic it hides.

## Changed-world policy

There are three distinct kinds of context:

1. A discovery proposal or zone cache can be refreshed locally. Safe natural
   ground drift is captured as the current original; production selection does
   not change a building's layout or invalidate its retained access proof.
2. An actor action needs its current assignment, route and observation proof.
   A changed target invalidates that action and permits rediscovery; it does not
   make unrelated residents or the town unhealthy.
3. An accepted inventory/payment receipt owns exact items and an operation
   identity. Preserve its debit, duplicate suppression and restart recovery.
   Conflicts here cannot be silently treated as cosmetic drift.

Every native write still checks current modification authority and protects
containers with contents, control blocks and unrelated buildings. No stale
async result may replace a newer accepted request. Keep failures and retries
local to the smallest operation that can safely recover.
