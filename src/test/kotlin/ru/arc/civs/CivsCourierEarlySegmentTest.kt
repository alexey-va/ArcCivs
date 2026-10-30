package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import kotlin.math.sqrt

class CivsCourierEarlySegmentTest : FunSpec({
    test("a freshly obstructed hinted return walks eight metres of native progress before its exact depot receipt") {
        earlySegmentWorld { paper, world ->
            val depot = CitizenPoint(.5, 64.0, .5)
            val source = CitizenPoint(60.5, 64.0, .5)
            val warehouse = earlySegmentBuilding(BuildingType.WAREHOUSE, 0)
            val forester = earlySegmentBuilding(BuildingType.FORESTER, 60).copy(production = "OAK_LOG")
            val worker = CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1)
            var city = CivsRules.validate(pathsCity().copy(worldId = world.uid, radius = 128,
                buildings = pathsCity().buildings + listOf(warehouse, forester), workers = listOf(worker),
                inventory = CivInventory(CivStorageMode.PHYSICAL)))
            for (building in listOf(warehouse, forester)) {
                val control = requireNotNull(building.control)
                world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            }
            val sourceBin = (world.getBlockAt(60, 64, -1).state as Barrel).inventory
            val depotBin = (world.getBlockAt(0, 64, -1).state as Barrel).inventory
            sourceBin.setItem(0, ItemStack(Material.OAK_LOG, 54))
            val offered = requireNotNull(CivsLogistics.plan(city, worker) {
                if (it == forester.id) mapOf("OAK_LOG" to 54L) else emptyMap()
            })
            val trip = CivsPaths().begin(city)
            val inbound = earlySegmentRoute(world, depot, source, null, lineOnly = true).also(::prepareEarlySegment)
            walkEarlySegment(world, inbound, depot) { trip.observe(it) } shouldBe source
            val hint = requireNotNull(CitizenCourierReturnHint.capture(city, offered.cargo, trip, depot, source))
            CivsStorage(paper.createSimplePlugin("EarlyCourierSegment")) { emptyArray() }.use { storage ->
                val pickupId = UUID.randomUUID()
                storage.reserve(city, pickupId)
                val pickup = storage.prepareCargoPickup(city, offered.cargo)
                val picked = CivsLogistics.pickup(city, worker.id, offered.cargo).copy(inventory = pickup.inventoryAfter)
                val pickupReceipt = CivInventoryTransaction(pickupId, city.id, city.revision, picked, pickup.changes).validated()
                storage.seal(pickupReceipt)
                storage.apply(pickupReceipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(pickupId)
                city = picked
                sourceBin.isEmpty shouldBe true
                depotBin.isEmpty shouldBe true
                val carried = requireNotNull(city.workers.single().cargo)
                carried shouldBe offered.cargo.copy(inventoryBacked = true)
                val loadedWorker = city.workers.single()
                val corridor = requireNotNull(hint.corridor(city, carried, source, depot))

                // The incoming walk was clear. Its hint supplies no authority to cross this new wall.
                earlySegmentWall(world)
                val first = earlySegmentRoute(world, source, depot, corridor).also(::prepareEarlySegment)
                withClue("nodes=${first.nodeCount} blocks=${first.capturedBlocks} endpoint=${first.endpoint} failure=${first.failure}") {
                    first.failure shouldBe null
                    first.continuation shouldBe true
                    first.nodeCount shouldBe 512
                }
                val endpoint = requireNotNull(first.endpoint)
                (sqrt(source.distanceSquared(depot)) - sqrt(endpoint.distanceSquared(depot)) >= 8.0) shouldBe true
                citizenAtStorage(world, endpoint, requireNotNull(warehouse.control)) shouldBe false
                var at = walkEarlySegment(world, first, source)
                at shouldBe endpoint
                city.workers.single() shouldBe loadedWorker
                depotBin.isEmpty shouldBe true
                var segments = 1
                while (at != depot && segments++ <= 32) {
                    // The one consumed hint is absent from every continuation.
                    val continuation = earlySegmentRoute(world, at, depot, null).also(::prepareEarlySegment)
                    continuation.hasCourierCorridorHint shouldBe false
                    continuation.failure shouldBe null
                    if (continuation.continuation) continuation.nodeCount shouldBe 4097
                    at = walkEarlySegment(world, continuation, at)
                }
                at shouldBe depot
                citizenAtStorage(world, at, requireNotNull(warehouse.control)) shouldBe true
                city.workers.single() shouldBe loadedWorker

                val dropId = UUID.randomUUID()
                storage.reserve(city, dropId)
                val drop = storage.prepareCargoDropoff(city, carried, warehouse.id)
                val unloaded = CivsLogistics.unload(city, worker.id, carried.id, 20_000).copy(inventory = drop.inventoryAfter)
                val receipt = CivInventoryTransaction(dropId, city.id, city.revision, unloaded, drop.changes).validated()
                receipt.changes.map { it.container }.toSet() shouldBe setOf(warehouse.control)
                depotBin.isEmpty shouldBe true
                storage.seal(receipt)
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                depotBin.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 54)
                sourceBin.isEmpty shouldBe true
                unloaded.workers.single().cargo shouldBe null
                CivsEquipment.materials(unloaded.workers.single())["OAK_LOG"] shouldBe null
                unloaded.workers.single().completedCycles shouldBe worker.completedCycles + 1
                storage.unlock(dropId)
            }
        }
    }

    test("a hinted search without eight metres of admitted progress does not emit a short segment") {
        earlySegmentWorld { _, world ->
            val source = CitizenPoint(60.5, 64.0, .5)
            val depot = CitizenPoint(.5, 64.0, .5)
            val route = CitizenPacketRoute(world, source.location(world), depot.location(world),
                { it.y == 64 && it.x in 53..62 && it.z in -30..30 }, { _, _ -> true },
                { CivsPaths.ORDINARY_COST }, corridor = earlySegmentCorridor(source, depot)).also(::prepareEarlySegment)
            withClue("nodes=${route.nodeCount} blocks=${route.capturedBlocks} failure=${route.failure}") {
                (route.nodeCount >= 512) shouldBe true
                route.continuation shouldBe false
                route.failure shouldBe "no-path"
            }
        }
    }

    test("the same native detour without a hint retains its ordinary full goal or hard node budget") {
        earlySegmentWorld { _, world ->
            earlySegmentWall(world)
            val route = earlySegmentRoute(world, CitizenPoint(60.5, 64.0, .5), CitizenPoint(.5, 64.0, .5), null)
                .also(::prepareEarlySegment)
            route.failure shouldBe null
            (route.nodeCount > 512) shouldBe true
            if (route.continuation) route.nodeCount shouldBe 4097
            else route.endpoint shouldBe CitizenPoint(.5, 64.0, .5)
        }
    }

    test("a short hinted return completes its exact full goal before any early segment") {
        earlySegmentWorld { _, world ->
            val source = CitizenPoint(10.5, 64.0, .5)
            val depot = CitizenPoint(.5, 64.0, .5)
            val route = earlySegmentRoute(world, source, depot, earlySegmentCorridor(source, depot), lineOnly = true)
                .also(::prepareEarlySegment)
            route.failure shouldBe null
            route.continuation shouldBe false
            (route.nodeCount < 512) shouldBe true
            route.endpoint shouldBe depot
            walkEarlySegment(world, route, source) shouldBe depot
        }
    }
})

private fun earlySegmentWorld(action: (MockBukkitTestRuntime, CivsWorksiteWorldFixture) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("early-courier-segment").also(paper.server::addWorld)
            for (x in -1..4) for (z in -3..2) world.loadChunk(x, z)
            action(paper, world)
        }
    }

private fun earlySegmentBuilding(type: BuildingType, x: Int) = CivBuilding(UUID.randomUUID(), type, 1,
    CivPosition(x, 64, -1), 0, "fixture/${type.name.lowercase()}",
    CivBox(CivPosition(x - 2, 63, -3), CivPosition(x + 2, 68, -1)), CivPosition(x, 64, 0),
    BuildingPhase.ACTIVE, control = CivPosition(x, 64, -1))

private fun earlySegmentWall(world: World) {
    for (z in -20..20) for (y in 64..67) world.getBlockAt(30, y, z).type = Material.STONE
}

private fun earlySegmentCorridor(from: CitizenPoint, to: CitizenPoint) = requireNotNull(CitizenSearchCorridor.fromWalked(
    (0..60).mapTo(hashSetOf()) { CivPosition(it, 63, 0) }, from, to))

private fun earlySegmentRoute(world: World, from: CitizenPoint, to: CitizenPoint,
    corridor: CitizenSearchCorridor?, lineOnly: Boolean = false) = CitizenPacketRoute(world, from.location(world), to.location(world),
    { it.y == 64 && it.x in -2..62 && (if (lineOnly) it.z == 0 else it.z in -24..24) },
    { _, _ -> true }, { if (lineOnly) 1.0 else CivsPaths.ORDINARY_COST }, corridor = corridor)

private fun CitizenPoint.location(world: World) = Location(world, x, y, z)

private fun prepareEarlySegment(route: CitizenPacketRoute) {
    var operations = 0
    while (route.searching && operations++ < 500_000) route.expand()
    withClue("nodes=${route.nodeCount} blocks=${route.capturedBlocks} phase=${route.preparationPhase} failure=${route.failure}") {
        route.searching shouldBe false
        (route.capturedBlocks <= CivsNavigationSettings().maximumCapturedBlocks) shouldBe true
    }
}

private fun walkEarlySegment(world: World, route: CitizenPacketRoute, from: CitizenPoint,
    observe: (CitizenPoint) -> Unit = {}): CitizenPoint {
    var at = from.location(world)
    observe(from)
    repeat(2_000) {
        if (!route.finished) route.move(at, 1.0) { true }?.let { next ->
            citizenPacketSupported(world, next.point()) shouldBe true
            citizenPacketBodyClear(world, next.point(), false) shouldBe true
            citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
            at = next
            observe(at.point())
        }
    }
    withClue("at=${at.point()} failure=${route.failure} remaining=${route.remainingNodes}") {
        route.failure shouldBe null
        route.finished shouldBe true
    }
    return at.point()
}
