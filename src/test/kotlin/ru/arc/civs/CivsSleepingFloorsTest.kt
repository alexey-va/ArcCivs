package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class CivsSleepingFloorsTest : FunSpec({
    test("home source is shared and a changed geometry cancels stale pending results") {
        residentRuntime { h ->
            val home = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }
            val requests = mutableListOf<CompletableFuture<CivSleepingFloorMask>>()
            CivsSleepingFloors({ CompletableFuture<CivSleepingFloorMask>().also(requests::add) },
                Logger.getAnonymousLogger()).use { owner ->
                owner.get(h.city, home) shouldBe null
                owner.get(h.city, home) shouldBe null
                requests.size shouldBe 1
                val moved = home.copy(anchor = home.anchor.copy(x = home.anchor.x + 1))
                h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == home.id) moved else it })
                owner.get(h.city, moved) shouldBe null
                requests.size shouldBe 2
                requests.first().isCancelled shouldBe true
                val mask = CivSleepingFloorMask(listOf(CivPosition(1, 64, 1)))
                requests.last().complete(mask)
                owner.get(h.city, moved) shouldBe mask
                // A control correction does not change the authored rooms; its live exclusion is checked at admission.
                val relocated = moved.copy(control = CivPosition(2, 64, 2))
                h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == home.id) relocated else it })
                owner.get(h.city, relocated) shouldBe mask
                requests.size shouldBe 2
            }
        }
    }

    test("home source retention is bounded and close cancels outstanding loads") {
        residentRuntime { h ->
            val home = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }
            val homes = (1L..65L).map { home.copy(id = UUID(2296, it)) }
            h.city = h.city.copy(buildings = homes)
            val requests = mutableListOf<CompletableFuture<CivSleepingFloorMask>>()
            val owner = CivsSleepingFloors({ CompletableFuture<CivSleepingFloorMask>().also(requests::add) }, Logger.getAnonymousLogger())
            homes.forEach { owner.get(h.city, it) shouldBe null }
            requests.size shouldBe 65
            requests.first().isCancelled shouldBe true
            owner.close()
            requests.all { it.isCancelled } shouldBe true
        }
    }

    for ((surface, expectedY, floorY) in listOf(Triple("slab", 64.5, 65), Triple("carpet", 64.0625, 64),
        Triple("path", 63.9375, 64)))
    test("sleep native feet preserve the real $surface collision top and physical guards") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = ResidentRuntimeWorld().also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val point = CivPosition(4, floorY, 4)
            val supportY = if (surface == "path") 63 else 64
            world.getBlockAt(4, supportY, 4).blockData = when (surface) {
                "slab" -> Bukkit.createBlockData("minecraft:acacia_slab[type=bottom,waterlogged=false]")
                "carpet" -> Bukkit.createBlockData(Material.LIME_CARPET)
                else -> Bukkit.createBlockData(Material.DIRT_PATH)
            }
            val feet = requireNotNull(citizenSleepFeet(world, point))
            feet.y shouldBe expectedY
            citizenSleepStanding(world, point, feet) shouldBe true
            citizenArrived(feet, feet.clone()) shouldBe true
            citizenArrived(feet, feet.clone().add(0.0, .51, 0.0)) shouldBe false
            val headY = kotlin.math.floor(expectedY + 1.6).toInt()
            world.getBlockAt(4, headY, 4).type = Material.STONE
            citizenSleepFeet(world, point) shouldBe null
            world.getBlockAt(4, headY, 4).type = Material.AIR
            world.getBlockAt(4, supportY, 4).type = Material.WATER
            citizenSleepFeet(world, point) shouldBe null
            citizenArrived(feet, feet.clone()) shouldBe false
            world.unloadChunk(0, 0)
            citizenSleepFeet(world, point) shouldBe null
        }
    }

    test("a sleeping footprint needs every margin column in one authored room plane") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("sleep-room-mask")
            val feet = Location(world, 2.5, 64.5, 2.5)
            val body = BoundingBox(.54, 64.57, 1.88, 2.57, 65.19, 3.12)
            val mask = buildSet { for (x in 0..2) for (z in 1..3) add(CivPosition(x, 65, z)) }
            citizenSleepRoom(mask, 65, feet, body) shouldBe true
            citizenSleepRoom(mask - CivPosition(0, 65, 2), 65, feet, body) shouldBe false
            citizenSleepRoom(mask, 64, feet, body) shouldBe false
            feet.y shouldBe 64.5
        }
    }

    for (surface in listOf("slab", "path", "carpet"))
    test("a resident walks to the exact $surface sleep endpoint before entering the lying pose") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            // The ordinary route also requires its existing one-chunk capture margin.
            for (x in -2..1) for (z in -1..1) h.world.loadChunk(x, z)
            h.city = h.city.copy(buildings = h.city.buildings.map { home -> home.copy(
                bounds = CivBox(CivPosition(-5, 63, -4), CivPosition(5, 68, 4))) })
            val floorY = if (surface == "slab") 65 else 64
            val cells = buildList { for (x in 0..3) for (z in 1..3) add(CivPosition(x, floorY, z)) }
            h.sleepingFloorSource = { CompletableFuture.completedFuture(CivSleepingFloorMask(
                listOf(CivPosition(2, floorY, 2)) + cells)) }
            val supportY = if (surface == "path") 63 else 64
            for (x in 0..3) for (z in 1..3) h.world.getBlockAt(x, supportY, z).blockData = when (surface) {
                "slab" -> Bukkit.createBlockData("minecraft:acacia_slab[type=bottom,waterlogged=false]")
                "carpet" -> Bukkit.createBlockData(Material.LIME_CARPET)
                else -> Bukkit.createBlockData(Material.DIRT_PATH)
            }
            h.owner.terrainChanged(h.world.uid, cells.map { it.copy(y = supportY) })
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, -3.5, 64.0, 2.5))
            h.navigationNow = 3_000
            h.world.time = 13_000
            h.owner.pulse(3_000)
            val route = requireNotNull(h.actor().route)
            val expectedY = when (surface) { "slab" -> 64.5; "path" -> 63.9375; else -> 64.0625 }
            route.authored shouldBe CivPosition(2, floorY, 2)
            route.target.y shouldBe expectedY
            npc.pose shouldBe PacketNpcPose.STANDING
            val travelled = mutableSetOf<CitizenPoint>()
            for (tick in 1..400) {
                h.navigate()
                travelled += requireNotNull(h.owner.residentFeet(h.workerId)).point()
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (npc.pose == PacketNpcPose.SLEEPING) break
            }
            npc.pose shouldBe PacketNpcPose.SLEEPING
            requireNotNull(h.owner.residentFeet(h.workerId)).y shouldBe expectedY
            (travelled.size > 10) shouldBe true
            citizenSleepClear(h.world, requireNotNull(h.actor().sleepPose).bounds) shouldBe true
            npc.scale shouldBe 1.0
            requireNotNull(h.actor().sleepPose).bounds.minY shouldBe
                (requireNotNull(h.actor().sleepOrigin).y + .07 plusOrMinus 1e-6)
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    test("a fractional shelter reserves its actual standing envelope for other residents") {
        for (raised in listOf(false, true)) residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            for (x in -2..2) for (z in -1..1) h.world.loadChunk(x, z)
            val home = h.city.buildings.single().copy(bounds =
                CivBox(CivPosition(-5, 63, -4), CivPosition(8, 70, 5)))
            val unavailable = CivBuilding(UUID(2296, 201), BuildingType.HOUSE, 1, CivPosition(30, 64, 0), 0,
                "fixture/no-room", CivBox(CivPosition(30, 64, 0), CivPosition(30, 64, 0)),
                CivPosition(30, 64, 0), BuildingPhase.ACTIVE)
            val neighbour = CivWorker(UUID(2296, 202), "Neighbour", null, homeId = unavailable.id)
            h.city = h.city.copy(buildings = listOf(home, unavailable),
                workers = h.city.workers.map { it.copy(homeId = home.id) } + neighbour)
            h.owner.pulse(2_000)
            h.actor().npc.teleport(Location(h.world, -3.5, 64.0, 2.5))
            h.actor(neighbour.id).npc.teleport(Location(h.world, 12.5, 64.0, 2.5))
            val floorY = if (raised) 64 else 65
            val target = CivPosition(2, floorY, 2)
            val cells = buildList { for (x in 0..3) for (z in 1..3) add(CivPosition(x, floorY, z)) }
            h.sleepingFloorSource = { building -> CompletableFuture.completedFuture(CivSleepingFloorMask(
                if (building.id == home.id) listOf(target) + cells.filter { it != target } else emptyList())) }
            val slab = Bukkit.createBlockData("minecraft:acacia_slab[type=bottom,waterlogged=false]")
            for (x in 0..3) for (z in 1..3) h.world.getBlockAt(x, 64, z).blockData =
                if (raised) Bukkit.createBlockData(Material.DIRT_PATH) else slab.clone()
            // The adjacent ledge supports a clear body which slightly overhangs this column.
            val ledge = CivPosition(3, if (raised) 65 else 66, 2)
            h.world.getBlockAt(ledge.x, ledge.y, ledge.z).blockData =
                if (raised) Bukkit.createBlockData(Material.STONE) else slab.clone()
            h.owner.terrainChanged(h.world.uid, cells.map { it.copy(y = 64) } + ledge)
            h.navigationNow = 3_000
            h.world.time = 13_000
            h.owner.pulse(3_000)
            h.actor().shelter shouldBe target
            val reserved = requireNotNull(h.actor().shelterFeet)
            reserved.y shouldBe if (raised) 64.9375 else 64.5
            citizenSleepStanding(h.world, target, reserved) shouldBe true
            citizenSleepClear(h.world, requireNotNull(h.actor().shelterPose).bounds) shouldBe true
            val roommate = Location(h.world, 3.05, if (raised) 66.0 else 66.5, 2.5)
            citizenPacketSupported(h.world, roommate.point(), false) shouldBe true
            citizenPacketBodyClear(h.world, roommate.point(), false) shouldBe true
            citizenStandingBounds(reserved, h.actor().npc.scale)
                .overlaps(citizenStandingBounds(roommate, h.actor(neighbour.id).npc.scale)) shouldBe raised
            val collisions = h.owner.javaClass.getDeclaredField("collisions").apply { isAccessible = true }
                .get(h.owner) as CivsCitizenCollisions
            collisions.residentMoveAllowed(h.actor(neighbour.id), roommate, roommate) shouldBe !raised
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    test("fallback homes each advance through their own floor candidates under the shared admission budget") {
        residentRuntime { h ->
            val hall = h.bootstrapShelterHome()
            for (x in -2..4) for (z in -2..2) h.world.loadChunk(x, z)
            fun house(id: Long, x: Int) = hall.copy(id = UUID(2296, id), type = BuildingType.HOUSE,
                phase = BuildingPhase.ACTIVE, anchor = CivPosition(x, 64, 0), entrance = CivPosition(x, 64, 5),
                control = null, bounds = CivBox(CivPosition(x - 6, 63, -6), CivPosition(x + 8, 68, 5)))
            val first = house(151, 20)
            val second = house(152, 40)
            fun points(x: Int) = buildList { for (dx in 0..9) for (z in -4..3) add(CivPosition(x + dx, 64, z)) }
            val firstPoints = points(16)
            val secondPoints = points(36)
            h.city = h.city.copy(buildings = listOf(hall, first, second),
                workers = h.city.workers.map { it.copy(homeId = hall.id) })
            h.sleepingFloorSource = { home -> CompletableFuture.completedFuture(CivSleepingFloorMask(
                if (home.id == first.id) firstPoints else secondPoints)) }
            (firstPoints.take(40) + secondPoints).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.STONE }
            h.actor().npc.teleport(Location(h.world, .5, 64.0, 8.5))
            h.reserveShelter() shouldBe null
            h.actor().shelterSearchIndex shouldBe 80
            val admitted = requireNotNull(h.reserveShelter())
            (admitted in firstPoints.drop(40)) shouldBe true
            h.actor().shelterHome shouldBe first.id
            (h.actor().shelterSearchIndex <= 160) shouldBe true
            citizenSleepClear(h.world, requireNotNull(h.actor().shelterPose).bounds) shouldBe true
        }
    }

    test("a completed home with pending or absent rooms prevents outdoor bootstrap sleep") {
        residentRuntime { h ->
            val hall = h.bootstrapShelterHome()
            val house = CivBuilding(UUID(2296, 99), BuildingType.HOUSE, 1, CivPosition(22, 64, 0), 0,
                "sleep-room", CivBox(CivPosition(18, 63, -2), CivPosition(26, 68, 2)),
                CivPosition(22, 64, -3), BuildingPhase.ACTIVE)
            h.city = h.city.copy(buildings = listOf(hall, house), workers = h.city.workers.map { it.copy(homeId = hall.id) })
            val pending = CompletableFuture<CivSleepingFloorMask>()
            h.sleepingFloorSource = { pending }
            val npc = h.owner.collisionActors().single()
            val origin = Location(h.world, 8.8, 64.0, 8.2)
            npc.teleport(origin)
            val before = h.city
            h.world.time = 13_000
            h.owner.pulse(3_000)
            npc.pose shouldBe PacketNpcPose.STANDING
            npc.location shouldBe origin
            h.actor().sheltering shouldBe true
            h.actor().route shouldBe null
            pending.complete(CivSleepingFloorMask(emptyList()))
            h.owner.pulse(4_000)
            npc.pose shouldBe PacketNpcPose.STANDING
            npc.location shouldBe origin
            h.actor().route shouldBe null
            h.city shouldBe before
        }
    }
})
