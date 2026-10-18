# ArcCivs settlement contract

Players found one settlement, choose a curated imported building style, place
and preview a town hall, assign four pioneers, build production and housing,
recruit residents, and upgrade buildings/town hall through three levels.
The settlement survives reload/restart. Owner-managed membership grants city
management; unrelated players cannot build, break or loot city structures.

Core 2.7.41 is mandatory: lifecycle/scheduling, SQL, atomic files, localized
text, native dialogs/tables, packet displays and the host-owned asynchronous
schematic service. NPCs use Citizens ordinary bounded point-to-point navigation;
there is no Core packet-NPC API. No duplicated pools, schematic cache or display
transport. No schematic IO or future waiting on the gameplay thread.

## Network authority

Every backend has an explicit, stable typed `server-id` and the same shared SQL
configuration. Core's `PaperSqlServices` owns the provider lease; blocking runtime
creation, migration and JDBC work run on workers. Shutdown seals admissions,
drains admitted file-to-SQL chains and SQL work, then releases the lease. There
is no local write fallback when SQL fails and no supported mixed old/new writer
deployment. Missing identity/SQL configuration stops startup with an actionable
error; settings changes and initial SQL failure recovery require a restart.

`arc_civs_city` stores the canonical validated settlement snapshot, immutable
home server/owner, revision and update time. `arc_civs_member.player_id` is globally
unique for both owners and members. A short registry row lock serializes writes
and multi-city imports; each save still enforces home and revision CAS. The only
successful same-revision retry is the exact committed state. Membership conflict
rolls the transaction back before any publication, world changes or start grant.
An unknown commit result suspends the affected authority until canonical reload.

Existing city JSON imports atomically, preserving originals. An existing SQL
city with the same home/owner wins over its stale JSON; conflicting identity or
membership fails closed. SQL city writes never update the legacy files. Immutable
construction plans and captured schematics remain local to the home backend.
`maximum-settlements` is per home backend, as the old file limit was per runtime.

Only `loadOwned(server-id)` records reach protection, construction and Citizens.
Foreign snapshots occupy a separate UI-only map and cannot be mutated or paid
out locally. `/civs` reads membership afresh on every open; Refresh repeats it,
with no background sync. Read failure shows an inline error and never presents
founding from unknown membership. The remote overview offers Refresh and a
typed Core Bungee backend request. SENT means requested, not arrival or teleport
to the city. Empty allowed-worlds makes a backend view-only.

## Domain

Six building types: TOWN_HALL, WAREHOUSE, HOUSE, FORESTER, QUARRY, FARM; levels
1..3. Internal resources WOOD, STONE, FOOD cannot be withdrawn as items/money.
Food pays for recruitment/development; ordinary work does not consume food.
Starting stock is 96 wood, 96 stone, 24 food, with four pioneers. This grant is
part of the durable city creation, never repeated on join/reload.
The network conversion changes no prices, rates, grants or caps. Remote viewing
produces zero WOOD/STONE/FOOD and introduces no external currency or item flow.

Town hall limits population to 4/8/12 and supplies four beds, base storage128,
one builder slot and one construction project. Houses add2/4/6 beds; warehouse
adds256/512/1024 capacity per resource. Production buildings have1/2/3 jobs,
produce4 wood or3 stone/food per60seconds times their level, and require a real
worker trip to the workplace and then storage. Recruitment costs12food.
Population cap12, buildings cap16, active NPC cap48 server-wide. No offline
production/catch-up; cities without nearby viewers sleep. Player-visible pause
reasons include no assignment, unreachable route and full storage.

Building resources are reserved exactly once before construction. Construction
has one durable identity and explicit preparation/placement/completion states;
restart recovery continues the same operation without charging again. Placement
validates city ownership, bounds, collisions, loaded chunks and Lands access.
Foreign world changes are not silently overwritten. Protected buildings cannot
be dismantled into free resources. Mutable source schematics affect future
requests; an already accepted construction keeps its captured immutable plan.

## UI

/civs (alias /city) opens a native dialog hub. Founding, buildings, stock,
workers, upgrades and members are separate stable screens. Selection and
confirmation revalidate current city revision and authority; async callbacks
cannot open a stale preview. Menus use Core tables/frames, light body text,
plain untrusted placeholders and RU/EN locale resources. Admin status/recovery
is permission gated. Suggestions reflect the exact argument only.
Founding failures lead with a DANGER heading and concrete reason above the style
table, preserve the draft and offer a working retry. Runtime DOWN is shown as an
explicit storage failure rather than indefinite loading. Diagnostics use Core
StructuredDebugLine/ArcLogging for source-owned callback and operation boundaries,
with bounded sanitized input, correlation IDs, outcomes and exception stacks;
they do not claim visibility into packets discarded inside Core.

Prepared placement leaves the dialog and uses Core `PaperActionPanels` through
`CivsPlacementControls`. The main page offers confirm, anchor movement, both
quarter-turn rotations, optional details and cancellation. Movement has a separate
page of six one-block nudges, Done and Cancel; a ground right-click relocates the
whole preview. The green twelve-edge support block is also a direct movement
entry. It is the normalized support pivot below the walkable entrance and outside
the building footprint; the authored origin stays fixed. Cyan marks the entrance; a light-blue frame
shows the ground destination. Upgrades keep their existing position and rotation.
Move controls sit slightly higher and farther away to leave a clear terrain-pick
corridor below them; the normal placement page uses the shared Core defaults.

`CivsService` keeps the last valid preview until an asynchronous replacement is
ready, blocks confirmation during that load and fences obsolete completions.
Every accepted replacement receives a fresh context identity. Final authority,
world validation, captured schematic bytes and durable reservation remain in the
service/world owners. Dialogs suppress the panel only while actually open; they
do not own movement or confirmation. No physical blocks change for previews.

## Delivery

Build and focused domain/platform/recovery checks, consumer/package checks,
then production deployment and full native player journey. Lab/production
fixtures must preserve existing users and have an explicit cleanup record.
