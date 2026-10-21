# ArcCivs settlement contract

Players found one settlement, choose a curated imported building style, place
and preview a town hall site, welcome gradual pioneers, assign a hut builder, build production and housing,
recruit residents, and upgrade buildings/town hall through three levels.
The settlement survives reload/restart. Owner-managed membership grants city
management. Owners and members may remodel their structures, including construction
footprints at every phase; unrelated players and environmental changes remain guarded.

ArcCore host 0.1.19 and bundled Core helpers 2.7.50 are mandatory; the shared
API is byte-identical to the host API. ArcCore provides lifecycle/scheduling,
SQL, atomic files, localized text, native dialogs/tables, packet displays and the host-owned asynchronous
schematic service. Human residents use Core's `PaperPacketNpcs` and
`PacketPlayerNpc` with bundled signed skins, equipment and work-only arm swings.
No resident Bukkit entity, native inventory or hitbox is registered. Core owns
profile/spawn/metadata packets, viewer/chunk lifecycle and validated interactions;
ArcCivs owns authoritative positions, paths, work and health. Resident nameplates
and courier parcels attach to the same packet actor through an admitted per-viewer
carrier lease. Skins are stable per worker UUID; no profile network lookup occurs.
Work props never enter city storage. Supplied personal tools, armor, food and consumables are recorded separately in the city snapshot. Residents expose a native
inventory menu backed by 33 saved slots: six equipment slots and 27 bag slots. Quick transfers preserve
item metadata and use the same inventory journal; returned items go to the player's
inventory, not the warehouse. One bread heals six health points. The legacy supply
action offers up to 16 bread; the native bag uses its actual slot capacity. Armor
equips native slots; a supplied matching iron work tool reduces incoming
damage by ten percent. Props alone confer no bonus. Residents start with 20 health;
each new completed profession cycle earns one internal point, with ten points per
level and a level-five cap. The highest profession level adds two maximum health
per extra level, up to 28. New cycles train the currently assigned profession;
skill progress is persisted separately by profession and remains after reassignment.
Old cycles grant no retroactive points. Production
recipes consume actual inputs; gathering requires the corresponding world work.
The worker tool request is derived from the current active assignment and equipment,
not a persisted quest or reward. It transfers one real matching tool: lowest material
tier first, then the existing enchantment/durability ordering. The debit uses the
existing native inventory journal. Consumable requests target one native stack,
minus personal and local stocks; ingredients missing for the next recipe take
priority over reserve top-ups. The same red/green request action appears in the
resident card and building menu. Personal donations accept partial amounts and
consume carried plain ingredients before the warehouse remainder in the existing
work receipt. Renamed/custom supplies are excluded from automatic consumption.

Damage from players, monsters and the environment enters the virtual resident
damage owner. The actor stops work while its injury waits for the current city
write; health or permanent removal is committed before visual health/despawn changes.
Failed/unknown injury commits remain frozen until a successful canonical reload.
There is no native regeneration. Viewer/chunk retirement preserves stored health
and personal inventory; it cannot heal or resurrect a dead worker. Nearby idle
monsters approach residents through bounded round-robin checks; existing monster
targets take priority. `CivsResidentDamageEvent` is the cancellable integration
boundary for virtual residents; native `EntityDamageEvent` listeners do not receive
fake entity events. Player attacks also enforce world PvP and optional Lands
attack permission. Death destroys carried
supplies without drops or XP and frees the job/population slot. Free arrivals
refill vacancies below five residents, including before hall completion. Above
five, opposite-sex adults form families in completed houses: 20 active city
minutes per birth, then 40 to adulthood. Children use half-scale player NPCs,
play and wander, and cannot take jobs. Progress never catches up offline.
Working adults lose one hunger per active minute and consume existing bread
from their bag; hunger at or below six doubles work time. Family, hunger and
member-name changes use the existing city write queue, without starving behind work. `CivsResidentNavigation` incrementally plans
bounded routes in loaded chunks, sharing at most 96 search steps or one millisecond
per tick across residents. Each route visits at most 4096 nodes; an edge checks a
0.6-by-1.8-block body and at most one block of ascent or descent.
Movement revalidates live collision and support before accepting a step; work
checks the actual position independently of planner completion. A failed or stuck
route may recover to a validated loaded safe endpoint, resetting footsteps.
The same arrival, work and cycle-token checks still apply. Actual work-route
crossings use Trails 2.3.20's `TrailsFootTrafficActor` entry point; Trails retains
the ordinary permission, protection, terrain progression and persistence pipeline.
Completed work trips teach a bounded shared corridor preference (32 cities,
512 outdoor cells each, 16 admitted cells per tick); authored buildings and
fields remain excluded. Terrain conversion belongs solely to Trails. Support
height includes paths and slabs. Player movement is never cancelled or rewritten
by resident contact. Overlapping bodies receive at most one horizontal 0.05 push
per tick; the resident yields into supported, collision-free space or a free side.
A trapped resident remains passable to the player. Navigation still yields to
real players without creating native NPC entities. Native world work uses a
three-block eye-to-focus reach and unobstructed ray, stopping travel as soon as
that real block is reachable. Storage accepts any visible valid side of its
actual barrel. A courier shows one compact back parcel only while carrying goods;
it remains inside the normal resident's horizontal body envelope.
No duplicated pools, schematic cache or display transport. No schematic IO or future waiting on the gameplay thread.

Construction uses the host's paced schematic overload: at most one accepted
block or indivisible pair every five ticks. Its primary-thread readiness check
requires an arrived, stationary, awake builder for the current order. Unavailable
builders suspend native placement without discarding durable material credit.
Accepted native writes drive the builder's held material, gaze, swing and local
feedback; cosmetic failures cannot invalidate an already accepted write.
The builder remains at the worksite through native completion and durable commit.
Committed batches enqueue neighbour physics for their actual changed cells;
Paper receives both neighbour and client-update flags so unsupported plants
disappear on clients as well as on the server.

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
`CivsStore` owns the one defensive snapshot copy before async admission;
`CivsNetworkStore` persists that same immutable revision without making a second copy.

Existing city JSON imports atomically, preserving originals. An existing SQL
city with the same home/owner wins over its stale JSON; conflicting identity or
membership fails closed. SQL city writes never update the legacy files. Immutable
construction plans and captured schematics remain local to the home backend.
`maximum-settlements` is per home backend, as the old file limit was per runtime.

Only `loadOwned(server-id)` records reach protection, construction and resident actors.
Foreign snapshots occupy a separate UI-only map and cannot be mutated or paid
out locally. `/civs` reads membership afresh on every open; Refresh repeats it,
with no background sync. Read failure shows an inline error and never presents
founding from unknown membership. The remote overview offers Refresh and a
typed Core Bungee backend request. SENT means requested, not arrival or teleport
to the city. Empty allowed-worlds makes a backend view-only.

## Domain

Eleven building types: TOWN_HALL, BUILDER_HUT, WAREHOUSE, HOUSE, FORESTER,
QUARRY, FARM, SAWMILL, BLACKSMITH, STONEMASON, LIBRARY; levels1..3.
The curated catalog contains99 assets across33
style/type families. Each schematic has one `minecraft:structure_void` primary-hut
marker inside the building; it is the physical control position, not the authored
anchor or visual entrance. A selected block's X/Y/Z is the schematic anchor without
an added height offset. The authored marker is transformed independently from that
anchor and rotation. Clicking a BARREL uses the same selected-block contract as any
other material; it does not align the internal marker to the clicked barrel.
Control-position lookup reads lightweight schematic marker metadata rather than
preparing full placement geometry.
The visible entrance is independent. Confirming a SITE grants one BARREL at that authored marker for free,
without pasting the completed building or charging a barrel as construction material.
The same city commit records its expected prior block in `pendingControls` and protects
the marker before native installation. Recovery accepts that prior block or an existing
BARREL, preserves its inventory, then retires the receipt. The same durable receipt can
prepare up to three entrance cells once: at most one free DIRT support and two AIR
cells. Ordinary terrain and crafted blocks, including lava, may be replaced; bedrock,
TileState/container contents and external protection remain guarded. This preparation
is not a periodic repair. Completed grants never regenerate
on a timer; normal block protection prevents harvesting controls, while external deletion
requires restoring the missing barrel. Members may restore an eligible missing
control barrel on an ACTIVE building; existing control blocks remain protected.
A conflicting block is retained and reported.
The first pioneer arrives after 30s,
then one per 60s while a viewer is nearby; each arrival commits its remaining
receipt before spawning. A restart never grants extra residents or catches up multiple arrivals. At night, residents shelter in their active house when
available and otherwise use the town hall, then enter the native sleeping pose.
Idle residents choose safe loaded wandering targets within eight blocks of their
workplace, otherwise home or town hall, at 0.55 of work speed. Base work speed is
0.215 blocks per tick before the current surface multiplier. Routes avoid horizontal footprints
of foreign buildings and farm fields; idle wandering does not generate Trails wear.
Idle/evening campfire visits use existing lit campfires within 24 blocks of the
resident's anchor. Discovery examines at most one loaded chunk per pulse, caches
at most 256 chunks for 120 seconds and invalidates affected chunks on block events.
Six distinct safe standing positions avoid controls, fire and occupied seats;
travel expires after 45 seconds and rest lasts 25–45 seconds. Work, cargo and
night sheltering supersede leisure. No chunks are loaded for discovery.
All route segments, safe endpoints and collision nudges reject lit campfire
cells touching the resident's body or support. Execution rechecks live heat;
a resident already touching fire may leave it. Fire damage remains active.
Optional resource-pack speech uses a stable voice profile per worker UUID.
Only human viewers within 12 blocks hear ambient speech; sleeping or moving
residents do not speak ambient lines. Per-resident intervals are 15–25 seconds,
with a three-second shared minimum. Every intentional click greets its viewer,
independently of the ambient cooldown. Campfire, sleep and actual speech diagnostics are emitted on transitions.
One eligible nearby city per pulse fills vacant jobs in one persisted revision,
builders first, one warehouse courier next, producers next, town-hall scribes last. Existing assignments and completed-cycle tokens
are preserved; full jobs and inactive production sites are skipped.

The SITE hall supplies the initial four beds and a depot. A SITE builder hut
has one assignable slot: its builder can construct that same hut first, then
other buildings. Completed builder huts and ordinary town halls have one/two/three
worker slots at levels one/two/three, as existing production buildings already do.
Ordinary hall workers are scribes: one observed 60-second work session at the
workplace converts three plain PAPER and one LEATHER into one BOOK. Inputs,
output, cycle token and internal profession point commit in the same receipt.
SAWMILL converts one matching log into four planks; BLACKSMITH crafts one plain
iron tool with its ordinary ingot/stick recipe; STONEMASON converts four STONE
into four STONE_BRICKS; LIBRARY converts BOOK, FEATHER and INK_SAC into one
WRITABLE_BOOK. All use 60-second observed work, fixed per-cycle
recipes and one/two/three slots. The same receipt consumes inputs, credits output
and commits the cycle and profession point for all processors.
Missing inputs or a full warehouse
retain the completed trip and retry after five seconds, without granting output
or points. Houses have no work slots. Active item-storage warehouses have one/two/three
courier slots; autoassignment fills one courier before producers and leaves extra
warehouse slots for explicit assignment. Legacy stock-only cities
retain hall builders; so do migrated full cities without room for a hut.
Population remains4/8/12 by hall level,12maximum; houses add2/4/6beds. Building
limit16 and native NPC limit48 remain. Completed hall upgrades expand the actual
persisted radius by32, including96→128→160 for new default cities. New founding
reserves future maximum spacing; existing cities keep their actual radius.

Materials pages show16 rows and provide one explicit player supply action plus
an admin-only deficit grant. Both subtract existing town stock and fit physical
slot capacity. Player supply journals exact plain-slot before/after debit together
with container credits, locks the source inventory, and uses Core native player
data persistence before applying container credits. Recovery waits for an offline
source player; unknown slot drift remains a conflict. Initial/reload item changes
wait for local receipt discovery; exact locks are installed and unrelated players
are released before network city loading begins. Local journal failure remains
explicitly blocked instead of being treated as an empty journal. Old receipts
without player debits remain compatible. No full player state is restored or virtual surplus created.

World capture/revalidation shares up to2048 cells across four scans per tick,
yielding after the2ms budget between native calls and rotating the next scan.
A single native call is not preempted. Builder availability is checked before
schematic recovery/capture and again after asynchronous work. Scan completion
logs phase, cell count and elapsed time independently of SQL timings.

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
ACTIVE building's control through its building menu. The authored marker barrel is
installed automatically through the same one-time receipt; upgrade confirmation also
performs this rebind when needed. The old barrel and its inventory are never moved
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

Scribes and the four processors remain observed 60-second work sessions with
real ingredients and outputs. FARM, FORESTER and QUARRY are physical worksites,
not timed item grants: FARM tills real soil, plants and waits for mature crops
before harvesting native drops (1.5 seconds per action). No new bread-baking jobs
are created; a legacy BREAD selection resolves to wheat harvesting.
When no actionable field job is found in the bounded plot slice, FARM tends an
unripe crop for four seconds. Watering maintains soil moisture and advances only
age 3 to 4; optional fertilizer consumes one real bone meal at age 5 to reach 6.
Other care visits animate without inventing world changes. Native age checkpoints
bound each growth-cycle bonus without a separate timer or restart catch-up.
Care retains field reach, protection and receipt validation; it grants no drops,
profession training or hoe wear. Existing hunger still applies while working.
FORESTER fells a real tree of at most 128 logs per
action, taking max(6 seconds, 1 second per log), yields native tool drops and
replants only after spending locally stocked saplings: one for ordinary trees,
four in a 2×2 plot for dark oak. Each tree receipt records at most 128 exact
physical block changes. Native Unbreaking durability applies only to changed
non-air source blocks.
QUARRY advances an authored real shaft across multiple floors. Before excavation,
the miner needs a pickaxe and carried ladders, torches and cobblestone. If any
supply runs out, one storage receipt tops the bag up to 16 ladders, 8 torches and
32 cobblestone before work resumes; it does not alter blocks or mining progress.
Mining spends carried supplies and harvests native drops one block per 4-second
action. The three worksites therefore do not mint resources on a timer.
Item-storage processors consume carried ingredients first, then their current
control barrel or virtual building map; output remains in the building store; couriers move their goods to the current building endpoint.
FARM and FORESTER perform work directly at field/tree cells, without a per-cell
return trip. Legacy abstract-stock producers retain their old return trip. The chosen processor output is persisted
per building; a full destination pauses delivery. Farming takes 1.5 seconds per
physical action (3 seconds when hungry), retaining native crop growth and drops.
No offline production/catch-up.

## Warehouse logistics

Orders are derived from local ingredient shortages: one recipe cycle per assigned
worker, less local stock and incoming cargo. Construction orders cover only the
current `payment.awaiting` row; a site spends its own control-barrel stock. Active
warehouse upgrades may use all their own internal containers. Couriers prefer
construction requests, then rotate ingredient deliveries and collection of the currently
selected output from other buildings by completed shipment count. Source-to-destination entrances must be within the existing
88-block work-route bound. Warehouses use their own indexed native containers;
ordinary producer sources and destinations are strictly their control barrels.
Worksite orders include one spare IRON_HOE, IRON_AXE or IRON_PICKAXE per assigned
worker, according to job; FARM requests only its selected seed type.

`CivWorker.cargo` records one material, at most64 items, original source entrance
and stable shipment identity. Pickup journals source debit and cargo together;
the actor waits for that receipt before travelling. Unload journals destination
credit, cargo removal, one completed cycle and one WAREHOUSE profession point.
Planning revalidates demand and actual stock at pickup; incoming cargo prevents
duplicate orders. A pending receipt retains the actor's shipment plan. Couriers
never use teleport recovery and restart at the recorded source before travelling
to the destination. Missing destinations return to a reachable surviving warehouse
or hall, within the same route bound. Reassignment
does not discard carried goods; death intentionally destroys cargo without drops.
Full destinations keep cargo and retry, and ordinary open containers remain open.

All item movement uses exact native slot before/after receipts stored by Core
AtomicFileStore on the I/O executor before native writes. Synchronous planning
freezes source membership, then only native containers changed by the exact receipt
stay locked through journal retirement. Unaffected containers remain open and
interactive. Explicit transfers close affected viewers; NPC/background transfers
use open containers immediately and keep their views open, with only receipt
containers' item edits briefly fenced until retirement. A source player's inventory remains locked
through its item receipt. It checks all
changed slots before any writes; restart may finish only known before/after states.
Drift is an explicit conflict. The city revision commits after native application;
only an exact same-revision retry is accepted. Unknown outcomes retain the receipt,
freeze city mutations and reconcile against canonical SQL on reload. Native chunk
persistence and SQL are separate systems: this is guarded replay, not a distributed
atomic transaction; a power-loss rollback of already-acknowledged chunk data remains
a platform persistence boundary. No blind repeat after an unknown result.

Construction starts without requiring the complete material list. New orders split the
immutable captured before/after plan into deterministic horizontal rows of at most16
adjacent cells with identical per-cell material requirements. One observed builder
session adds at most eight existing rows after two seconds of work. Concurrent
builders add once-only durable credit, bounded by remaining rows and 96 total rows;
one placer still charges and installs each row serially. Durable work credit
preserves the remaining authorization across pause/restart; it does
not change row indices or charge already paid rows again. Only that row's exact obtainable items are debited, in the existing
durable inventory receipt together with the READY→PLACING transition and `reservedBatch`.
Missing items persist WAITING_MATERIALS without any debit; one waiting city is retried
per5s in round-robin order. Open warehouse viewers do not defer it. Adding
the required items resumes automatically. `materials` holds the unpaid remainder.
Paid rows retain their reservation across pause, retry and restart, so replay cannot charge
twice. Completing the row clears its reservation and advances within the bounded
session while credit remains; the next trip is required when that credit ends. Legacy
orders with null payment state retain their already-paid 256-cell tile algorithm.
New SITE plans and accepted ACTIVE-building upgrades for the next level share a
persistent FIFO queue, with only one active construction. The unique marker cell is omitted from new paste plans;
matching existing target blocks need no replacement. Core's provider-owned paste
splits new placement around the control into at most six crops, applied sequentially
in one batch while preserving the barrel and its inventory. Durable completion
advances the plan, and callback fencing includes the order and expected cursor, not
only the UUID. Revalidation checks only the batch about to be placed: current order
identity, loaded chunks/borders, Lands authority, bedrock and nonempty containers.
It does not compare future cells to their originally captured world preimage or keep
extra per-cell world snapshots. The immutable captured schematic remains tied to the
accepted plan, but owners and members may alter its cells at every construction phase.
Committed batches are not checked or restored if a player later breaks or rebuilds
them. If a crash interrupts an uncommitted batch, that batch may be applied again;
its durable material reservation prevents a second charge, but there is no per-cell
exactly-once guarantee.

Paired and multi-block cells such as doors, beds and double blocks are applied as
one native placement unit, so a batch cannot leave only half of a pair. Finished
buildings retain native physics; only the active placement and exact item-receipt
cells are fenced while their operation is in progress, and receipt cells stay locked
through commit. After the durable build commit, neighbor physics is issued for exact
changed plan cells, including updates needed by vines beside removed logs. Cosmetic
shape repair is limited to exact-source fences, panes and walls; it skips AIR and
replacement blocks and never rebuilds a removed or remodeled structure.

World placement validates ownership, loaded chunks, borders, Lands, building boxes
and foreign controlpoints. It preserves nonempty inventories and registered storage.
Owners and members may place and break blocks inside their buildings, SITE plans and
accepted upgrades at every construction phase, with normal drops. Existing control
barrels remain protected (eligible missing ACTIVE controls may be restored), and exact
live receipt cells remain locked. Stranger, environmental and other-plugin
schematic guards remain active; owner/member editing does not bypass Lands, bedrock,
nonempty-container or construction identity checks. Native control inventory remains
accessible to members.
Personal cyan boundary cues use Core packet displays within24blocks of an edge,
with at most60parts per viewer and no perimeter/world scans.
The same owner and settlement pulse maintain three-line RU/EN holograms above the
saved control barrels: building name, current level and construction state or
assigned worker count. Only the nearest four controls within16blocks are shown;
labels respect terrain occlusion and never use the paste origin or a future upgrade
marker. State changes update existing packet handles. Leaving, deletion, reload and
shutdown retire them with the existing scene; no physical entities or world scan.

SITE plans may be placed while a different building is under construction. A
persistent ordered list of building IDs holds requested SITE jobs, with one active
construction. Duplicate requests are idempotent. Starting the next job reloads its
saved plan and captures/revalidates current terrain. A failed queue head retains
its position and exposes a concrete retry reason; it is not silently skipped.
Upgrades retain their existing active-order contract.

Building and city removal first persist a deletion intent in the city snapshot.
The service loads only the exact control chunks asynchronously, then revalidates
registered controls and Lands access on the main thread. Only BARREL controls are
removed; native contents are dropped with metadata, and the free barrel itself is
not granted as an item. Already absent/replaced controls are preserved on retry.
Final city deletion retains the existing tombstone contract; removing a queued or
active building clears its corresponding task and lets the next job proceed.
World blocks and dropped entities retain native save durability; this is not a
second escrow or a cross-system atomic transaction.

## UI

/civs (alias /city) opens the city overview. Its **Управление поселением** submenu
contains storage, development, members and owner-only deletion. Founding and building
screens remain separate. **Гайд** holds the explanatory boundary, sleep and placement
instructions, keeping overview focused on state and actions. Right-clicking a building
control opens that building: level, work assignments, output choice, materials and
its native barrel by default (paginated warehouse only in VIRTUAL). Worker and
building views show the resolved current work or blocking reason. **Очередь** lists
the active job and waiting plans; ordinary building/plan removal has a confirmation.
Selection and
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
entrance and authored anchor. Right-clicking a block starts the preview and sets the
schematic anchor to that block's X/Y/Z. The internal control is calculated independently
from the transformed marker. Confirmation installs a free BARREL there; selecting an
already placed BARREL has no special coordinate semantics. There is no second aiming ray after the click. Shift +
right-click, menu reopen, logout, world change, expiry,
reload and shutdown retire the selection. Its identity fences asynchronous
blueprint completion; a rejected location preserves the draft for the next click.
Ground targeting uses the player's actual eye location and look direction (including
pitch), with a 12-block ray and ordinary block occlusion. Ground selection neither
captures world blocks nor writes stock or city state.

After a successful ground click, prepared placement uses Core `PaperActionPanels` through
`CivsPlacementControls`. The main page offers confirm, anchor movement, both
quarter-turn rotations, optional details and cancellation. Movement has a separate
page of six one-block nudges, Done and Cancel; a ground right-click relocates the
whole preview. The green marker identifies the inside-building control coordinate;
cyan marks the separate visual entrance and the light-blue frame shows the ground
destination. Upgrades preserve the existing entrance and rotation.
Both control pages face the selected anchor, not the internal control: normal controls
from 2.6 blocks and 0.1 blocks below eye level, move controls from three. A compact two-column grid uses 1.6-block
buttons, 1.7-block column spacing, 0.75 label scale and one centered status. Movement
controls sit slightly higher to keep terrain picking clear below them. Core still owns
obstacle avoidance, reach checks and hit testing; no separate positioning engine is used.
Chat feedback is colored by outcome (green success, yellow warning, red rejection,
blue guidance); ordinary dialog prose retains its existing palette.

Placement selection immediately shows nearby saved building outlines, before a new
candidate is ready. It considers footprints no more than 32 horizontal blocks from the
player and 16 vertical blocks from the footprint entrance, and caps total neighboring-building
geometry at 128 parts. Candidate overlaps are red; an ACTIVE building does not conflict with its
own upgrade. This total geometry cap is separate from the shared per-tick display
spawn budget.

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

Ordinary in-flight city saves leave menus available. Mutating player actions wait
for the city's current write and re-read membership, online status, distance and
operation-specific state before executing; no stale snapshot is replayed. Waiting
actions resume before new NPC work on the owning server tick. Unknown writes and
actual recovery retain their hard barriers. Administrative reload still requires
all writes and placements to drain.

All loaded inventory receipts acquire native container locks before the service
publishes readiness. Recovery may process one receipt per pulse, but players
cannot alter later receipts' before/after slots while they wait. A known SQL
rejection preserves the founding preview; an unknown commit result freezes
changes until canonical reload.

A migrated city already containing sixteen buildings and no builder hut retains
one builder assignment in its active hall. This narrowly avoids an irreversible
bootstrap deadlock in old full cities. New city placement reserves a hut slot,
so the compatibility condition is unreachable through the new normal flow.
