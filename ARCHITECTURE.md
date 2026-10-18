# ArcCivs settlement contract

Players found one settlement, choose a curated imported building style, place
and preview a town hall site, welcome gradual pioneers, assign a hut builder, build production and housing,
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

Seven building types: TOWN_HALL, BUILDER_HUT, WAREHOUSE, HOUSE, FORESTER,
QUARRY, FARM; levels1..3. New placement creates a SITE and its physical barrel
control at the selected ground approach, outside the schematic write volume.
It does not paste the completed building. The first pioneer arrives after30s,
then one per60s while a viewer is nearby; each arrival commits its remaining
receipt before spawning. A restart never grants four more residents or catches
up multiple arrivals. Idle residents choose bounded safe loaded wander targets.

The SITE hall supplies the initial four beds and a depot. A SITE builder hut
has one assignable slot: its builder can construct that same hut first, then
other buildings. New worker assignments never use the hall as a workshop.
Population remains4/8/12 by hall level,12maximum; houses add2/4/6beds. Building
limit16 and native NPC limit48 remain. Completed hall upgrades expand the actual
persisted radius by32, including96→128→160 for new default cities. New founding
reserves future maximum spacing; existing cities keep their actual radius.

Items replace the closed WOOD/STONE/FOOD ledger. One-time founder reserve is
96OAK_LOG,96COBBLESTONE,24BREAD. Legacy remaining stock converts1:1 exactly once
when its old paid construction is complete; legacy snapshots and orders decode
unchanged. Virtual maps contain plain obtainable vanilla materials only; custom
items remain untouched in physical inventory. There is no Vault or token payout.

Each building has a paginated virtual warehouse of1728/3456/5184items by level,
plus its real27-slot control barrel for input/output. Explicitly registered city
chests/barrels are physical sources; only one half of a double chest is used.
PHYSICAL mode consumes loaded containers first, then the common reserve and
building warehouses; VIRTUAL consumes those durable virtual maps. Mode changes
create no items. Deposit moves actual control-barrel cargo to that building's
warehouse; withdrawal moves up to64 items to its barrel, using its own warehouse
then the common initial/legacy reserve. No direct player-inventory automation.

Production requires an observed completed NPC round trip and the old60s work
period plus travel. Logs yield4×level, stone and raw crops3×level; bread applies
the vanilla3-wheat recipe and yields1×level. The chosen output is persisted per
building. Physical mode inserts real items without drops, virtual mode credits
that producer's bounded warehouse. A full destination pauses delivery. Recruitment
consumes12BREAD after all population/bed checks. No offline production/catch-up.

All item movement uses exact native slot before/after receipts stored by Core
AtomicFileStore on the I/O executor before native writes. One locked city transaction
closes container viewers and blocks inventory edits while in flight. It checks all
changed slots before any writes; restart may finish only known before/after states.
Drift is an explicit conflict. The city revision commits after native application;
only an exact same-revision retry is accepted. Unknown outcomes retain the receipt,
freeze city mutations and reconcile against canonical SQL on reload. Native chunk
persistence and SQL are separate systems: this is guarded replay, not a distributed
atomic transaction; a power-loss rollback of already-acknowledged chunk data remains
a platform persistence boundary. No blind repeat after an unknown result.

Construction reserves exact obtainable materials for the immutable captured
before/after plan once. Matching existing blocks need no replacement. A builder's
observed trip authorizes one deterministic horizontal tile of at most256cells;
Core's provider-owned crop/paste does the bounded native writes. Only its successful
completion advances the durable tile cursor. Callback fencing includes order and
expected cursor, not just the order UUID. PLACING restart rechecks that same tile
and accepts only planned before/after states; completed tiles are not repasted.
Old paid orders retain their original whole-plan algorithm. Mutable source files
cannot change an accepted design. Full snapshots are released after each step.

World placement validates ownership, loaded chunks, borders, Lands, building boxes
and foreign controlpoints. It preserves nonempty inventories and registered storage.
Owners and members may remodel ACTIVE buildings and unstarted SITE areas with normal
drops. Accepted construction footprints and control blocks remain locked. Environmental
and other-plugin schematic guards retain protection, so strangers cannot bypass it
with explosions or pistons. Native control inventory remains accessible to members.
Personal cyan boundary cues use Core packet displays within24blocks of an edge,
with at most60parts per viewer and no perimeter/world scans.

## UI

/civs (alias /city) opens a native dialog hub. Founding, buildings, stock,
workers, upgrades and members are separate stable screens. Right-clicking a building control opens that building: level, work assignments, output choice, materials and its paginated warehouse. Selection and
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

Founding and new-building forms start a service-owned ground selection and close
the dialog before reading any aim target. A persistent action-bar instruction
refreshes every 20 ticks; a light-blue twelve-edge frame marks the ground support.
The main-hand right-click supplies the actual clicked block, with the same bounded
plant-to-support resolution used by the marker. There is no second aiming ray
after the click. Shift + right-click, menu reopen, logout, world change, expiry,
reload and shutdown retire the selection. Its identity fences asynchronous
blueprint completion; a rejected location preserves the draft for the next click.
Ground selection neither captures world blocks nor writes stock or city state.

After a successful ground click, prepared placement uses Core `PaperActionPanels` through
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

## Migration and recovery visibility

All loaded inventory receipts acquire native container locks before the service
publishes readiness. Recovery may process one receipt per pulse, but players
cannot alter later receipts' before/after slots while they wait. A known SQL
rejection preserves the founding preview; an unknown commit result freezes
changes until canonical reload.

A migrated city already containing sixteen buildings and no builder hut retains
one builder assignment in its active hall. This narrowly avoids an irreversible
bootstrap deadlock in old full cities. New city placement reserves a hut slot,
so the compatibility condition is unreachable through the new normal flow.
