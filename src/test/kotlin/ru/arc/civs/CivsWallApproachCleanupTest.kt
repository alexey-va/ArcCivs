package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CivsWallApproachCleanupTest : FunSpec({
    test("completed wall work restores only its authored flat surface and preserves public and missing-journal entrance claims") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("wall-cleanup", ground = Material.AIR, collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString) || data.material == Material.GRASS_BLOCK)
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..2) world.loadChunk(x, z)
            fun building(id: Long, type: BuildingType, x: Int, z: Int, entry: CivPosition) = CivBuilding(UUID(293, id), type, 1,
                CivPosition(x, 64, z), 0, "fixture/building", CivBox(CivPosition(x, 64, z), CivPosition(x + 2, 69, z + 2)), entry, BuildingPhase.ACTIVE)
            val hall = building(1, BuildingType.TOWN_HALL, -40, -40, CivPosition(-39, 65, -37))
            val hut = building(2, BuildingType.BUILDER_HUT, -30, -30, CivPosition(-29, 65, -27))
            val wall = building(3, BuildingType.WALL, -1, 31, CivPosition(0, 65, 30))
            val publicRoad = building(4, BuildingType.HOUSE, 5, 11, CivPosition(5, 65, 10))
            val missing = building(5, BuildingType.HOUSE, 5, 21, CivPosition(5, 65, 20))
            val apron = building(6, BuildingType.HOUSE, 4, 15, CivPosition(0, 65, 16))
            val route = (0..30).map { CivPosition(0, 65, it) }
            val publicRoute = (0..5).map { CivPosition(it, 65, 10) }
            val missingRoute = (0..5).map { CivPosition(it, 65, 20) }
            val authored = route.drop(1).map { CivPlanBlock(it.copy(y = 64), "minecraft:grass_block[snowy=false]", "minecraft:coarse_dirt") }
            val worker = CivsEquipment.replace(CivWorker(UUID(293, 7), "Builder", hut.id, nextDeliveryAt = 1),
                MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64) })
            val plan = CivSettlementPlan(1, 0, 48, emptyList(), mapOf(missing.id.toString() to missingRoute,
                hut.id.toString() to listOf(hut.entrance), apron.id.toString() to listOf(apron.entrance)), emptyList(),
                listOf(CivBox(CivPosition(40, 64, 40), CivPosition(44, 64, 44))), listOf(0))
            var city = Settlement(UUID(293, 8), UUID(293, 9), "Wall cleanup", world.uid, route.first(), 96, "caledonia", Stock(),
                listOf(hall, hut, wall, publicRoad, missing, apron), listOf(worker), autonomy = CivAutonomy(plan = plan),
                inventory = CivInventory(CivStorageMode.PHYSICAL))
            CivsSettlementPlans.current(city) shouldBe plan
            val captures = listOf(wall to route, publicRoad to publicRoute).map { (b, points) ->
                CivPlacementPlan(b.id, city.id, city.worldId, b, emptyList(),
                    terrain = CivTerraformPlan(b.bounds, if (b == wall) authored else emptyList(), points, 64, 0, 0))
            }
            for (x in -3..8) for (z in -3..33) for (y in 61..64)
                world.getBlockAt(x, y, z).type = if (y == 64) Material.GRASS_BLOCK else Material.DIRT
            authored.forEach { world.getBlockAt(it.position.x, 64, it.position.z).type = Material.COARSE_DIRT }
            val access = CivAutonomousAccess.from(city, captures)
            val targets = CivsWallApproachCleanup.targets(city, access)
            (targets.isNotEmpty()) shouldBe true
            targets.forEach { it.validated(city) }
            targets.none { it.floor.z in 9..11 || it.floor.z in 16..21 || it.floor.z == 30 } shouldBe true
            CivsWallApproachCleanup.targets(city.copy(constructionQueue = listOf(wall.id)), access) shouldBe emptyList()
            val queuedSite = missing.copy(id = UUID(293, 12), phase = BuildingPhase.SITE)
            val queued = city.copy(buildings = city.buildings + queuedSite, constructionQueue = listOf(queuedSite.id))
            CivsWallApproachCleanup.targets(queued, access) shouldBe emptyList()
            shouldThrowAny { targets.first().validated(queued) }
            CivsWallApproachCleanup.targets(city.copy(autonomy = CivAutonomy()), access) shouldBe emptyList()
            val board = civsPlanBoardSnapshot(city, captures)
            (wall.id in board.routes) shouldBe false
            board.routes.getValue(publicRoad.id) shouldBe publicRoute
            val native = CivsRoadWorld(paper.createSimplePlugin("WallCleanupTest"), { listOf(city) }, { _, _, _ -> true }, { false })
            val selected = targets.first { it.floor.z == 5 }
            shouldThrowAny { selected.validated(city.copy(buildings = city.buildings + publicRoad.copy(id = UUID(293, 10)))) }
            world.getBlockAt(0, 64, 5).type = Material.STONE_BRICKS
            native.captureRoadUpkeep(city, worker, selected) shouldBe null
            world.getBlockAt(0, 64, 5).type = Material.COARSE_DIRT
            val captured = native.captureRoadUpkeep(city, worker, selected)!!
            val work = captured.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
            val after = CivsRoadMaintenance.complete(city, work, 3_000)
            val receipt = CivRoadUpkeepReceipt(worker, city.stock, work, 3_000)
            val tx = CivInventoryTransaction(UUID(293, 11), city.id, city.revision, after, emptyList(),
                worldChanges = work.changes, upkeep = receipt).validated()
            Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated() shouldBe tx
            receipt.before(after) shouldBe city
            work.changes.single().let { c ->
                c.position shouldBe CivPosition(0, 64, 5)
                world.getBlockAt(0, 64, 5).setBlockData(Bukkit.createBlockData(c.after), false)
            }
            native.validateRoadUpkeep(city, work)
            native.validateRoadUpkeep(after, work)
            city = after
            world.getBlockAt(0, 64, 5).type shouldBe Material.GRASS_BLOCK
            for (z in listOf(10, 16, 20, 30)) world.getBlockAt(0, 64, z).type shouldBe Material.COARSE_DIRT
            CivAutonomousAccess.from(city, captures).routes.getValue(wall.id) shouldBe route
            city.workers.single().completedCycles shouldBe 1
            CivsEquipment.usableCount(city.workers.single(), "COBBLESTONE") shouldBe 63
        }
    }

    test("current Auto4 cleanup uses exact retained authored floor cells and exports a bounded native acceptance list") {
        val input = System.getenv("CIVS_WALL_CLEANUP_INPUT") ?: return@test
        val geometry = System.getenv("CIVS_WALL_CLEANUP_GEOMETRY") ?: error("Missing building geometry")
        val pavement = System.getenv("CIVS_WALL_CLEANUP_PAVEMENT") ?: error("Missing exact authored floor cells")
        val gson = Gson()
        val captured = JsonParser.parseString(Files.readString(Path.of(input))).asJsonObject
        val current = JsonParser.parseString(Files.readString(Path.of(geometry))).asJsonObject
        val walls = JsonParser.parseString(Files.readString(Path.of(pavement))).asJsonObject.getAsJsonArray("walls")
            .associate { row -> row.asJsonObject.let { it["building"].asString to it } }
        val buildings = current.getAsJsonArray("buildings").map { gson.fromJson(it, CivBuilding::class.java) }
        val adopted = current["plan"]?.takeUnless { it.isJsonNull }?.let { gson.fromJson(it, CivSettlementPlan::class.java) }
        val city = Settlement(UUID.fromString(current["id"].asString), UUID(0, 1), "Auto4 captured roads",
            UUID.fromString(current["worldId"].asString), gson.fromJson(current["center"], CivPosition::class.java),
            current["radius"].asInt, current["style"].asString, Stock(), buildings, emptyList(), autonomy = CivAutonomy(plan = adopted))
        captured["city"].asString shouldBe city.id.toString()
        val plans = captured.getAsJsonArray("plans").mapNotNull { raw ->
            val row = raw.asJsonObject
            if (row["identity"]?.asBoolean != true) return@mapNotNull null
            val b = city.building(UUID.fromString(row["building"].asString)) ?: return@mapNotNull null
            val route = row.getAsJsonArray("route").map { point -> point.asJsonArray.let { CivPosition(it[0].asInt, it[1].asInt, it[2].asInt) } }
            val cells = walls[b.id.toString()]?.getAsJsonArray("pavement")?.map { cell -> cell.asJsonArray.let {
                CivPlanBlock(CivPosition(it[0].asInt, it[1].asInt, it[2].asInt), it[3].asString, it[4].asString)
            } }.orEmpty()
            CivPlacementPlan(UUID.fromString(row["id"].asString), city.id, city.worldId, b, emptyList(),
                terrain = CivTerraformPlan(b.bounds, cells, route, b.anchor.y, 0, 0))
        }
        val access = CivAutonomousAccess.from(city, plans)
        val targets = CivsWallApproachCleanup.targets(city, access)
        (targets.size > 0) shouldBe true
        targets.forEach { it.validated(city) }
        println("auto4 cleanup_columns=${targets.size} walls=${targets.map { it.source.id }.distinct().size}")
        System.getenv("CIVS_WALL_CLEANUP_OUT")?.let { output ->
            val terraces = CivsRoadMaintenance.prepare(city, access).targets.filter { it.routeOverride != null }.take(1)
            Files.writeString(Path.of(output), gson.toJson(mapOf("city" to city.id, "source_revision" to captured["revision"].asLong,
                "cleanup" to targets.map { mapOf("source" to it.source.id, "floor" to it.floor,
                    "before" to it.wallCleanup!!.authored.after, "after" to it.after) },
                "terrace" to terraces)))
        }
    }
})
