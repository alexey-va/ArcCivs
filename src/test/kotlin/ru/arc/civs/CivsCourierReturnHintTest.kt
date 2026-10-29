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
    test("a real walked courier source leg retains one hint through its pending pickup then consumes it on return") {
        residentRuntime { h ->
            val (source, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.city.inventory!!.mode shouldBe CivStorageMode.PHYSICAL
            h.idleReason = "searching-work"
            val offered = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == source.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            var offersEnabled = false
            h.courierProvider = { _, worker -> if (worker.id != courierId || !offersEnabled) null else
                worker.cargo?.let { offered.copy(cargo = it, carrying = true) } ?: offered }
            h.onPickup = { cityId, workerId, _ -> h.owner.requireStorageArrival(cityId, workerId, source.id) }
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            // An initial offered plan would legitimately spawn at its source. Admit
            // the idle warehouse actor first, then require an actual source walk.
            h.world.getBlockAt(11, 63, 0).type = Material.STONE
            val warehouseFeet = Location(h.world, 11.5, 64.0, .5)
            courier.teleport(warehouseFeet)
            citizenPacketSupported(h.world, warehouseFeet.point()) shouldBe true
            citizenAtStorage(h.world, warehouseFeet.point(),
                requireNotNull(h.city.building(offered.cargo.warehouseId)?.control)) shouldBe true
            offersEnabled = true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            var now = 3_000L
            for (tick in 1..600) {
                h.navigate(); now = requireNotNull(h.navigationNow)
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (h.pickups == 1) break
            }
            withClue(h.navigationState(courierId)) { h.pickups shouldBe 1 }
            val runtime = CivsCitizens::class.java.getDeclaredField("runtime").also { it.isAccessible = true }
                .get(h.owner) as CitizenRuntime
            val actor = runtime.actors.getValue(courierId)
            (actor.npc.location.distance(warehouseFeet) >= 5.0) shouldBe true
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
            h.pickups shouldBe 1
            h.unloads shouldBe 0
        }
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

    for (obstruction in listOf("forbidden", "water", "changed-step"))
    test("a $obstruction across the hinted corridor uses the same ordinary-search fallback and budgets") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("courier-corridor-$obstruction").also(paper.server::addWorld)
            for (x in -1..3) for (z in -1..1) world.loadChunk(x, z)
            val start = CitizenPoint(.5, 64.0, .5)
            val target = CitizenPoint(40.5, 64.0, .5)
            val corridor = requireNotNull(CitizenSearchCorridor.fromWalked((0..40).mapTo(hashSetOf()) {
                CivPosition(it, 63, 0) }, start, target))
            if (obstruction != "forbidden") for (x in 19..21) for (z in -1..1) for (y in 64..65)
                world.getBlockAt(x, y, z).type = if (obstruction == "water") Material.WATER else Material.STONE
            val route = CitizenPacketRoute(world, start.location(world), target.location(world),
                { it.y == 64 && it.x in -2..42 && it.z in -5..5 &&
                    (obstruction != "forbidden" || it.x !in 19..21 || it.z !in -1..1) },
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
                    if (next.x in 18.0..23.0 && kotlin.math.abs(next.z - .5) > 1.5) leftHint = true
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
