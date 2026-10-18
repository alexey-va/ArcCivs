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
QUARRY, FARM; levels1..3. The curated catalog remains63 assets across21
style/type families. Each schematic has one `minecraft:structure_void` primary-hut
marker inside the building; it is the physical control position, not the authored
origin or visual entrance. The primary ground-click flow aligns marker X/Z with the
selected ground X/Z, leaves the schematic anchor at ground Y and places the control
at ground Y plus the marker's authored Y-minus-origin offset. Clicking an existing
BARREL at the exact control XYZ is an alternate selection path. The visible entrance
is independent. The player supplies the BARREL; no control item is minted, and new
ACTIVE controls are never auto-recreated. A newly
placed SITE creates a control at the marker without pasting the completed building.
The first pioneer arrives after30s,
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

PHYSICAL is the default and uses only actual native items. Each building's real
27-slot control barrel is its primary warehouse. All CHEST/TRAPPED_CHEST/BARREL
blocks inside its 3D bounds are indexed automatically. A double chest contributes
each eligible 27-slot half once. Existing explicit city registrations remain
compatible. Discovery reads loaded chunk tile entities on geometry registration
and chunk load, updates individual placement/break events, and refreshes affected
building chunks after a paste. Availability and production never rescan world
blocks or chunk tile entities. Unloaded chunks are not loaded for storage.

VIRTUAL is an explicit opt-in with paginated warehouses of1728/3456/5184items by
building level. The control barrel remains the visible input/output for transfers.
An owner or city member with management access may explicitly rebind only a legacy
ACTIVE building's control through its building menu after placing a new player-owned
BARREL at the target coordinates. The old barrel and its inventory are never moved
or cleared; the old container is
automatically registered as an additional warehouse within the 16-container limit.
The city UUID and stocks remain unchanged. SITE buildings and buildings with an
accepted construction cannot be rebound.
PHYSICAL consumes loaded containers only; VIRTUAL consumes its durable maps.
Existing common/building reserves in PHYSICAL are pending delivery, not invisible
spendable materials. Materialization uses the same durable slot receipt, examines
at most 128 containers and changes at most 1024 slots per attempt, preserving any
remainder when full. Control barrels have priority; other containers rotate fairly
even after a full/no-op attempt. Each selected container is read at most once. Opening
a physical warehouse or switching to PHYSICAL attempts it; nearby cities with
pending items also retry one city per5s fairly. Mode changes create no items.
Deposit in VIRTUAL moves actual control-barrel cargo to that building's
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
before/after plan once. The unique marker cell is omitted from new paste plans;
matching existing target blocks need no replacement. Core's provider-owned paste
splits new placement around the control into at most six crops, applied sequentially
in one batch while preserving the barrel and its inventory. Durable completion
advances the plan, and restart recovery accepts only known planned before/after
states. Callback fencing includes the order and expected cursor, not only the UUID.
Previously captured paid plans retain their stored snapshots and original algorithm;
mutable source assets cannot change an accepted design. Full snapshots are released
after a batch.

World placement validates ownership, loaded chunks, borders, Lands, building boxes
and foreign controlpoints. It preserves nonempty inventories and registered storage.
Owners and members may remodel ACTIVE buildings and unstarted SITE areas with normal
drops. Accepted construction footprints and control blocks remain locked. Environmental
and other-plugin schematic guards retain protection, so strangers cannot bypass it
with explosions or pistons. Native control inventory remains accessible to members.
Personal cyan boundary cues use Core packet displays within24blocks of an edge,
with at most60parts per viewer and no perimeter/world scans.

## UI

/civs (alias /city) opens the city overview. Its **Управление поселением** submenu
contains storage, development, members and owner-only deletion. Founding and building
screens remain separate. **Гайд** holds the explanatory boundary, sleep and placement
instructions, keeping overview focused on state and actions. Right-clicking a building
control opens that building: level, work assignments, output choice, materials and
its native barrel by default (paginated warehouse only in VIRTUAL). Selection and
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
refreshes every 20 ticks. The primary-hut marker is the unique inside-building
`structure_void` cell in the curated schematic; it is separate from the visible
entrance and authored origin. Right-clicking ground starts the preview and selects
ground X/Z. The schematic anchor remains at ground Y; the control coordinate Y is
ground Y plus the marker's authored Y-minus-origin offset. The player places a BARREL
at that control coordinate; an already placed BARREL can instead be right-clicked
at its exact control XYZ. There is no second aiming ray after the click. Shift +
right-click, menu reopen, logout, world change, expiry,
reload and shutdown retire the selection. Its identity fences asynchronous
blueprint completion; a rejected location preserves the draft for the next click.
Ground selection neither captures world blocks nor writes stock or city state.

After a successful ground click, prepared placement uses Core `PaperActionPanels` through
`CivsPlacementControls`. The main page offers confirm, anchor movement, both
quarter-turn rotations, optional details and cancellation. Movement has a separate
page of six one-block nudges, Done and Cancel; a ground right-click relocates the
whole preview. The green marker identifies the inside-building control coordinate;
cyan marks the separate visual entrance and the light-blue frame shows the ground
destination. Upgrades preserve the existing entrance and rotation.
Both control pages face the authored control point: normal controls from 2.8 blocks,
move controls from three. Their
two columns and left-aligned status leave a clear central corridor for seeing and
placing the barrel; move controls also sit slightly higher to keep terrain picking
clear below them. Core still owns obstacle avoidance, reach checks and hit testing.

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
