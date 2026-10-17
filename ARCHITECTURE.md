# ArcCivs settlement contract

Players found one settlement, choose a curated imported building style, place
and preview a town hall, assign four pioneers, build production and housing,
recruit residents, and upgrade buildings/town hall through three levels.
The settlement survives reload/restart. Owner-managed membership grants city
management; unrelated players cannot build, break or loot city structures.

Core 2.7.31 is mandatory: lifecycle/scheduling, atomic persistence, localized
text, native dialogs/tables, packet displays and the host-owned asynchronous
schematic service. NPCs use Citizens ordinary bounded point-to-point navigation;
there is no Core packet-NPC API. No duplicated pools, schematic cache or display
transport. No schematic IO or future waiting on the gameplay thread.

## Domain

Six building types: TOWN_HALL, WAREHOUSE, HOUSE, FORESTER, QUARRY, FARM; levels
1..3. Internal resources WOOD, STONE, FOOD cannot be withdrawn as items/money.
Food pays for recruitment/development; ordinary work does not consume food.
Starting stock is 96 wood, 96 stone, 24 food, with four pioneers. This grant is
part of the durable city creation, never repeated on join/reload.

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

## Delivery

Build and focused domain/platform/recovery checks, consumer/package checks,
then production deployment and full native player journey. Lab/production
fixtures must preserve existing users and have an explicit cleanup record.
