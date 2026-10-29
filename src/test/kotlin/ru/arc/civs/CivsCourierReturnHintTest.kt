package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsCourierReturnHintTest : FunSpec({
    for (warehouseX in listOf(10, -25, -45)) for (completeUnrelated in listOf(false, true))
        test("a real walked courier source leg to warehouseX=$warehouseX survives unrelated completion=$completeUnrelated and its pending pickup") {
        residentRuntime { h ->
            val startAtBuilder = warehouseX != 10
            val (source, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.city.inventory!!.mode shouldBe CivStorageMode.PHYSICAL
            val builder = source.copy(id = UUID(0, 136), type = BuildingType.BUILDER_HUT, production = null,
                schematicId = "builder1", anchor = CivPosition(-5, 64, 0), control = CivPosition(-5, 64, 0),
                entrance = CivPosition(-5, 64, -1), bounds = CivBox(CivPosition(-7, 63, -2), CivPosition(-3, 68, 2)))
            if (startAtBuilder) {
                val warehouse = h.city.buildings.single { it.type == BuildingType.WAREHOUSE }
                h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                    if (it.id == warehouse.id) it.copy(anchor = CivPosition(warehouseX, 64, 0), control = CivPosition(warehouseX, 64, 0),
                        entrance = CivPosition(warehouseX, 64, -1), bounds = CivBox(CivPosition(warehouseX - 2, 63, -2), CivPosition(warehouseX + 2, 68, 2))) else it
                } + builder))
                // Even an idle physical warehouse plan validates its one-chunk
                // search margin before admission; load the full native leg.
                for (x in (warehouseX shr 4) - 1..2) for (z in -2..2) h.world.loadChunk(x, z)
                h.world.getBlockAt(warehouseX, 64, 0).type = Material.BARREL
                h.world.getBlockAt(warehouseX + 1, 63, 0).type = Material.STONE
                h.world.getBlockAt(-5, 64, 0).type = Material.BARREL
                h.world.getBlockAt(-4, 63, 0).type = Material.STONE
            }
            val unrelated = source.copy(id = UUID(0, 128), type = BuildingType.UNIVERSITY,
                anchor = CivPosition(-25, 64, 25), entrance = CivPosition(-25, 64, 24), control = CivPosition(-25, 64, 25),
                bounds = CivBox(CivPosition(-27, 63, 23), CivPosition(-23, 68, 27)), phase = BuildingPhase.CONSTRUCTING,
                production = null, schematicId = "university1")
            if (completeUnrelated) h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + unrelated,
                construction = CivConstruction(UUID(0, 129), unrelated.id, 1, 1, 0,
                    ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = unrelated,
                    batches = CivConstructionBatches(1, 16), materials = mapOf("STONE" to 1L), payment = CivConstructionPayment())))
            h.idleReason = "searching-work"
            val offered = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == source.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            var offersEnabled = false
            h.courierProvider = { _, worker -> if (worker.id != courierId || !offersEnabled) null else
                worker.cargo?.let { offered.copy(cargo = it, carrying = true) } ?: offered }
            h.onPickup = { cityId, workerId, _ -> h.owner.requireStorageArrival(cityId, workerId, source.id) }
            requireNotNull(citizenPlan(h.city, h.city.workers.single { it.id == courierId })).chunksReady(h.world) shouldBe true
            h.owner.pulse(2_000)
            val courier = withClue(h.owner.debug(h.city.id, courierId)) {
                h.owner.collisionActors().single { it.uniqueId == courierId }
            }
            // An initial offered plan would legitimately spawn at its source. Admit
            // the idle warehouse actor first, then require an actual source walk.
            h.world.getBlockAt(11, 63, 0).type = Material.STONE
            val departure = Location(h.world, if (startAtBuilder) -3.5 else 11.5, 64.0, .5)
            courier.teleport(departure)
            citizenPacketSupported(h.world, departure.point()) shouldBe true
            citizenAtStorage(h.world, departure.point(), if (startAtBuilder) requireNotNull(builder.control) else
                requireNotNull(h.city.building(offered.cargo.warehouseId)?.control)) shouldBe true
            if (startAtBuilder) citizenAtStorage(h.world, departure.point(),
                requireNotNull(h.city.building(offered.cargo.warehouseId)?.control)) shouldBe false
            offersEnabled = true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            val runtime = CivsCitizens::class.java.getDeclaredField("runtime").also { it.isAccessible = true }
                .get(h.owner) as CitizenRuntime
            val actor = runtime.actors.getValue(courierId)
            var completed = false
            var now = 3_000L
            for (tick in 1..600) {
                h.navigate(); now = requireNotNull(h.navigationNow)
                if (completeUnrelated && !completed && actor.npc.location.distance(departure) >= 5.0 && h.pickups == 0) {
                    val oldRoute = requireNotNull(actor.route)
                    val oldTrip = requireNotNull(oldRoute.trip)
                    (oldTrip.walked.size >= 2) shouldBe true
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                        if (it.id == unrelated.id) it.copy(phase = BuildingPhase.ACTIVE) else it
                    }, construction = null))
                    h.navigate(); now = requireNotNull(h.navigationNow)
                    actor.route shouldBe null // Real Motion geometry fence cancelled the old navigation.
                    val sourceControl = requireNotNull(source.control)
                    withClue("fresh native leg from ${actor.npc.location.point()}") {
                        CitizenChunks.between(actor.npc.location.blockX, actor.npc.location.blockZ,
                            sourceControl.x, sourceControl.z).ready(h.world) shouldBe true
                    }
                    for (admission in 1..20) {
                        h.owner.pulseFieldWork(now)
                        if (actor.route != null) break
                        h.navigate(); now = requireNotNull(h.navigationNow)
                    }
                    val fresh = withClue("${h.navigationState(courierId)} ${h.owner.debug(h.city.id, courierId)}") {
                        requireNotNull(actor.route)
                    }
                    (fresh !== oldRoute) shouldBe true
                    (fresh.trip !== oldTrip) shouldBe true
                    fresh.trip?.validFor(h.city) shouldBe true
                    citizenAtStorage(h.world, fresh.from,
                        requireNotNull(h.city.building(offered.cargo.warehouseId)?.control)) shouldBe false
                    completed = true
                }
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (h.pickups == 1) break
            }
            withClue(h.navigationState(courierId)) { h.pickups shouldBe 1 }
            completed shouldBe completeUnrelated
            (actor.npc.location.distance(departure) >= 5.0) shouldBe true
            val hint = requireNotNull(actor.courierReturnHint)
            val sourceFeet = actor.npc.location
            h.city = CivsLogistics.pickup(h.city, courierId, offered.cargo)
            h.owner.pulseFieldWork(now + 250)
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            actor.courierReturnHint shouldBe hint
            actor.route shouldBe null
            actor.npc.location shouldBe sourceFeet
            h.pendingPickup.complete(h.city)
            h.owner.pulseFieldWork(now + 500)
            h.phase(courierId) shouldBe CitizenPhase.RETURNING
            actor.courierReturnHint shouldBe null
            val route = requireNotNull(actor.route)
            route.motion.hasCourierCorridorHint shouldBe true
            hint.corridor(h.city, offered.cargo, sourceFeet.point(), route.target.point().copy(z = route.target.z + 1)) shouldBe null
            h.pickups shouldBe 1
            h.unloads shouldBe 0
        }
    }

    for (change in listOf("warehouse", "source", "protected-corridor", "shipment", "world", "centre", "radius", "teleport", "overflow"))
        test("an observed courier prefix rejects $change before reuse under fresh geometry") {
            val warehouse = CivBuilding(UUID(0, 130), BuildingType.WAREHOUSE, 1, CivPosition(0, 64, 0), 0, "test-warehouse",
                CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE,
                control = CivPosition(0, 64, 0))
            val source = warehouse.copy(id = UUID(0, 131), type = BuildingType.FORESTER, anchor = CivPosition(40, 64, 0),
                bounds = CivBox(CivPosition(38, 63, -2), CivPosition(42, 68, 2)), entrance = CivPosition(40, 64, 0),
                control = CivPosition(40, 64, 0), schematicId = "test-forester")
            val city = pathsCity().copy(radius = 512, buildings = listOf(warehouse, source), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val cargo = CivCargo(UUID(0, 132), warehouse.id, source.id, warehouse.id, source.entrance, "OAK_LOG", 64)
            val origin = CitizenPoint(.5, 64.0, .5)
            val prefix = requireNotNull(CitizenCourierInboundPrefix.begin(city, cargo, warehouse.id, origin))
            val paths = CivsPaths()
            val first = paths.begin(city)
            var actual = origin
            fun walk(trip: CivsPaths.Trip, at: CitizenPoint) {
                trip.observe(at); prefix.observe(actual, at, trip); actual = at
            }
            if (change == "overflow") for (z in 0..9) for (x in 3..36) walk(first, CitizenPoint(x + .5, 64.0, z + .5))
            for (x in 1..20) walk(first, CitizenPoint(x + .5, 64.0, .5))
            if (change == "overflow") first.walked shouldBe emptySet()
            val updated = when (change) {
                "warehouse", "source" -> city.copy(buildings = city.buildings.map {
                    if (it.id == (if (change == "warehouse") warehouse.id else source.id))
                        it.copy(control = requireNotNull(it.control).copy(z = 1)) else it
                })
                "protected-corridor" -> city.copy(buildings = city.buildings + warehouse.copy(id = UUID(0, 133),
                    bounds = CivBox(CivPosition(12, 63, -1), CivPosition(14, 68, 1))))
                "world" -> city.copy(worldId = UUID(0, 134))
                "centre" -> city.copy(center = city.center.copy(z = 1))
                "radius" -> city.copy(radius = city.radius - 1)
                else -> city
            }
            val currentCargo = if (change == "shipment") cargo.copy(id = UUID(0, 135)) else cargo
            prefix.append(updated, currentCargo, warehouse.id, first, actual)
            val second = paths.begin(updated)
            if (change == "teleport") actual = actual.copy(z = actual.z + 5)
            prefix.start(updated, currentCargo, warehouse.id, actual)
            for (x in 21..40) walk(second, CitizenPoint(x + .5, 64.0, .5))
            prefix.finish(updated, currentCargo.copy(inventoryBacked = true), warehouse.id, second, actual) shouldBe null
        }

    test("one observed courier corridor reduces long return capture without changing native search authority") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("courier-return-corridor").also(paper.server::addWorld)
            for (x in -1..4) for (z in -1..3) world.loadChunk(x, z)
            val city = pathsCity().copy(worldId = world.uid, radius = 128)
            val paths = CivsPaths()
            val trip = paths.begin(city)
            val trace = (0..60).map { CivPosition(it, 63, 0) } + (1..40).map { CivPosition(60, 63, it) }
            trace.forEach {
                world.getBlockAt(it.x, it.y, it.z).type = Material.COBBLESTONE
                trip.observe(it.copy(y = 64).point())
            }
            val start = CitizenPoint(.5, 64.0, .5)
            val source = CitizenPoint(60.5, 64.0, 40.5)
            val cargo = returnHintCargo(city)
            val hint = requireNotNull(CitizenCourierReturnHint.capture(city, cargo, trip, start, source))
            val corridor = requireNotNull(hint.corridor(city, cargo.copy(inventoryBacked = true), source, start))
            fun planned(preferred: CitizenSearchCorridor?) = CitizenPacketRoute(world, source.location(world), start.location(world),
                { it.y == 64 && it.x in -2..62 && it.z in -2..42 }, { _, _ -> true },
                { if (it.type == Material.COBBLESTONE) 1.0 else CivsPaths.ORDINARY_COST }, corridor = preferred)
                .also(::prepareHintRoute)
            val ordinary = planned(null)
            val returning = planned(corridor)
            for (route in listOf(ordinary, returning)) {
                route.failure shouldBe null
                route.continuation shouldBe false
                route.endpoint shouldBe start
            }
            withClue("ordinary=${ordinary.capturedBlocks}/${ordinary.nodeCount} hinted=${returning.capturedBlocks}/${returning.nodeCount}") {
                (returning.capturedBlocks < ordinary.capturedBlocks) shouldBe true
                (returning.nodeCount <= ordinary.nodeCount) shouldBe true
            }
            // Preparation has not spent or moved the real source items/terrain.
            trace.all { world.getBlockAt(it.x, it.y, it.z).type == Material.COBBLESTONE } shouldBe true
        }
    }

    test("the single hint rejects changed identity geometry and large endpoint drift but not unrelated revisions") {
        val city = pathsCity().copy(radius = 128)
        val trip = CivsPaths().begin(city)
        for (x in 0..40) trip.observe(CitizenPoint(x + .5, 64.0, .5))
        val start = CitizenPoint(.5, 64.0, .5)
        val arrived = CitizenPoint(40.5, 64.0, .5)
        val cargo = returnHintCargo(city)
        val hint = requireNotNull(CitizenCourierReturnHint.capture(city, cargo, trip, start, arrived))
        trip.walked.clear() // A cancelled route cannot mutate the detached hint.
        (hint.corridor(city.copy(revision = city.revision + 1), cargo, arrived, start) != null) shouldBe true
        hint.corridor(city, cargo.copy(id = UUID.randomUUID()), arrived, start) shouldBe null
        hint.corridor(city.copy(worldId = UUID.randomUUID()), cargo, arrived, start) shouldBe null
        hint.corridor(city.copy(id = UUID.randomUUID()), cargo, arrived, start) shouldBe null
        val changed = city.copy(buildings = city.buildings.map { it.copy(bounds = it.bounds.copy(max = it.bounds.max.copy(y = it.bounds.max.y + 1))) })
        hint.corridor(changed, cargo, arrived, start) shouldBe null
        val originalRoute = listOf(CivPosition(1, 64, 0), CivPosition(2, 65, 0), CivPosition(3, 64, 0))
        val grade = CivRoadRouteOverride(originalRoute, originalRoute.map { it.copy(y = 64) })
        hint.corridor(city.copy(roadRouteOverrides = mapOf(city.buildings.single().id.toString() to grade)), cargo, arrived, start) shouldBe null
        hint.corridor(city, cargo, arrived.copy(x = arrived.x + 3), start) shouldBe null
        hint.corridor(city, cargo, arrived, start.copy(x = start.x + 5)) shouldBe null
    }

    test("disconnected traces and access longer than twelve blocks never become a corridor") {
        val from = CitizenPoint(.5, 64.0, .5)
        val to = CitizenPoint(40.5, 64.0, .5)
        CitizenSearchCorridor.fromWalked((0..3).mapTo(hashSetOf()) { CivPosition(it, 63, 0) } +
            (37..40).map { CivPosition(it, 63, 0) }, from, to) shouldBe null
        CitizenSearchCorridor.fromWalked((0..20).mapTo(hashSetOf()) { CivPosition(it, 63, 0) }, from, to) shouldBe null
        CitizenSearchCorridor.fromWalked((0..256).mapTo(hashSetOf()) { CivPosition(it, 63, 0) }, from, to) shouldBe null
        val connected = requireNotNull(CitizenSearchCorridor.fromWalked((5..35).mapTo(hashSetOf()) {
            CivPosition(it, 63, 0) }, from, to))
        connected.contains(from) shouldBe true
        connected.contains(to) shouldBe true
        connected.contains(CitizenPoint(20.5, 64.0, 5.5)) shouldBe false
    }

    test("only a warehouse destination may bridge sixty-four blocks while source access stays twelve") {
        val walked = (20..40).mapTo(hashSetOf()) { CivPosition(it, 63, 0) }
        val from = CitizenPoint(40.5, 64.0, .5)
        val warehouse = CitizenPoint(-43.5, 64.0, .5)
        CitizenSearchCorridor.fromWalked(walked, from, warehouse) shouldBe null
        val bridged = requireNotNull(CitizenSearchCorridor.fromWalked(walked, from, warehouse, warehouseAccess = true))
        bridged.contains(warehouse) shouldBe true
        bridged.contains(CitizenPoint(.5, 64.0, 2.5)) shouldBe true
        bridged.contains(CitizenPoint(.5, 64.0, 3.5)) shouldBe false
        CitizenSearchCorridor.fromWalked(walked, from, warehouse.copy(x = warehouse.x - .001), warehouseAccess = true) shouldBe null
        (CitizenSearchCorridor.fromWalked(walked, from.copy(x = 52.5), warehouse, warehouseAccess = true) != null) shouldBe true
        CitizenSearchCorridor.fromWalked(walked, from.copy(x = 52.501), warehouse, warehouseAccess = true) shouldBe null
    }

    for (warehouseAccess in listOf(false, true)) for (obstruction in listOf("forbidden", "water", "changed-step"))
    test("a $obstruction across the hinted corridor with warehouse access=$warehouseAccess uses the same ordinary-search fallback and budgets") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("courier-corridor-$obstruction").also(paper.server::addWorld)
            for (x in -1..3) for (z in -1..1) world.loadChunk(x, z)
            val start = CitizenPoint(.5, 64.0, .5)
            val target = CitizenPoint(40.5, 64.0, .5)
            val corridor = requireNotNull(CitizenSearchCorridor.fromWalked((if (warehouseAccess) 0..20 else 0..40).mapTo(hashSetOf()) {
                CivPosition(it, 63, 0) }, start, target, warehouseAccess))
            val obstructionWidth = if (warehouseAccess) 2 else 1
            if (obstruction != "forbidden") for (x in 19..21) for (z in -obstructionWidth..obstructionWidth) for (y in 64..65)
                world.getBlockAt(x, y, z).type = if (obstruction == "water") Material.WATER else Material.STONE
            val route = CitizenPacketRoute(world, start.location(world), target.location(world),
                { it.y == 64 && it.x in -2..42 && it.z in -5..5 &&
                    (obstruction != "forbidden" || it.x !in 19..21 || it.z !in -obstructionWidth..obstructionWidth) },
                { _, _ -> true }, { 1.0 }, corridor = corridor)
            prepareHintRoute(route)
            route.failure shouldBe null
            route.continuation shouldBe false
            route.endpoint shouldBe target
            (route.nodeCount <= CivsNavigationSettings().maximumNodes + 1) shouldBe true
            (route.capturedBlocks <= CivsNavigationSettings().maximumCapturedBlocks) shouldBe true
            var at = start.location(world).also { it.yaw = -90f }
            var leftHint = false
            repeat(600) {
                if (!route.finished) route.move(at, 1.0) { true }?.let { next ->
                    if (next.x in 18.0..23.0 && kotlin.math.abs(next.z - .5) > obstructionWidth + .5) leftHint = true
                    at = next
                }
            }
            withClue("$obstruction ${route.failure} at=$at") { route.finished shouldBe true }
            leftHint shouldBe true
            route.failure shouldBe null
        }
    }

    test("a closed passage remains a native movement fence even when a hint supplies its corridor") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("courier-corridor-door").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val start = CitizenPoint(.5, 64.0, .5)
            val target = CitizenPoint(20.5, 64.0, .5)
            world.getBlockAt(10, 64, 0).type = Material.OAK_DOOR
            val corridor = requireNotNull(CitizenSearchCorridor.fromWalked((0..20).mapTo(hashSetOf()) {
                CivPosition(it, 63, 0) }, start, target))
            val route = CitizenPacketRoute(world, start.location(world), target.location(world),
                { it.y == 64 && it.z == 0 && it.x in 0..20 }, { _, _ -> true }, { 1.0 }, corridor = corridor)
            prepareHintRoute(route)
            route.failure shouldBe null
            var at = start.location(world).also { it.yaw = -90f }
            repeat(100) { route.move(at, 1.0) { it.blockX < 10 }?.let { at = it } }
            route.failure shouldBe "passage-blocked"
            (at.x < 10.0) shouldBe true
            world.getBlockAt(10, 64, 0).blockData.let { (it as org.bukkit.block.data.Openable).isOpen } shouldBe false
        }
    }
})

private fun CitizenPoint.location(world: World) = Location(world, x, y, z)

private fun returnHintCargo(city: Settlement) = CivCargo(UUID.randomUUID(), city.buildings.single().id,
    UUID.randomUUID(), city.buildings.single().id, CivPosition(40, 64, 0), "OAK_LOG", 16)

private fun prepareHintRoute(route: CitizenPacketRoute) {
    var operations = 0
    while (route.searching && operations++ < 500_000) route.expand()
    withClue("nodes=${route.nodeCount} blocks=${route.capturedBlocks} phase=${route.preparationPhase} failure=${route.failure}") {
        route.searching shouldBe false
    }
}
