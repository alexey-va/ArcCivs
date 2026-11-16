package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsRoadApproachCleanupTest : FunSpec({
    test("only generated ROAD transit covered at the exact permanent grade retires its construction approach") {
        val f = roadApproachFixture()
        val access = CivAutonomousAccess.from(f.city, f.plans)
        CivsWallApproachCleanup.retired(f.city, access.routes) shouldBe setOf(f.road.id)
        val targets = CivsWallApproachCleanup.targets(f.city, access)
        val expected = (listOf(7, 8) + (12..27)).flatMap { z -> (-1..1).map { x -> CivPosition(x, 64, z) } }.toSet() +
            setOf(CivPosition(-1, 64, 28), CivPosition(1, 64, 28))
        targets.map { it.floor }.toSet() shouldBe expected
        targets.forEach { it.validated(f.city) }
        access.routes.getValue(f.road.id) shouldBe f.roadRoute
        val board = civsPlanBoardSnapshot(f.city, f.plans)
        board.routes shouldBe access.publicRoutes(f.city)
        board.routes.keys shouldBe access.routes.keys - f.road.id
        listOf(f.house, f.gate, f.otherRoad).forEach { owner ->
            board.routes.getValue(owner.id) shouldBe access.routes.getValue(owner.id)
            targets.none { CivsAutonomousTerraform.roadProfileContains(access.routes.getValue(owner.id), it.floor) } shouldBe true
        }
        f.city.buildings.flatMap { it.transit.orEmpty() }.forEach { feet ->
            targets.none { it.floor.x == feet.x && it.floor.z == feet.z } shouldBe true
        }
        // The same XZ mask one block higher is not coverage. ROAD approaches are never donors.
        val raised = f.road.copy(transit = f.road.transit!!.map { it.copy(y = it.y + 1) })
        val raisedCity = f.city.copy(buildings = f.city.buildings.map { if (it.id == raised.id) raised else it })
        CivsWallApproachCleanup.retired(raisedCity, access.routes) shouldBe emptySet()
        val dependent = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.house.id) it.copy(type = BuildingType.ROAD) else it })
        CivsWallApproachCleanup.retired(dependent, access.routes) shouldBe emptySet()
        val unsupported = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.road.id) it.copy(schematicId = "custom/road") else it })
        CivsWallApproachCleanup.retired(unsupported, access.routes) shouldBe emptySet()
        // A GATE's paid full profile is a valid independent witness too.
        val gateDonor = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.house.id) it.copy(type = BuildingType.GATE) else it })
        CivsWallApproachCleanup.retired(gateDonor, access.routes) shouldBe setOf(f.road.id)
    }

    test("missing noneligible ROAD claims stop cleanup while an adopted reservation keeps its complete approach") {
        val f = roadApproachFixture()
        val missing = f.plans.filterNot { it.building.id == f.otherRoad.id }
        val incomplete = CivAutonomousAccess.from(f.city, missing)
        CivsWallApproachCleanup.targets(f.city, incomplete) shouldBe emptyList()
        incomplete.publicRoutes(f.city) shouldBe incomplete.routes
        civsPlanBoardSnapshot(f.city, missing).routes shouldBe incomplete.routes
        val reservation = CivSettlementPlan(1, 0, 48, emptyList(),
            mapOf(f.otherRoad.id.toString() to f.plans.single { it.building.id == f.otherRoad.id }.terrain!!.route),
            emptyList(), listOf(CivBox(CivPosition(40, 64, 40), CivPosition(44, 64, 44))), listOf(0))
        val city = f.city.copy(autonomy = CivAutonomy(plan = reservation))
        val targets = CivsWallApproachCleanup.targets(city, CivAutonomousAccess.from(city, missing))
        targets.map { it.floor }.toSet() shouldBe CivsWallApproachCleanup.targets(f.city, CivAutonomousAccess.from(f.city, f.plans))
            .map { it.floor }.toSet()
        targets.forEach { it.validated(city) }
        val public = CivAutonomousAccess.from(city, missing).publicRoutes(city)
        public shouldBe incomplete.routes - f.road.id
        civsPlanBoardSnapshot(city, missing).routes shouldBe public
        for (route in listOf(emptyList(), reservation.routes.getValue(f.otherRoad.id.toString()).dropLast(1))) {
            val stale = city.copy(autonomy = CivAutonomy(plan = reservation.copy(routes = mapOf(f.otherRoad.id.toString() to route))))
            val access = CivAutonomousAccess.from(stale, missing)
            access.publicRoutes(stale) shouldBe access.routes
            civsPlanBoardSnapshot(stale, missing).routes shouldBe access.routes
            CivsWallApproachCleanup.targets(stale, access) shouldBe emptyList()
        }
        val original = f.plans.single { it.building.id == f.road.id }
        listOf(original.copy(adminPlacement = true), original.copy(id = UUID(314, 99)),
            original.copy(building = original.building.copy(transit = original.building.transit!!.drop(1)))).forEach { foreign ->
            val access = CivAutonomousAccess.from(f.city, f.plans.map { if (it.id == original.id) foreign else it })
            (f.road.id in access.routes) shouldBe false
            CivsWallApproachCleanup.targets(f.city, access) shouldBe emptyList()
        }
    }

    test("the detached adopted sketch cannot reintroduce retired ROAD or WALL approaches and retains queued work") {
        val f = roadApproachFixture()
        val wallId = UUID(314, 80)
        val wall = f.road.copy(id = wallId, type = BuildingType.WALL, anchor = CivPosition(20, 64, 29),
            bounds = CivBox(CivPosition(20, 64, 29), CivPosition(22, 69, 31)), entrance = CivPosition(20, 65, 28),
            schematicId = "civs/infrastructure/v1/wall/$wallId", transit = null)
        val wallRoute = (0..20).map { CivPosition(it, 65, 0) } + (1..28).map { CivPosition(20, 65, it) }
        val otherRoute = f.plans.single { it.building.id == f.otherRoad.id }.terrain!!.route
        val plan = CivSettlementPlan(1, 0, 48, emptyList(), mapOf(f.road.id.toString() to f.roadRoute,
            wall.id.toString() to wallRoute, f.otherRoad.id.toString() to otherRoute), emptyList(),
            listOf(CivBox(CivPosition(40, 64, 40), CivPosition(44, 64, 44))), listOf(0))
        val city = f.city.copy(buildings = f.city.buildings + wall, autonomy = CivAutonomy(plan = plan))
        val captures = f.plans + CivPlacementPlan(wall.id, city.id, city.worldId, wall, emptyList(),
            terrain = CivTerraformPlan(wall.bounds, emptyList(), wallRoute, 64, 0, 0))
        CivsSettlementPlans.current(city) shouldBe plan
        val snapshot = civsPlanBoardSnapshot(city, captures)
        snapshot.routes.keys shouldBe f.plans.mapTo(hashSetOf()) { it.building.id } - f.road.id
        snapshot.plan!!.routes shouldBe mapOf(f.otherRoad.id.toString() to otherRoute)
        val displayed = (snapshot.routes.values + snapshot.plan!!.routes.values).flatten()
        displayed.none { it == CivPosition(0, 65, 7) || it == CivPosition(20, 65, 15) } shouldBe true
        CivsSettlementPlans.current(city) shouldBe plan
        plan.routes.keys shouldBe setOf(f.road.id.toString(), wall.id.toString(), f.otherRoad.id.toString())
        val queued = city.copy(buildings = city.buildings.map { if (it.id == wall.id) it.copy(phase = BuildingPhase.SITE) else it },
            constructionQueue = listOf(wall.id))
        CivsSettlementPlans.current(queued) shouldBe plan
        val future = civsPlanBoardSnapshot(queued, captures)
        future.plan!!.routes shouldBe mapOf(wall.id.toString() to wallRoute, f.otherRoad.id.toString() to otherRoute)
        future.routes.getValue(f.otherRoad.id) shouldBe otherRoute
        CivsWallApproachCleanup.targets(queued, CivAutonomousAccess.from(queued, captures)) shouldBe emptyList()
    }

    test("ROAD cleanup replays one exact paid flat write and refuses changed native or owner witnesses") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("road-approach-cleanup", ground = Material.AIR, collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString) || data.material == Material.GRASS_BLOCK)
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..2) world.loadChunk(x, z)
            val f = roadApproachFixture(world.uid)
            var city = f.city
            CivsRules.validate(city) shouldBe city
            for (x in -12..12) for (z in -4..36) for (y in 61..64)
                world.getBlockAt(x, y, z).type = if (y == 64) Material.GRASS_BLOCK else Material.DIRT
            f.plans.flatMap { it.terrain!!.blocks }.forEach { change ->
                val p = change.position
                world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(change.after), false)
            }
            val access = CivAutonomousAccess.from(city, f.plans)
            val selected = CivsWallApproachCleanup.targets(city, access).single { it.floor == CivPosition(0, 64, 7) }
            val before = ((-2..6).flatMap { x -> (0..32).map { z -> CivPosition(x, 64, z) } } +
                f.plans.flatMap { it.terrain!!.blocks }.map { it.position } +
                city.buildings.flatMap { it.transit.orEmpty() }.map { it.copy(y = it.y - 1) })
                .associateWith { world.getBlockAt(it.x, it.y, it.z).blockData.asString }
            var permitted = true
            val native = CivsRoadWorld(paper.createSimplePlugin("RoadCleanupTest"), { listOf(city) }, { _, _, _ -> permitted }, { false })
            val floor = selected.floor
            world.getBlockAt(floor.x, floor.y, floor.z).type = Material.DIRT_PATH
            native.captureRoadUpkeep(city, city.workers.single(), selected) shouldBe null
            world.getBlockAt(floor.x, floor.y, floor.z).type = Material.STONE_BRICKS
            native.captureRoadUpkeep(city, city.workers.single(), selected) shouldBe null
            world.getBlockAt(floor.x, floor.y, floor.z).type = Material.COARSE_DIRT
            permitted = false
            native.captureRoadUpkeep(city, city.workers.single(), selected) shouldBe null
            permitted = true
            val captured = native.captureRoadUpkeep(city, city.workers.single(), selected)!!
            val work = captured.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
            val drift = city.copy(buildings = city.buildings.map { if (it.id == f.road.id)
                it.copy(transit = it.transit!!.map { p -> p.copy(y = p.y + 1) }) else it })
            shouldThrowAny { selected.validated(drift) }
            shouldThrowAny { selected.validated(city.copy(buildings = city.buildings.filterNot { it.id == f.house.id })) }
            shouldThrowAny { selected.validated(city.copy(constructionQueue = listOf(f.otherRoad.id))) }
            val missingOwner = selected.copy(wallCleanup = selected.wallCleanup!!.copy(
                owners = selected.wallCleanup.owners.filterNot { it.source.id == f.otherRoad.id }))
            shouldThrowAny { missingOwner.validated(city) }
            val after = CivsRoadMaintenance.complete(city, work, 3_000)
            val receipt = CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, 3_000)
            val tx = CivInventoryTransaction(UUID(314, 90), city.id, city.revision, after, emptyList(),
                worldChanges = work.changes, upkeep = receipt).validated()
            Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated() shouldBe tx
            receipt.before(after) shouldBe city
            native.validateRoadUpkeep(city, work)
            work.changes.single().let { change ->
                change.position shouldBe floor
                change.before shouldBe "minecraft:coarse_dirt"
                change.after shouldBe CivsEntrancePath.GRASS
                world.getBlockAt(floor.x, floor.y, floor.z).setBlockData(Bukkit.createBlockData(change.after), false)
            }
            native.validateRoadUpkeep(city, work)
            native.validateRoadUpkeep(after, work)
            city = after
            before.filterKeys { it != floor }.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            city.workers.single().completedCycles shouldBe 1
            CivsEquipment.usableCount(city.workers.single(), "COBBLESTONE") shouldBe 63
            CivAutonomousAccess.from(city, f.plans).routes shouldBe access.routes
        }
    }

    test("historical 93 ROAD geometry retains every dependent approach and all exact public transit") {
        val source = planBoardAutoFixture()
        source.buildings.count { it.type == BuildingType.ROAD } shouldBe 93
        val city = Settlement(source.cityId, UUID(314, 91), source.name, source.worldId, source.center, source.radius,
            "caledonia", Stock(), source.buildings, emptyList())
        val access = CivAutonomousAccess(routes = source.routes)
        val expected = setOf("b9be379a-9f4b-38b9-a416-cb604cd7429d", "34fe7134-8915-33d8-b63e-dc22a3168ff3",
            "f11eab79-b738-3f3c-b762-2dc88f6af6c3", "bf06388a-c9f8-398e-a69b-7fb99074f4d6", "9a63f393-2c2d-36a2-9a6b-b2947dcb4a7a",
            "23982db1-9199-3ad0-a180-d43b490cfbc5", "d93bafec-8bed-3cb5-bcba-26fa5b16d050", "56069bee-003d-3a4d-9506-df93817d37f2",
            "40a7262c-5ccc-3700-bfc5-2ca4096d1263", "979c0bce-a416-34b7-8553-9c9b01f21f3c", "93ff5122-71a0-3a38-b5e7-1ad4adec42d2",
            "adaeb27d-deb1-3813-9ad3-895c84b820a1", "d812a5de-b061-3f38-af11-e454b1f99c2e", "55e17046-80ad-374a-b7eb-52ffc4f8b9a9",
            "99bcb45f-1566-36da-9ada-efed97ba1997", "29bfbe13-bc34-3329-935e-ae47c365b803", "414cc541-d216-3dd9-9f08-7ce3fc579e3c",
            "e3a57e6c-ae7b-322c-9a3c-c3692541cf95").mapTo(hashSetOf(), UUID::fromString)
        CivsWallApproachCleanup.retired(city, access.routes) shouldBe expected
        val public = access.publicRoutes(city)
        public shouldBe source.routes.filterKeys { it !in expected }
        public.keys.count { city.building(it)!!.type == BuildingType.ROAD } shouldBe 75
        val permanent = source.routes.filterKeys { city.building(it)!!.type !in setOf(BuildingType.ROAD, BuildingType.WALL) }
            .values.flatMap { CivsAutonomousTerraform.roadProfile(it).values }.toSet()
        expected.forEach { id -> city.building(id)!!.transit!!.forEach { feet ->
            (feet.copy(y = feet.y - 1) in permanent) shouldBe true
        } }
        val layout = CivsPlanBoardGeometry.layout(source.copy(routes = public))
        layout.roadCells shouldBe (public.values.flatten() + source.buildings.flatMap { it.transit.orEmpty() })
            .mapTo(hashSetOf()) { CivPlanBoardCell(it.x.toLong(), it.z.toLong()) }
        // The historical resource contains geometry, not native before-images. Without authored
        // capture cells it licenses no world writes, even though these 18 approaches are redundant.
        CivsWallApproachCleanup.targets(city, access) shouldBe emptyList()
    }
})

private data class RoadApproachFixture(val city: Settlement, val plans: List<CivPlacementPlan>, val road: CivBuilding,
    val house: CivBuilding, val gate: CivBuilding, val otherRoad: CivBuilding, val roadRoute: List<CivPosition>)

private fun roadApproachFixture(worldId: UUID = UUID(314, 1)): RoadApproachFixture {
    fun building(id: Long, type: BuildingType, x: Int, z: Int, entry: CivPosition, transit: List<CivPosition>? = null): CivBuilding {
        val uuid = UUID(314, id)
        return CivBuilding(uuid, type, 1, CivPosition(x, 64, z), 0,
            if (type == BuildingType.ROAD) "civs/infrastructure/v1/road/$uuid" else "civs/v2/fixture",
            CivBox(CivPosition(x, 64, z), CivPosition(x + 2, 69, z + 2)), entry, BuildingPhase.ACTIVE, transit = transit)
    }
    val hall = building(2, BuildingType.TOWN_HALL, -40, -40, CivPosition(-39, 65, -37))
    val hut = building(3, BuildingType.BUILDER_HUT, -30, -30, CivPosition(-29, 65, -27))
    val house = building(4, BuildingType.HOUSE, -4, 31, CivPosition(-1, 65, 30))
    val gate = building(5, BuildingType.GATE, -8, 9, CivPosition(-5, 65, 10),
        (-8..-6).flatMap { x -> (9..11).map { z -> CivPosition(x, 65, z) } })
    val road = building(6, BuildingType.ROAD, -1, 29, CivPosition(0, 65, 28),
        (-1..1).flatMap { x -> (29..31).map { z -> CivPosition(x, 65, z) } })
    val other = building(7, BuildingType.ROAD, 6, 6, CivPosition(6, 65, 5),
        (6..8).flatMap { x -> (6..8).map { z -> CivPosition(x, 65, z) } })
    val worker = CivsEquipment.replace(CivWorker(UUID(314, 8), "Builder", hut.id, nextDeliveryAt = 1),
        MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64) })
    val city = Settlement(UUID(314, 9), UUID(314, 10), "Road approaches", worldId, CivPosition(0, 65, 0), 96,
        "caledonia", Stock(), listOf(hall, hut, house, gate, road, other), listOf(worker),
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy())
    val roadRoute = (0..28).map { CivPosition(0, 65, it) }
    val shared = (0..5).map { CivPosition(it, 65, 0) }
    val routes = mapOf(hut to listOf(hut.entrance),
        house to (shared + (1..30).map { CivPosition(5, 65, it) } + (4 downTo -1).map { CivPosition(it, 65, 30) }),
        gate to (shared + (1..10).map { CivPosition(5, 65, it) } + (4 downTo -5).map { CivPosition(it, 65, 10) }),
        road to roadRoute, other to ((0..5).map { CivPosition(0, 65, it) } + (1..6).map { CivPosition(it, 65, 5) }))
    val plans = routes.map { (owner, points) -> CivPlacementPlan(owner.id, city.id, city.worldId, owner, emptyList(),
        terrain = CivTerraformPlan(owner.bounds, CivsAutonomousTerraform.roadProfile(points).values.map {
            CivPlanBlock(it, CivsEntrancePath.GRASS, "minecraft:coarse_dirt") }, points, 64, 0, 0)) }
    return RoadApproachFixture(city, plans, road, house, gate, other, roadRoute)
}
