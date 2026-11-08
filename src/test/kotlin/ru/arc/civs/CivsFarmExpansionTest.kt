package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsFarmExpansionTest : FunSpec({
    beforeEach { installExpandedFarmCatalog() }
    afterEach { CivsWorksiteLayouts.installCatalog(emptyMap()) }

    test("the retained Medieval Oak expansion follows its authored offset in world coordinates") {
        val anchor = CivPosition(12307, 88, 8402)
        val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 2, anchor, 90,
            "civs/v2/medievaloak/agriculture-horticulture-altfarmer/b2847c45d9139927e715/level2",
            CivBox(CivPosition(12308, 86, 8395), CivPosition(12328, 103, 8411)),
            CivPosition(12307, 89, 8402), BuildingPhase.ACTIVE)
        val originalWater = CivPosition(16, 0, -11)
        fun jsonPoint(point: CivPosition) = JsonArray().apply { add(point.x); add(point.y); add(point.z) }
        val soil = (12..20).flatMap { x -> (-15..-7).map { z -> CivPosition(x, 0, z) } } - originalWater
        val metadata = JsonObject().apply {
            add("soilCells", JsonArray().apply { soil.forEach { add(jsonPoint(it)) } })
            add("irrigationCells", JsonArray().apply { add(jsonPoint(originalWater)) })
        }
        CivsWorksiteLayouts.installCatalog(mapOf(farm.schematicId to JsonObject().apply { add("farm", metadata) }))
        CivsFarmFields.originalMetadata(farm) shouldBe true
        CivsWorksiteLayouts.field(farm).size shouldBe 80
        CivsWorksiteLayouts.irrigation(farm) shouldBe listOf(CivPosition(12318, 88, 8418))
        CivsWorksiteLayouts.isField(farm, 12325, 8422) shouldBe false

        val expanded = farm.copy(farmFieldVersion = CivsFarmFields.VERSION)
        CivsWorksiteLayouts.field(expanded).size shouldBe 120
        CivsWorksiteLayouts.irrigation(expanded).toSet() shouldBe (12311..12325).map { CivPosition(it, 88, 8418) }.toSet()
        CivsFarmFields.added(expanded).size shouldBe 54
        CivsFarmFields.draining(expanded).size shouldBe 8
        CivsFarmFields.rim(expanded).size shouldBe 52
        CivsFarmFields.rim(expanded).contains(CivPosition(12326, 88, 8423)) shouldBe true
        CivsWorksiteLayouts.isField(expanded, 12325, 8422) shouldBe true
        CivsWorksiteLayouts.isField(expanded, 12326, 8422) shouldBe false
    }

    test("retained profile changes only field intent and protects its soil water and draining crops in every rotation") {
        MockBukkitTestRuntime.open().use { paper ->
            for (rotation in listOf(0, 90, 180, 270)) {
                val f = farmExpansionFixture(paper, rotation = rotation)
                val before = f.city
                val native = (CivsFarmFields.soil(f.farm) + CivsFarmFields.water(f.farm)).associateWith {
                    f.world.getBlockAt(it.x, it.y, it.z).blockData.asString
                }
                CivsWorksiteLayouts.field(f.farm).size shouldBe 80
                CivsWorksiteLayouts.irrigation(f.farm).size shouldBe 1
                CivsFarmFields.originalMetadata(f.farm) shouldBe true
                val expanded = CivsRules.expandFarmField(before, f.farm.id)
                val farm = expanded.building(f.farm.id)!!
                farm shouldBe f.farm.copy(farmFieldVersion = 1)
                expanded.workers shouldBe before.workers
                expanded.inventory shouldBe before.inventory
                expanded.stock shouldBe before.stock
                expanded.construction shouldBe null
                expanded.revision shouldBe before.revision + 1
                (civsPlanBoardLayoutKey(expanded) != civsPlanBoardLayoutKey(before)) shouldBe true
                civsSameBuildingLayout(before.buildings, expanded.buildings) shouldBe false
                CivsWorksiteLayouts.field(farm).size shouldBe 120
                CivsWorksiteLayouts.irrigation(farm).size shouldBe 15
                CivsFarmFields.added(farm).size shouldBe 54
                CivsFarmFields.draining(farm).size shouldBe 8
                val protection = CivsProtection.prepare(listOf(expanded))
                for (point in CivsWorksiteLayouts.field(farm) + CivsWorksiteLayouts.irrigation(farm)) {
                    CivsWorksiteLayouts.isField(farm, point.x, point.z) shouldBe true
                    protection.geometry.any { it.bounds.contains(point.x, point.y, point.z) } shouldBe true
                    protection.cropCells.contains(farmWorldCrop(f.world.uid, point)) shouldBe true
                    worksiteProtectedByBuilding(farm.copy(type = BuildingType.FORESTER, farmFieldVersion = null), farm, point) shouldBe true
                    f.world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe native.getValue(point)
                }
                CivsLogistics.workerWorkReserve(expanded, expanded.workers.single())["WHEAT_SEEDS"] shouldBe 120L
                shouldThrow<CivsRejection> { CivsRules.expandFarmField(expanded, farm.id) }
            }
        }
    }

    test("legacy missing profile and expanded construction state survive the real store without changing original plans") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = farmExpansionFixture(paper)
            val root = Files.createTempDirectory("civs-farm-profile-")
            try {
                val executor = Executor(Runnable::run)
                CivsStore(root, executor).use { it.save(f.city).join() }
                val file = root.resolve("cities/${f.city.id}.json")
                val legacy = JsonParser.parseString(Files.readString(file)).asJsonObject
                legacy.getAsJsonObject("city").getAsJsonArray("buildings").forEach { it.asJsonObject.remove("farmFieldVersion") }
                Files.writeString(file, Gson().toJson(legacy))
                val expanded = CivsRules.expandFarmField(f.city, f.farm.id)
                CivsStore(root, executor).use { store ->
                    store.load().join().single() shouldBe f.city
                    store.save(expanded).join() shouldBe expanded
                }
                val current = expanded.building(f.farm.id)!!
                val target = current.copy(level = 2, schematicId = FARM_SOURCE + "2", phase = BuildingPhase.CONSTRUCTING)
                val order = CivConstruction(UUID.randomUUID(), current.id, 2, 1, 0, ConstructionPhase.PLACING, Stock(),
                    plannedBuilding = target, batches = CivConstructionBatches(1, 1), materials = emptyMap(),
                    payment = CivConstructionPayment(reservedBatch = 0))
                val placing = CivsRules.validate(expanded.copy(construction = order))
                shouldThrow<CivsRejection> { CivsRules.validate(placing.copy(construction = order.copy(
                    plannedBuilding = target.copy(farmFieldVersion = null)))) }
                val upgraded = CivsRules.completeBatch(placing, placing.owner, order.id)
                upgraded.building(current.id) shouldBe target.copy(phase = BuildingPhase.ACTIVE)
                CivsStore(root, executor).use { store ->
                    store.load().join().single() shouldBe expanded
                    store.save(upgraded).join()
                }
                CivsStore(root, executor).use { store ->
                    val loaded = store.load().join().single()
                    loaded shouldBe upgraded
                    CivsWorksiteLayouts.field(loaded.building(current.id)!!).size shouldBe 120
                }
                for (bad in listOf(current.copy(farmFieldVersion = 2), current.copy(type = BuildingType.HOUSE),
                    current.copy(schematicId = current.schematicId.replace("nordic", "other")))) {
                    shouldThrow<CivsRejection> { CivsRules.validate(expanded.copy(buildings = expanded.buildings.map {
                        if (it.id == bad.id) bad else it
                    })) }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    test("native admission refuses changed banks foreign crops TileState permissions unloaded chunks and reserved routes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val f = farmExpansionFixture(paper)
            fun admit(access: CivAutonomousAccess = CivAutonomousAccess(), allowed: Boolean = true) =
                CivsFarmExpansion.validate(f.city, f.farm, access, f.world, listOf(f.city)) { _, _ -> allowed }
            admit()
            val new = CivsFarmFields.added(f.farm).first()
            val top = new.copy(y = new.y + 1)
            fun set(point: CivPosition, material: Material) { f.world.getBlockAt(point.x, point.y, point.z).type = material }
            set(top, Material.CARROTS)
            shouldThrow<CivsRejection> { admit() }
            set(top, Material.WILDFLOWERS)
            shouldThrow<CivsRejection> { admit() }.values["stage"] shouldBe "new-column"
            set(top, Material.AIR)
            set(top, Material.CHEST)
            shouldThrow<CivsRejection> { admit() }
            set(top, Material.AIR)
            shouldThrow<CivsRejection> { admit(allowed = false) }
            val below = new.copy(y = new.y - 1)
            set(below, Material.AIR)
            shouldThrow<CivsRejection> { admit() }
            set(below, Material.DIRT)
            val bank = CivsWorksiteLayouts.point(f.farm, CivPosition(15, 0, -22))
            set(bank, Material.AIR)
            shouldThrow<CivsRejection> { admit() }
            set(bank, Material.DIRT)
            shouldThrow<CivsRejection> { admit(CivAutonomousAccess(routes = mapOf(f.farm.id to listOf(new.copy(y = new.y + 1))))) }
            shouldThrow<CivsRejection> { admit(CivAutonomousAccess(reservedFortificationPorts = listOf(CivBox(new, new)))) }
            f.world.unloadChunk(new.x shr 4, new.z shr 4)
            shouldThrow<CivsRejection> { admit() }
            f.world.loadChunk(new.x shr 4, new.z shr 4)
            admit()
            f.city.workers shouldBe listOf(f.worker)
            f.farm.farmFieldVersion shouldBe null
        }
    }

    test("two flat beds may use the natural stepped rim seen at Auto5") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val f = farmExpansionFixture(paper, rotation = 270)
            fun set(offset: CivPosition, material: Material) {
                val p = CivsWorksiteLayouts.point(f.farm, offset)
                f.world.getBlockAt(p.x, p.y, p.z).type = material
            }
            fun admit() = CivsFarmExpansion.validate(f.city, f.farm, CivAutonomousAccess(), f.world, listOf(f.city)) { _, _ -> true }
            for (z in -16..-6) {
                set(CivPosition(20, 1, z), Material.DIRT)
                if (z >= -8) set(CivPosition(20, 2, z), Material.GRASS_BLOCK)
            }
            val crest = CivPosition(20, 3, -6)
            val flowers = CivPosition(20, 3, -8)
            set(crest, Material.GRASS_BLOCK)
            set(flowers, Material.WILDFLOWERS)
            // The crest has a supported approach along the rim despite its three-block direct face.
            admit()
            f.farm.farmFieldVersion shouldBe null
            set(flowers, Material.STONE)
            shouldThrow<CivsRejection> { admit() }.values["stage"] shouldBe "perimeter-access"
            set(flowers, Material.WILDFLOWERS)
            set(CivPosition(20, 2, -7), Material.AIR)
            set(CivPosition(20, 1, -7), Material.AIR)
            shouldThrow<CivsRejection> { admit() }.values["stage"] shouldBe "perimeter-access"
        }
    }

    for (farmLevel in listOf(1, 2))
    test("a ready farm with its original site journal takes a turn before an optional wall refit at level $farmLevel") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val f = farmExpansionFixture(paper)
            val plugin = paper.createSimplePlugin("FarmDevelopment")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val world = mockk<CivsWorld> { every { canModify(any(), any(), any()) } returns true }
            val blueprints = mockk<CivsBlueprints> { every { maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel } }
            val refit = CompletableFuture<CivAutonomousDesign>()
            val placement = mockk<CivsAutonomousPlacement>(relaxed = true) {
                every { rebuildInfrastructure(any(), any(), any(), any()) } returns refit
            }
            val wallId = UUID.randomUUID()
            val at = CivPosition(-15, 63, 15)
            val wall = CivBuilding(wallId, BuildingType.WALL, 1, at, 0, "civs/infrastructure/v1/wall/$wallId",
                CivBox(at, at.copy(x = -11, y = 67)), at.copy(y = 64, z = 16), BuildingPhase.ACTIVE)
            val relocated = f.farm.copy(level = farmLevel, schematicId = FARM_SOURCE + farmLevel,
                control = f.farm.control!!.copy(x = f.farm.control!!.x + 1))
            f.world.getBlockAt(relocated.control!!.x, relocated.control!!.y, relocated.control!!.z).type = Material.BARREL
            val before = CivsRules.validate(f.city.copy(buildings = f.city.buildings.map {
                if (it.id == relocated.id) relocated else it
            } + wall))
            CivsInfrastructureRebuild.needsRefit(before, wall) shouldBe true
            var city = before
            var now = 1_000_000L
            var saves = 0
            val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, change ->
                try { city = change(city); saves++; CompletableFuture.completedFuture(city) }
                catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
            }
            val plan = CivPlacementPlan(f.farm.id, city.id, city.worldId, f.farm, emptyList())
            val wallPlan = CivPlacementPlan(wall.id, city.id, city.worldId, wall, emptyList())
            val plans = listOf(plan, wallPlan)
            val development = CivsAutonomousDevelopment(plugin, tasks, world, blueprints, placement, Executor(Runnable::run),
                { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
                { CompletableFuture.completedFuture(plans) }, { true }, { _, action -> action() }, mutation, mutation,
                { _, _ -> error("the wall design is still pending") }, {}, { now })
            try {
                development.pulseAccessRepair(); paper.performTicks(2)
                development.pulse(); paper.performTicks(2)
                verify(exactly = 0) { placement.rebuildInfrastructure(any(), any(), any(), any()) }
                saves shouldBe 1
                city shouldBe CivsRules.expandFarmField(before, f.farm.id)
                city.building(f.farm.id)!!.control shouldBe relocated.control
                city.workers shouldBe f.city.workers
                CivsWorksiteLayouts.points(f.farm, "farm", "irrigationCells").single().let {
                    f.world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.WATER
                }
                CivsFarmFields.added(f.farm).all { f.world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe true
                development.pulse(); paper.performTicks(2)
                saves shouldBe 1
                now += 5_001
                development.pulseAccessRepair(); paper.performTicks(2)
                development.pulse(); paper.performTicks(2)
                verify(exactly = 1) { placement.rebuildInfrastructure(city, wallPlan, plans, null) }
                city shouldBe CivsRules.expandFarmField(before, f.farm.id)
                saves shouldBe 1
            } finally { development.reset(); tasks.close() }
        }
    }

    test("a refused field yields to an accepted ordinary site on the next development pulse without retrying the field") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val f = farmExpansionFixture(paper)
            val site = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1, CivPosition(-15, 63, 15), 0,
                "fixture/pending-builder", CivBox(CivPosition(-17, 63, 13), CivPosition(-13, 70, 17)),
                CivPosition(-15, 64, 18), BuildingPhase.SITE)
            val before = CivsRules.placeSite(f.city, f.city.owner, site)
            var city = before
            var time = 1_000_000L
            var saves = 0
            var loads = 0
            val bank = CivsWorksiteLayouts.point(f.farm, CivPosition(15, 0, -22))
            f.world.getBlockAt(bank.x, bank.y, bank.z).type = Material.AIR
            val plugin = paper.createSimplePlugin("FarmDevelopmentRefusal")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val world = mockk<CivsWorld> { every { canModify(any(), any(), any()) } returns true }
            val blueprints = mockk<CivsBlueprints> { every { maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel } }
            val placement = mockk<CivsAutonomousPlacement>(relaxed = true)
            val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, change ->
                try { city = change(city); saves++; CompletableFuture.completedFuture(city) }
                catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
            }
            val plan = CivPlacementPlan(f.farm.id, city.id, city.worldId, f.farm, emptyList())
            val development = CivsAutonomousDevelopment(plugin, tasks, world, blueprints, placement, Executor(Runnable::run),
                { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
                { loads++; CompletableFuture.completedFuture(listOf(plan)) }, { true }, { _, action -> action() }, mutation, mutation,
                { _, _ -> error("an accepted site must use its ordinary construction queue") }, {}, { time })
            try {
                development.pulseAccessRepair(); paper.performTicks(2)
                development.accessReady(city) shouldBe true
                development.pulse(); paper.performTicks(2)
                city shouldBe before
                saves shouldBe 0
                loads shouldBe 2
                repeat(3) { development.pulse(); paper.performTicks(2) }
                loads shouldBe 2
                time += 5_001
                development.pulse(); paper.performTicks(2)
                city shouldBe CivsRules.enqueue(before, before.owner, site.id)
                city.constructionQueue shouldBe listOf(site.id)
                city.building(f.farm.id)!!.farmFieldVersion shouldBe null
                saves shouldBe 1
                loads shouldBe 2
                f.world.getBlockAt(bank.x, bank.y, bank.z).type shouldBe Material.AIR
            } finally { development.reset(); tasks.close() }
        }
    }

})

class CivsFarmWaterReceiptTest : FunSpec({
    beforeEach { installExpandedFarmCatalog() }
    afterEach { CivsWorksiteLayouts.installCatalog(emptyMap()) }

    for (refusal in listOf("bank", "water-place"))
    test("a pending irrigation receipt rechecks native authority and recovers exactly once refusal=$refusal") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val f = farmExpansionFixture(paper)
            val city = CivsRules.expandFarmField(f.city, f.farm.id)
            val farm = city.building(f.farm.id)!!
            val point = CivsFarmFields.water(farm).first()
            val before = f.world.getBlockAt(point.x, point.y, point.z).blockData.asString
            val work = CivWorldWork(f.worker.id, 0, farm.id, point.copy(x = point.x - 1, y = point.y + 1),
                listOf(CivPlanBlock(point, before, "minecraft:water[level=0]")), durationMillis = 1_500, status = "working-field")
            val after = CivsRules.finishWorldWork(city, work, 10_000)
            val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                worldChanges = work.changes).validated()
            val plugin = paper.createSimplePlugin("FarmWaterReceipt")
            val bank = point.copy(x = point.x - 1)
            CivsStorage(plugin) { emptyArray() }.use { storage ->
                storage.lock(tx)
                storage.foodCanModify = { _, _ -> true }
                var permitWater = refusal != "water-place"
                storage.farmWaterCanModify = { _, _ -> permitWater }
                if (refusal == "bank") f.world.getBlockAt(bank.x, bank.y, bank.z).type = Material.AIR
                (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
                f.world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe before
                tx.after shouldBe after
                permitWater = true
                f.world.getBlockAt(bank.x, bank.y, bank.z).type = Material.DIRT
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                f.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.WATER
                // A later bank edit or permission revocation must not turn already-applied water into a replay.
                f.world.getBlockAt(bank.x, bank.y, bank.z).type = Material.AIR
                permitWater = false
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                tx.after.workers.single().completedCycles shouldBe 1L
                CivsEquipment.plainCount(tx.after.workers.single(), "WHEAT_SEEDS") shouldBe 48
            }
        }
    }
})

internal const val FARM_SOURCE = "civs/v2/nordic/agriculture-horticulture-farmer/c0ad265fc22f522f22f9/level"

internal fun installExpandedFarmCatalog() {
    val sites = (1..3).associate { level ->
        val legacy = CivBuilding(UUID.randomUUID(), BuildingType.FARM, level, CivPosition(0, 63, 0), 0,
            "civs/minecolonies/nordic/agriculture/horticulture/farmer$level", CivBox(CivPosition(0, 63, 0), CivPosition(0, 63, 0)),
            CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        val site = CivsWorksiteLayouts.site(legacy)!!.deepCopy()
        val farm = site.getAsJsonObject("farm")
        for (key in listOf("soilCells", "irrigationCells", "gateCells")) {
            val transformed = JsonArray()
            farm.getAsJsonArray(key).forEach { value ->
                val p = CivsWorksiteLayouts.vector(value)
                transformed.add(JsonArray().apply { add(p.x + 1); add(p.y); add(p.z - 1) })
            }
            farm.add(key, transformed)
        }
        val entry = CivsWorksiteLayouts.vector(farm["workEntry"])
        farm.add("workEntry", JsonArray().apply { add(entry.x + 1); add(entry.y); add(entry.z - 1) })
        FARM_SOURCE + level to site
    }
    CivsWorksiteLayouts.installCatalog(sites)
}

internal data class FarmExpansionFixture(val world: CivsWorksiteWorldFixture, val city: Settlement, val farm: CivBuilding, val worker: CivWorker)

internal fun farmExpansionFixture(paper: MockBukkitTestRuntime, rotation: Int = 0): FarmExpansionFixture {
    val world = CivsWorksiteWorldFixture("farm-expansion-$rotation", Material.DIRT).also(paper.server::addWorld)
    return farmExpansionFixture(world, rotation)
}

internal fun farmExpansionFixture(world: CivsWorksiteWorldFixture, rotation: Int = 0): FarmExpansionFixture {
    for (x in -3..4) for (z in -3..4) world.loadChunk(x, z)
    val blank = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(0, 63, 0), rotation, FARM_SOURCE + "1",
        CivBox(CivPosition(-8, 62, -26), CivPosition(8, 82, -1)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE,
        control = CivPosition(0, 64, -3))
    val corners = listOf(CivPosition(-8, -1, -26), CivPosition(8, 19, -1), CivPosition(-8, -1, -1), CivPosition(8, 19, -26))
        .map { CivsWorksiteLayouts.point(blank, it) }
    val farm = blank.copy(bounds = CivBox(CivPosition(corners.minOf { it.x }, 62, corners.minOf { it.z }),
        CivPosition(corners.maxOf { it.x }, 82, corners.maxOf { it.z })),
        entrance = CivsWorksiteLayouts.point(blank, CivPosition(0, 1, 0)),
        control = CivsWorksiteLayouts.point(blank, CivPosition(0, 1, -3)))
    val rawWorker = CivWorker(UUID.randomUUID(), "Farmer", farm.id, nextDeliveryAt = 1,
        inventory = mapOf("IRON_HOE" to 1L, "WHEAT_SEEDS" to 48L))
    val worker = CivsEquipment.replace(rawWorker, CivsEquipment.slots(rawWorker))
    val city = CivsRules.validate(spatialCity(world.uid, listOf(farm), worker).copy(
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
    for (soil in CivsWorksiteLayouts.field(farm)) {
        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
        world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=1]")
    }
    for (water in CivsWorksiteLayouts.irrigation(farm)) world.getBlockAt(water.x, water.y, water.z).type = Material.WATER
    city.buildings.mapNotNull { it.control }.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL }
    return FarmExpansionFixture(world, city, farm, worker)
}

private fun farmWorldCrop(world: UUID, point: CivPosition) = world to point.copy(y = point.y + 1)
