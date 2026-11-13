package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import java.util.UUID

class CivsStorageApproachOccupancyTest : FunSpec({
    for (surface in listOf(Material.STONE, Material.ACACIA_SLAB, Material.DIRT_PATH))
    test("a carrying courier leaves a mine before its exact storage approach surface=$surface") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val fixture = mineExitStorageFixture(h, surface)
            val actor = fixture.actor
            val mine = h.city.buildings.single { it.type == BuildingType.QUARRY }
            val target = fixture.control
            val expectedY = when (surface) { Material.ACACIA_SLAB -> 63.5; Material.DIRT_PATH -> 63.9375; else -> 64.0 }
            val expected = Location(h.world, target.x + .5, expectedY, target.z - .5)
            val before = h.city
            val token = actor.plan.token
            val cargo = requireNotNull(h.city.workers.single().cargo)
            actor.cycle.phase shouldBe CitizenPhase.RETURNING
            citizenPacketSupported(h.world, expected.point(), false) shouldBe true
            citizenPacketBodyClear(h.world, expected.point(), false) shouldBe true
            citizenAtStorage(h.world, expected.point(), target) shouldBe true
            citizenMineWaypoint(h.city, h.world, actor.npc.location, target) shouldBe mine.entrance

            fixture.routes.startRoute(actor, h.world, target, 6_000) shouldBe true
            requireNotNull(actor.route).let { route ->
                route.authored shouldBe target
                route.target shouldBe mine.entrance.location(h.world)
                route.intermediate shouldBe true
            }
            for (tick in 0 until 480) {
                h.navigate()
                if (citizenArrived(actor.npc.location, mine.entrance.location(h.world))) break
            }
            citizenArrived(actor.npc.location, mine.entrance.location(h.world)) shouldBe true
            fixture.routes.cancelRoute(actor)
            fixture.routes.startRoute(actor, h.world, target, requireNotNull(h.navigationNow)) shouldBe true
            requireNotNull(actor.route).let { route ->
                route.authored shouldBe target
                route.target shouldBe expected
                route.intermediate shouldBe false
            }
            for (tick in 0 until 640) {
                h.navigate()
                if (fixture.routes.arrivedAt(actor, h.city, h.world, target)) break
            }
            fixture.routes.arrivedAt(actor, h.city, h.world, target) shouldBe true
            actor.npc.location.y shouldBe expectedY
            actor.plan.token shouldBe token
            h.city.workers.single().cargo shouldBe cargo
            h.city shouldBe before
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    for (obstruction in listOf("headroom", "support"))
    test("a mine exit never admits an unsafe final storage approach obstruction=$obstruction") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val fixture = mineExitStorageFixture(h, Material.ACACIA_SLAB)
            val actor = fixture.actor
            val mine = h.city.buildings.single { it.type == BuildingType.QUARRY }
            val standing = fixture.control.copy(z = fixture.control.z - 1)
            if (obstruction == "headroom") h.world.getBlockAt(standing.x, 65, standing.z).type = Material.STONE
            else for (y in 60..63) h.world.getBlockAt(standing.x, y, standing.z).type = Material.AIR
            val before = h.city
            val token = actor.plan.token
            citizenMineWaypoint(h.city, h.world, actor.npc.location, fixture.control) shouldBe mine.entrance
            fixture.routes.startRoute(actor, h.world, fixture.control, 6_000) shouldBe false
            actor.route shouldBe null
            actor.plan.token shouldBe token
            h.city shouldBe before
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    for (rise in listOf(1.0) + (1..15).map { it / 16.0 })
    test("storage routes preserve the actual native support top rise=$rise") {
        residentRuntime(worldFactory = ::FractionalStorageWorld, server = CivsWorksiteServerFixture()) { h ->
            val fixture = fractionalStorageFixture(h, rise)
            val actor = fixture.actor
            val target = requireNotNull(actor.plan.workplace)
            val token = actor.plan.token
            val expected = Location(h.world, fixture.control.x + .5, fixture.control.y - 1 + rise, fixture.control.z - .5)
            citizenPacketSupported(h.world, expected.point(), false) shouldBe true
            citizenPacketBodyClear(h.world, expected.point(), false) shouldBe true
            h.navigationNow = 6_000
            fixture.routes.startRoute(actor, h.world, target, 6_000) shouldBe true
            val route = requireNotNull(actor.route)
            route.authored shouldBe target
            route.target shouldBe expected
            actor.plan.token shouldBe token
            for (tick in 0 until 240) {
                h.navigate()
                if (fixture.routes.arrivedAt(actor, h.city, h.world, target)) break
            }
            withClue("rise=$rise actual=${actor.npc.location} target=$expected") {
                fixture.routes.arrivedAt(actor, h.city, h.world, target) shouldBe true
            }
            actor.npc.location.y shouldBe expected.y
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    for (obstruction in listOf("headroom", "unsupported", "unloaded", "player"))
    test("fractional storage keeps physical admission guards obstruction=$obstruction") {
        residentRuntime(worldFactory = ::FractionalStorageWorld, server = CivsWorksiteServerFixture()) { h ->
            val fixture = fractionalStorageFixture(h, .5)
            val world = h.world as FractionalStorageWorld
            val point = fixture.control.copy(y = fixture.control.y - 1, z = fixture.control.z - 1)
            when (obstruction) {
                "headroom" -> world.getBlockAt(point.x, point.y + 2, point.z).type = Material.STONE
                "unsupported" -> {
                    world.rise = 0.0
                    world.getBlockAt(point.x, point.y - 1, point.z).type = Material.AIR
                }
                "unloaded" -> world.unavailable = (point.x shr 4) to (point.z shr 4)
                "player" -> h.playerAt(Location(world, point.x + .5, point.y + .5, point.z + .5))
            }
            h.nativeCollisions().use { native ->
                h.owner.residentStepAllowed = native::canResidentMove
                h.navigationNow = 6_000
                val accepted = fixture.routes.startRoute(fixture.actor, world, requireNotNull(fixture.actor.plan.workplace), 6_000)
                if (obstruction == "unloaded") {
                    // Accepted loading intent must not create a route or invent an arrival.
                    accepted shouldBe true
                    citizenStorageFeet(world, point) shouldBe null
                } else accepted shouldBe false
                fixture.actor.route shouldBe null
                h.unloads shouldBe 0
                h.deliveries shouldBe 0
            }
        }
    }

    test("integer storage retains a supported ladder and fractional validation preserves supplied offsets") {
        residentRuntime(worldFactory = ::FractionalStorageWorld, server = CivsWorksiteServerFixture()) { h ->
            val world = h.world as FractionalStorageWorld
            val point = CivPosition(20, 64, -1)
            world.loadChunk(1, -1)
            world.getBlockAt(point.x, point.y - 1, point.z).type = Material.AIR
            world.getBlockAt(point.x, point.y, point.z).type = Material.LADDER
            citizenStandable(world, point) shouldBe true
            citizenStorageFeet(world, point) shouldBe point.location(world)
            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
            world.supportCells = setOf(point.copy(y = 63), point.copy(x = 21, y = 63))
            world.rise = .5
            val offset = Location(world, 21.125, 63.5, -.5)
            citizenStorageStanding(world, point.copy(y = 63), offset) shouldBe true
            offset.x shouldBe 21.125
        }
    }

    for (surface in listOf(Material.STONE, Material.DIRT_PATH))
    test("a current unfinished storage keeps its real entrance fallback without a barrel or transfer surface=$surface") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val fixture = storageApproachFixture(h, true, 0)
            val control = CivPosition(40, 64, 0)
            val storage = CivBuilding(UUID(295, 1), BuildingType.WAREHOUSE, 1, control, 0, "warehouse1",
                CivBox(CivPosition(38, 64, -2), CivPosition(42, 68, 2)), control.copy(z = -3),
                BuildingPhase.CONSTRUCTING, control = control)
            val order = CivConstruction(UUID(295, 2), storage.id, 1, 1, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = storage, batches = CivConstructionBatches(1, 16), workCredit = 1,
                materials = emptyMap(), payment = CivConstructionPayment(reservedBatch = 0))
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + storage, construction = order))
            for (x in -1..4) for (z in -2..2) h.world.loadChunk(x, z)
            val floor = storage.entrance.copy(y = storage.entrance.y - 1)
            h.world.getBlockAt(floor.x, floor.y, floor.z).blockData = Bukkit.createBlockData(surface)
            val navigation = h.owner.javaClass.getDeclaredField("navigation").run {
                isAccessible = true; get(h.owner) as CivsResidentNavigation
            }
            navigation.changed(h.world.uid, floor)
            val snapshot = h.world.getChunkAt(floor.x shr 4, floor.z shr 4, false)
                .getChunkSnapshot(false, false, false, false)
            snapshot.getBlockData(floor.x and 15, floor.y, floor.z and 15).asString shouldBe
                h.world.getBlockAt(floor.x, floor.y, floor.z).blockData.asString
            citizenStorageReady(h.world, control) shouldBe false
            fixture.routes.cancelRoute(fixture.actor)
            fixture.actor.targets.clear()
            fixture.actor.plan = fixture.actor.plan.copy(storage = control)
            fixture.actor.cycle = fixture.actor.plan.cycle().also { it.returnWithObservedWork() }
            fixture.actor.npc.teleport(control.copy(z = -8).location(h.world))
            h.navigationNow = 6_000
            fixture.routes.startRoute(fixture.actor, h.world, control, 6_000) shouldBe true
            requireNotNull(fixture.actor.route).target shouldBe storage.entrance.location(h.world)
            requireNotNull(fixture.actor.route).authored shouldBe control
            var endpoint: CitizenPoint? = null
            for (tick in 0 until 80) {
                h.navigate()
                fixture.actor.route?.let { if (!it.motion.searching) endpoint = it.motion.endpoint }
                if (endpoint != null) break
            }
            requireNotNull(endpoint).y shouldBe if (surface == Material.DIRT_PATH) 63.9375 else 64.0
            for (tick in 0 until 160) {
                h.navigate()
                if (fixture.routes.arrivedAt(fixture.actor, h.city, h.world, control)) break
            }
            fixture.routes.arrivedAt(fixture.actor, h.city, h.world, control) shouldBe true
            (fixture.actor.npc.location.distanceSquared(control.copy(z = -8).location(h.world)) > 0.0) shouldBe true
            citizenPacketSupported(h.world, fixture.actor.npc.location.point()) shouldBe true
            citizenPacketBodyClear(h.world, fixture.actor.npc.location.point(), false) shouldBe true
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    for (cached in listOf(true, false)) test("the native barrel with its north side occupied walks to a clear diagonal cached=$cached") {
        residentRuntime(workerId = UUID(146, 150), worldFactory = ::CapturedBarrelApproachWorld) { h ->
            val fixture = storageApproachFixture(h, true, 1)
            val actor = fixture.actor
            val neighbour = fixture.neighbours.single()
            val control = fixture.control
            // Local native geometry is translated from (-1844,71,-2689) to the fixture barrel.
            // Capture 2026-10-31T03:18:01.687Z, worker 679ad6aa, SHA-256
            // 9fab27d47c26eeb85331c23a0af0fb195f0a03e63a8221112adf994a98806037.
            // The captured worker's pose is used as the stationary blocker; resident roles are symmetric.
            installCapturedBarrelApproaches(h, control)
            neighbour.npc.teleport(Location(h.world, control.x + .8795858695087, control.y.toDouble(),
                control.z - .8795858695084))
            actor.npc.teleport(Location(h.world, control.x + .5, control.y.toDouble(), control.z - 6.5))
            if (!cached) actor.targets.clear()
            val neighbourPosition = neighbour.npc.location
            val occupied = listOf(citizenStandingBounds(neighbourPosition, neighbour.npc.scale))
            fun safe(point: CivPosition): Boolean = citizenStandable(h.world, point) &&
                citizenPacketBodyClear(h.world, point.point(), false) && citizenControlClear(h.city, point) &&
                point.location(h.world).let { citizenResidentStepClear(actor.npc, it, it, occupied) }
            val cardinal = listOf(0, 1, -1).flatMap { dy ->
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dz) ->
                    control.copy(x = control.x + dx, y = control.y + dy, z = control.z + dz)
                }
            }
            cardinal.size shouldBe 12
            cardinal.filter(::safe) shouldBe emptyList()
            val northDiagonals = listOf(-1, 1).map { control.copy(x = control.x + it, z = control.z - 1) }
            northDiagonals.forEach { point ->
                safe(point) shouldBe true
                citizenAtStorage(h.world, point.point(), control) shouldBe true
                citizenCanReachBlock(h.world, point.location(h.world).add(0.0, actor.npc.eyeHeight, 0.0), control) shouldBe true
            }
            val before = h.city
            val token = actor.plan.token
            val cycle = actor.cycle
            val barrel = h.world.getBlockAt(control.x, control.y, control.z).state as Barrel
            barrel.inventory.setItem(0, ItemStack(Material.IRON_PICKAXE))
            var offers = 0
            h.workerDelivery = { worker, proof ->
                worker shouldBe h.workerId
                proof shouldBe token
                h.owner.requireStorageArrival(h.city.id, worker, fixture.building.id)
                citizenResidentStepClear(actor.npc, actor.npc.location, actor.npc.location,
                    listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale))) shouldBe true
                citizenCanReachBlock(actor.npc, control) shouldBe true
                actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                (actor.cycle.observedWorkMillis >= 1_000) shouldBe true
                h.city shouldBe before
                offers++
                h.pendingDelivery
            }
            fixture.routes.startRoute(actor, h.world, requireNotNull(actor.plan.workplace), 2_000) shouldBe true
            (northDiagonals.any { it.point() == requireNotNull(actor.route).target.point() }) shouldBe true
            var walked = 0.0
            var contactFrames = 0
            for (tick in 1..600) {
                val previous = actor.npc.location
                h.navigate()
                val actual = actor.npc.location
                withClue("tick=$tick ${h.navigationState()}") {
                    (previous.distance(actual) <= .172001) shouldBe true
                    citizenPacketBodyClear(h.world, actual.point(), false) shouldBe true
                    citizenPacketSupported(h.world, actual.point()) shouldBe true
                    if (!citizenResidentStepClear(actor.npc, previous, actual, occupied)) {
                        h.owner.passingBodies(actor.npc) shouldBe true
                        contactFrames++
                        (contactFrames <= 60) shouldBe true
                    }
                    neighbour.npc.location shouldBe neighbourPosition
                }
                walked += previous.distance(actual)
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (offers == 1) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                offers shouldBe 1
                (walked > 4.0) shouldBe true
            }
            (actor.cycle === cycle) shouldBe true
            actor.plan.token shouldBe token
            h.city shouldBe before
            neighbour.yieldProgress shouldBe 0.0
            barrel.inventory.getItem(0) shouldBe ItemStack(Material.IRON_PICKAXE)
            CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "IRON_PICKAXE") shouldBe 0
            h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 10_234L
            h.deliveries shouldBe 1
        }
    }

    for (cached in listOf(true, false)) test("an occupied ${if (cached) "cached" else "first"} barrel side keeps the tool cycle and walks to a free side") {
        residentRuntime(workerId = UUID(144, 679)) { h ->
            val fixture = storageApproachFixture(h, cached, 1)
            val actor = fixture.actor
            val neighbour = fixture.neighbours.single()
            val before = h.city
            val token = actor.plan.token
            val neighbourPosition = neighbour.npc.location
            val work = requireNotNull(actor.plan.worldWork)
            val barrel = h.world.getBlockAt(fixture.control.x, fixture.control.y, fixture.control.z).state as Barrel
            var offers = 0
            h.workerDelivery = { worker, proof ->
                proof shouldBe token
                h.owner.requireStorageArrival(h.city.id, worker, fixture.building.id)
                citizenResidentStepClear(actor.npc, actor.npc.location, actor.npc.location,
                    listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale))) shouldBe true
                actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                (actor.cycle.observedWorkMillis >= 1_000) shouldBe true
                h.city shouldBe before
                offers++
                h.pendingDelivery
            }
            fixture.routes.startRoute(actor, h.world, work.standing, 2_000) shouldBe true
            val selected = requireNotNull(actor.route).target
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                (selected.point() != work.standing.point()) shouldBe true
                citizenResidentStepClear(actor.npc, selected, selected,
                    listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale))) shouldBe true
            }
            actor.plan.token shouldBe token
            actor.plan.worldWork shouldBe work
            h.city shouldBe before
            offers shouldBe 0
            var walked = 0.0
            var contactFrames = 0
            for (tick in 1..400) {
                val previous = actor.npc.location
                h.navigate()
                val actual = actor.npc.location
                withClue("tick=$tick ${h.navigationState()}") {
                    (previous.distance(actual) <= .172001) shouldBe true
                    citizenPacketBodyClear(h.world, actual.point(), false) shouldBe true
                    citizenPacketSupported(h.world, actual.point()) shouldBe true
                    if (!citizenResidentStepClear(actor.npc, previous, actual,
                            listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale)))) {
                        h.owner.passingBodies(actor.npc) shouldBe true
                        contactFrames++
                        (contactFrames <= 60) shouldBe true
                    }
                }
                walked += previous.distance(actual)
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (offers == 1) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                offers shouldBe 1
                (walked > .1) shouldBe true
            }
            actor.plan.token shouldBe token
            neighbour.npc.location shouldBe neighbourPosition
            neighbour.yieldProgress shouldBe 0.0
            h.city shouldBe before
            barrel.inventory.getItem(0) shouldBe ItemStack(Material.IRON_PICKAXE)
            h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 10_234L
            CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "IRON_PICKAXE") shouldBe 0
            h.deliveries shouldBe 1
        }
    }

    test("all supported barrel sides occupied pause without a receipt and retry the same cycle when a side clears") {
        residentRuntime(workerId = UUID(144, 680)) { h ->
            val fixture = storageApproachFixture(h, true, 4)
            val actor = fixture.actor
            val before = h.city
            val token = actor.plan.token
            val cycle = actor.cycle
            for (dy in listOf(0, 1, -1)) for (dx in listOf(-1, 1)) for (dz in listOf(-1, 1)) {
                val diagonal = fixture.control.copy(x = fixture.control.x + dx, y = fixture.control.y + dy,
                    z = fixture.control.z + dz)
                citizenStandable(h.world, diagonal) shouldBe false
            }
            fixture.routes.startRoute(actor, h.world, requireNotNull(actor.plan.workplace), 2_000) shouldBe false
            actor.route shouldBe null
            actor.cycle.phase shouldBe CitizenPhase.PAUSED
            actor.cycle.reason shouldBe "storage-unavailable"
            actor.targets shouldBe emptyMap()
            h.owner.pulse(4_000)
            actor.cycle.phase shouldBe CitizenPhase.PAUSED
            for ((at, attempts) in listOf(7_000L to 2, 22_000L to 3)) {
                h.navigationNow = at
                h.owner.pulse(at)
                (actor.cycle === cycle) shouldBe true
                actor.cycle.phase shouldBe CitizenPhase.PAUSED
                actor.cycle.reason shouldBe "storage-unavailable"
                actor.cycle.failedAttempts shouldBe attempts
                actor.route shouldBe null
            }
            // Even after the ordinary retry allowance is exhausted, availability has a bounded next retry.
            cycle.retry(36_999)
            cycle.phase shouldBe CitizenPhase.PAUSED
            cycle.retry(37_000)
            cycle.phase shouldBe CitizenPhase.TO_WORK
            cycle.failedAttempts shouldBe 3
            h.city shouldBe before
            h.deliveries shouldBe 0
            fixture.neighbours.forEachIndexed { index, neighbour ->
                fixture.routes.cancelRoute(neighbour)
                neighbour.npc.teleport(Location(h.world, 8.5 + index * 2, 64.0, 8.5))
            }
            h.navigationNow = 40_000
            h.owner.pulse(40_000)
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                actor.cycle.phase shouldBe CitizenPhase.TO_WORK
                (actor.route != null) shouldBe true
            }
            (actor.cycle === cycle) shouldBe true
            actor.plan.token shouldBe token
            actor.cycle.observedWorkMillis shouldBe 0L
            h.city shouldBe before
            h.deliveries shouldBe 0
        }
    }
})

private fun fractionalStorageFixture(h: ResidentRuntime, rise: Double): StorageApproachFixture {
    val fixture = storageApproachFixture(h, true, 0)
    val control = fixture.control
    val world = h.world as FractionalStorageWorld
    world.time = 1_000
    // Lower the last approach cells too: a valid nearby arrival must stand on the tested surface.
    world.supportCells = (1..3).mapTo(hashSetOf()) { control.copy(y = control.y - 1, z = control.z - it) }
    world.rise = rise
    for (x in control.x - 1..control.x + 1) for (z in control.z - 1..control.z + 1) {
        if (x == control.x && z in control.z - 1..control.z) continue
        for (y in control.y - 2..control.y + 2) world.getBlockAt(x, y, z).type = Material.STONE
    }
    fixture.routes.cancelRoute(fixture.actor)
    fixture.actor.targets.clear()
    fixture.actor.npc.teleport(control.copy(z = control.z - 6).location(world))
    return fixture
}

/** Contextual native shape values, recorded in both the chunk palette and live cells. */
private class FractionalStorageWorld : ResidentRuntimeWorld() {
    var rise = 1.0
        set(value) {
            field = value
            recordSupportData()
        }
    var supportCells = emptySet<CivPosition>()
        set(value) {
            field = value
            recordSupportData()
        }
    var unavailable: Pair<Int, Int>? = null

    private fun recordSupportData() {
        for (point in supportCells) super.getBlockAt(Coordinate(point.x, point.y, point.z)).blockData =
            if (rise == 0.0) Bukkit.createBlockData(Material.AIR)
            else Bukkit.createBlockData("minecraft:acacia_slab[type=${if (rise == 1.0) "double" else "bottom"},waterlogged=false]")
    }

    override fun isChunkLoaded(x: Int, z: Int): Boolean = unavailable != (x to z) && super.isChunkLoaded(x, z)

    override fun getBlockAt(point: Coordinate): BlockMock {
        if (CivPosition(point.x, point.y, point.z) !in supportCells) return super.getBlockAt(point)
        val box = BoundingBox(0.0, 0.0, 0.0, 1.0, rise, 1.0)
        val data = super.getBlockAt(point).blockData.clone()
        // Slabs use the existing fixture's contextual capture seam; never cache a variable shape as static STONE.
        return object : BlockMock(data.material,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            init { blockData = data }
            override fun isPassable(): Boolean = rise == 0.0
            override fun getBoundingBox(): BoundingBox = box.clone().shift(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            override fun getCollisionShape(): VoxelShape = object : VoxelShape {
                override fun getBoundingBoxes(): Collection<BoundingBox> = if (rise == 0.0) emptyList() else listOf(box.clone())
                override fun overlaps(other: BoundingBox): Boolean = rise != 0.0 && box.overlaps(other)
            }
        }
    }
}

private data class StorageApproachFixture(val building: CivBuilding, val control: CivPosition,
    val actor: CitizenActor, val neighbours: List<CitizenActor>, val routes: CivsCitizenRoutes)

private fun mineExitStorageFixture(h: ResidentRuntime, surface: Material): StorageApproachFixture {
    val mine = spatialBuilding("nordic", "miner", 1, CivPosition(20, 63, 0)).copy(production = "COBBLESTONE")
    val control = CivPosition(mine.bounds.max.x + 12, 64, mine.entrance.z)
    val warehouse = CivBuilding(UUID(297, 1), BuildingType.WAREHOUSE, 1, control, 0, "warehouse1",
        CivBox(control.copy(x = control.x - 2, y = 63, z = control.z - 2),
            control.copy(x = control.x + 2, y = 68, z = control.z + 2)), control.copy(z = control.z - 3),
        BuildingPhase.ACTIVE, control = control)
    val cargo = CivCargo(UUID(297, 2), warehouse.id, mine.id, warehouse.id, mine.entrance, "COBBLESTONE", 2,
        inventoryBacked = true)
    h.courier = CivCourierPlan(cargo, warehouse.id, warehouse.entrance, true)
    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + listOf(mine, warehouse),
        inventory = CivInventory(CivStorageMode.PHYSICAL), workers = h.city.workers.map { worker ->
            worker.copy(assignment = warehouse.id, nextDeliveryAt = 1, cargo = cargo, items = List(CivsEquipment.SLOTS) { slot ->
                if (slot == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 2) else null
            })
        }))
    val from = CivPosition((mine.bounds.min.x + mine.bounds.max.x) / 2, 64,
        (mine.bounds.min.z + mine.bounds.max.z) / 2)
    mine.bounds.contains(from.x, from.y, from.z) shouldBe true
    val chunks = CitizenChunks.between(from.x, from.z, control.x, control.z)
    for (x in chunks.minX..chunks.maxX) for (z in chunks.minZ..chunks.maxZ) h.world.loadChunk(x, z)
    h.world.time = 1_000
    h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
    val support = if (surface == Material.ACACIA_SLAB)
        Bukkit.createBlockData("minecraft:acacia_slab[type=bottom,waterlogged=false]") else Bukkit.createBlockData(surface)
    for (distance in 1..3) h.world.getBlockAt(control.x, 63, control.z - distance).blockData = support.clone()
    for (x in control.x - 1..control.x + 1) for (z in control.z - 1..control.z + 1) {
        if (x == control.x && z in control.z - 1..control.z) continue
        for (y in 62..66) h.world.getBlockAt(x, y, z).type = Material.STONE
    }
    h.navigationNow = 2_000
    h.owner.pulse(2_000)
    val actor = storageActors(h).getValue(h.workerId)
    val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
    routes.cancelRoute(actor)
    actor.targets.clear()
    actor.plan = requireNotNull(citizenPlan(h.city, h.city.workers.single(), h.courier))
    actor.cycle = actor.plan.cycle().also { it.start() }
    actor.npc.teleport(from.location(h.world))
    h.navigationNow = 6_000
    return StorageApproachFixture(warehouse, control, actor, emptyList(), routes)
}

private fun storageApproachFixture(h: ResidentRuntime, cached: Boolean, neighbours: Int): StorageApproachFixture {
    for (x in -1..2) for (z in -2..1) h.world.loadChunk(x, z)
    val quarry = h.assignQuarry().copy(level = 2)
    val control = requireNotNull(quarry.control)
    // A world-work search holds its resident in place; unassigned residents legitimately wander and turn.
    // Give every blocker its own legal farm outside the captured barrel and route geometry.
    val neighbourFarms = List(neighbours) { index ->
        val at = control.copy(x = control.x + 20, z = control.z + index * 8)
        CivBuilding(UUID(145, 150 + index.toLong()), BuildingType.FARM, 1, at, 0, "farm1",
            CivBox(at.copy(x = at.x - 2, y = at.y - 1, z = at.z - 2), at.copy(x = at.x + 2, y = at.y + 4, z = at.z + 2)),
            at, BuildingPhase.ACTIVE, control = at, production = "WHEAT")
    }
    neighbourFarms.forEach { farm ->
        val chunks = CitizenChunks.between(farm.anchor.x, farm.anchor.z, farm.anchor.x, farm.anchor.z)
        for (x in chunks.minX..chunks.maxX) for (z in chunks.minZ..chunks.maxZ) h.world.loadChunk(x, z)
        chunks.ready(h.world) shouldBe true
        h.world.getBlockAt(farm.anchor.x, farm.anchor.y, farm.anchor.z).type = Material.BARREL
    }
    // Translate the observed 679/150 pair to the fixture barrel. Rotate the uncached case to its first (+X) side.
    fun point(x: Double, z: Double) = if (cached) Location(h.world, control.x + x, 64.0, control.z + z)
        else Location(h.world, control.x + 1 - z, 64.0, control.z + x)
    val preferred = if (cached) control.copy(z = control.z - 1) else control.copy(x = control.x + 1)
    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
        if (it.id == quarry.id) quarry else if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it
    } + neighbourFarms, workers = h.city.workers.map { it.copy(completedCycles = 10_234,
        items = List(CivsEquipment.SLOTS) { null }) } + (0 until neighbours).map { index ->
        CivWorker(UUID(144, 150 + index.toLong()), "Storage neighbour", neighbourFarms[index].id, nextDeliveryAt = 1)
    }))
    h.city.workers.count { it.assignment == quarry.id } shouldBe 1
    val worker = h.city.workers.first { it.id == h.workerId }
    val equipment = CivsEquipment.slots(worker).toMutableList().also { it[4] = CivResidentItem("IRON_PICKAXE", 1) }
    val work = CivWorldWork(worker.id, worker.completedCycles, quarry.id, preferred, emptyList(),
        inputs = mapOf("IRON_PICKAXE" to 1L), durationMillis = 1_000, status = "waiting-tool", equipmentAfter = equipment)
    h.workProvider = { _, current -> work.takeIf { current.id == worker.id } }
    h.workIdleProvider = { _, _ -> "searching-work" }
    h.navigationNow = 2_000
    h.owner.collisionActors().single().teleport(point(.7692, -1.5615))
    (h.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory.setItem(0, ItemStack(Material.IRON_PICKAXE))
    for (attempt in 0 until 4) {
        h.owner.pulse(2_000)
        if (h.owner.activeCount() == h.city.workers.size) break
    }
    val actors = storageActors(h)
    withClue(h.owner.debug(h.city.id, null)) {
        actors.keys shouldBe h.city.workers.mapTo(hashSetOf()) { it.id }
    }
    val actor = actors.getValue(h.workerId)
    val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
    actors.values.forEach { routes.cancelRoute(it); it.targets.clear(); it.yieldProgress = 0.0 }
    val others = actors.values.filter { it !== actor }
    others.size shouldBe neighbours
    others.forEach { other ->
        other.plan.type shouldBe BuildingType.FARM
        other.plan.idleReason shouldBe "searching-work"
        other.plan.worldWork shouldBe null
        other.route shouldBe null
    }
    if (neighbours == 1) others.single().npc.teleport(point(1.0613, -.9592))
    else others.zip(listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)).forEach { (other, offset) ->
        other.npc.teleport(control.copy(x = control.x + offset.first, z = control.z + offset.second).location(h.world))
    }
    if (neighbours == 4) for (dx in listOf(-1, 1)) for (dz in listOf(-1, 1)) {
        // This negative fixture closes diagonal support too; four residents cover only the cardinal cells.
        for (depth in 1..2) h.world.getBlockAt(control.x + dx, control.y - depth, control.z + dz).type = Material.AIR
    }
    actor.cycle.phase shouldBe CitizenPhase.TO_WORK
    citizenAtStorage(h.world, actor.npc.location.point(), control) shouldBe false
    if (cached) actor.targets[preferred] = preferred
    return StorageApproachFixture(quarry, control, actor, others, routes)
}

private fun storageActors(h: ResidentRuntime): Map<UUID, CitizenActor> = h.owner.javaClass.getDeclaredField("actors").run {
    isAccessible = true
    @Suppress("UNCHECKED_CAST")
    get(h.owner) as Map<UUID, CitizenActor>
}

private fun installCapturedBarrelApproaches(h: ResidentRuntime, control: CivPosition) {
    fun cell(dx: Int, dy: Int, dz: Int, data: String) {
        h.world.getBlockAt(control.x + dx, control.y + dy, control.z + dz).blockData = Bukkit.createBlockData("minecraft:$data")
    }
    for (dx in -1..1) for (dz in -1..1) for (dy in -1..2) cell(dx, dy, dz, "air")
    cell(0, -1, 0, "oak_slab[type=top,waterlogged=false]")
    cell(0, 0, 0, "barrel[facing=north,open=false]")
    for (dx in -1..1) {
        cell(dx, -1, -1, "cobblestone")
        cell(dx, 0, 1, "spruce_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]")
    }
    cell(-1, -1, 0, "oak_stairs[facing=west,half=top,shape=straight,waterlogged=false]")
    cell(1, -1, 0, "oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]")
    cell(-1, 0, 0, "spruce_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]")
    cell(1, 0, 0, "spruce_trapdoor[facing=west,half=bottom,open=true,powered=false,waterlogged=false]")
    cell(-1, 1, -1, "wall_torch[facing=east]")
    cell(1, 1, -1, "wall_torch[facing=west]")
}

/** Recorded local shapes, not full-cube substitutes for the native torches/trapdoors/stairs. */
private class CapturedBarrelApproachWorld : ResidentRuntimeWorld() {
    private val captured = mutableMapOf<Coordinate, BlockMock>()
    override fun getBlockAt(point: Coordinate): BlockMock {
        if (point.x !in 19..21 || point.z !in -1..1 || point.y !in 63..66) return super.getBlockAt(point)
        return captured.getOrPut(point) {
            object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
                Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
                fun boxes(): List<BoundingBox> = when (type) {
                    Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.WALL_TORCH -> emptyList()
                    Material.OAK_SLAB -> listOf(BoundingBox(0.0, .5, 0.0, 1.0, 1.0, 1.0))
                    Material.OAK_STAIRS -> if ("facing=east" in blockData.asString)
                        listOf(BoundingBox(.5, 0.0, 0.0, 1.0, 1.0, 1.0), BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0))
                    else listOf(BoundingBox(0.0, 0.0, 0.0, .5, 1.0, 1.0), BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
                    Material.SPRUCE_TRAPDOOR -> listOf(when {
                        "facing=east" in blockData.asString -> BoundingBox(0.0, 0.0, 0.0, .1875, 1.0, 1.0)
                        "facing=west" in blockData.asString -> BoundingBox(.8125, 0.0, 0.0, 1.0, 1.0, 1.0)
                        else -> BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, .1875)
                    })
                    Material.STONE, Material.COBBLESTONE, Material.BARREL -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                    else -> error("Uncaptured barrel fixture state: ${blockData.asString}")
                }
                override fun isPassable(): Boolean = boxes().isEmpty()
                override fun getBoundingBox(): BoundingBox {
                    val boxes = boxes()
                    return if (boxes.isEmpty()) BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)
                    else BoundingBox(x + boxes.minOf { it.minX }, y + boxes.minOf { it.minY }, z + boxes.minOf { it.minZ },
                        x + boxes.maxOf { it.maxX }, y + boxes.maxOf { it.maxY }, z + boxes.maxOf { it.maxZ })
                }
                override fun getCollisionShape(): VoxelShape = boxes().let { boxes -> object : VoxelShape {
                    override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
                    override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
                } }
            }
        }
    }
}
