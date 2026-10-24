package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.bukkit.Material

class CivsMaterialsTest : FunSpec({
    lateinit var paper: ru.arc.paper.testing.MockBukkitTestRuntime
    beforeEach { paper = ru.arc.paper.testing.MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("workshop recipes consume vanilla ingredients and level only adds workplaces") {
        fun recipe(type: BuildingType, material: String, inputs: Map<String, Long>, amount: Long) {
            for (level in 1..3) {
                val building = CivBuilding(java.util.UUID.randomUUID(), type, level, CivPosition(0, 64, 0), 0, "test",
                    CivBox(CivPosition(0, 64, 0), CivPosition(2, 68, 2)), CivPosition(0, 64, -1), BuildingPhase.ACTIVE,
                    production = material)
                (material in CivsProduction.options(type)) shouldBe true
                CivsProduction.inputs(building) shouldBe inputs
                CivsProduction.amount(building) shouldBe amount
            }
        }
        recipe(BuildingType.BAKERY, "BREAD", mapOf("WHEAT" to 3L), 1L)
        for (wood in listOf("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK"))
            recipe(BuildingType.SAWMILL, "${wood}_PLANKS", mapOf("${wood}_LOG" to 1L), 4)
        for ((tool, iron) in mapOf("SHOVEL" to 1L, "AXE" to 3L, "PICKAXE" to 3L, "HOE" to 2L))
            recipe(BuildingType.BLACKSMITH, "IRON_$tool", mapOf("IRON_INGOT" to iron, "STICK" to 2L), 1)
        recipe(BuildingType.STONEMASON, "STONE_BRICKS", mapOf("STONE" to 4L), 4)
        recipe(BuildingType.LIBRARY, "WRITABLE_BOOK", mapOf("BOOK" to 1L, "FEATHER" to 1L, "INK_SAC" to 1L), 1)
        recipe(BuildingType.TOWN_HALL, "BOOK", mapOf("PAPER" to 3L, "LEATHER" to 1L), 1)
    }

    test("real building materials account for paired blocks and variants") {
        CivsMaterials.requirements("minecraft:spruce_door[half=lower]") shouldBe mapOf("SPRUCE_DOOR" to 1L)
        CivsMaterials.requirements("minecraft:spruce_door[half=upper]") shouldBe emptyMap()
        CivsMaterials.requirements("minecraft:red_bed[part=head]") shouldBe emptyMap()
        CivsMaterials.requirements("minecraft:spruce_slab[type=double]") shouldBe mapOf("SPRUCE_SLAB" to 2L)
        CivsMaterials.requirements("minecraft:wall_torch[facing=north]") shouldBe mapOf("TORCH" to 1L)
        CivsMaterials.requirements("minecraft:potted_poppy") shouldBe mapOf("FLOWER_POT" to 1L, "POPPY" to 1L)
        CivsMaterials.requirements("minecraft:wheat[age=7]") shouldBe mapOf("WHEAT_SEEDS" to 1L)
        CivsMaterials.requirements("minecraft:oak_stairs[waterlogged=true]") shouldBe mapOf("OAK_STAIRS" to 1L)
        CivsMaterials.requirements("minecraft:structure_void") shouldBe emptyMap()
        CivsMaterials.requirements("minecraft:fire[age=15,east=false,north=false,south=false,up=false,west=false]") shouldBe
            mapOf("FIRE_CHARGE" to 1L)
        shouldThrow<CivsRejection> { CivsMaterials.requirements("minecraft:nether_portal") }.reason shouldBe "unsupported-material"
    }

    test("water is free without changing saved construction row boundaries or other liquids") {
        CivsMaterials.requirements("minecraft:water[level=0]") shouldBe emptyMap()
        CivsMaterials.requirements("minecraft:water_cauldron[level=3]") shouldBe mapOf("CAULDRON" to 1L)
        CivsMaterials.requirements("minecraft:lava[level=0]") shouldBe mapOf("LAVA_BUCKET" to 1L)
        CivsMaterials.requirements("minecraft:lava_cauldron") shouldBe mapOf("CAULDRON" to 1L, "LAVA_BUCKET" to 1L)
        CivsMaterials.requirements("minecraft:powder_snow_cauldron") shouldBe mapOf("CAULDRON" to 1L, "POWDER_SNOW_BUCKET" to 1L)
        CivsMaterials.unpaid(mapOf("WATER_BUCKET" to 8L, "OAK_STAIRS" to 2L)) shouldBe mapOf("OAK_STAIRS" to 2L)
        val building = CivBuilding(java.util.UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(0, 64, 0), 0, "test",
            CivBox(CivPosition(0, 64, 0), CivPosition(3, 64, 0)), CivPosition(0, 64, 1), BuildingPhase.CONSTRUCTING)
        val plan = CivPlacementPlan(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), building,
            listOf("minecraft:water[level=0]", "minecraft:air", "minecraft:oak_stairs[waterlogged=true]", "minecraft:oak_stairs[waterlogged=false]")
                .mapIndexed { index, after -> CivPlanBlock(CivPosition(index, 64, 0), "minecraft:stone", after) })
        civsMaterialBatches(plan) shouldBe (0..3).map { index ->
            CivBuildBatch(CivPosition(index, 64, 0), CivPosition(index, 64, 0))
        }
        CivsMaterials.required(plan) shouldBe mapOf("OAK_STAIRS" to 2L)
    }

    test("curated vanilla palettes have obtainable deterministic material requirements") {
        val states = javaClass.getResourceAsStream("/blueprint-material-states.txt")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith('#') }
        (states.size > 100) shouldBe true
        for (state in states) {
            val costs = try { CivsMaterials.requirements(state) } catch (e: CivsRejection) { error("Cannot price $state: ${e.values}") }
            costs.all { (name, count) -> Material.getMaterial(name)?.isItem == true && count in 1..8 } shouldBe true
        }
    }

    test("construction tiles cover every voxel exactly once across negative coordinates") {
        val box = CivBox(CivPosition(-19, -4, -7), CivPosition(11, 3, 28))
        val batches = civsBuildBatches(box)
        val seen = hashSetOf<CivPosition>()
        for (batch in batches) {
            batch.minimum.y shouldBe batch.maximum.y
            ((batch.maximum.x - batch.minimum.x + 1) * (batch.maximum.z - batch.minimum.z + 1) <= 256) shouldBe true
            for (x in batch.minimum.x..batch.maximum.x) for (z in batch.minimum.z..batch.maximum.z)
                seen.add(CivPosition(x, batch.minimum.y, z)) shouldBe true
        }
        seen.size shouldBe 31 * 8 * 36
        seen.all { box.contains(it.x, it.y, it.z) } shouldBe true
        batches.map { it.minimum.y }.sorted() shouldBe batches.map { it.minimum.y }
    }

    test("incremental rows are deterministic homogeneous and require at most sixteen adjacent block costs") {
        val building = CivBuilding(java.util.UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 0, 0), 0, "test",
            CivBox(CivPosition(-20, 64, 0), CivPosition(20, 65, 1)), CivPosition(0, 64, 2), BuildingPhase.CONSTRUCTING)
        val cells = (-20..20).map { x -> CivPlanBlock(CivPosition(x, 64, 0), "minecraft:air",
            if (x < 0) "minecraft:stone" else "minecraft:oak_planks") } +
            CivPlanBlock(CivPosition(0, 65, 0), "minecraft:air", "minecraft:oak_slab[type=double,waterlogged=false]") +
            CivPlanBlock(CivPosition(2, 65, 0), "minecraft:stone", "minecraft:stone")
        val plan = CivPlacementPlan(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), building, cells)
        val batches = civsMaterialBatches(plan)
        civsMaterialBatches(plan.copy(blocks = cells.reversed())) shouldBe batches
        val included = batches.flatMap { batch ->
            batch.minimum.y shouldBe batch.maximum.y
            batch.minimum.z shouldBe batch.maximum.z
            (batch.maximum.x - batch.minimum.x + 1 <= 16) shouldBe true
            val row = cells.filter { it.position.y == batch.minimum.y && it.position.z == batch.minimum.z && it.position.x in batch.minimum.x..batch.maximum.x }
            row.map { CivsMaterials.batchKey(it.after) }.distinct().size shouldBe 1
            row.map { it.position }
        }
        included.distinct().size shouldBe included.size
        included.toSet() shouldBe cells.filter { it.before != it.after }.map { it.position }.toSet()
        batches.flatMap { batch -> cells.filter { it.position.y == batch.minimum.y && it.position.z == batch.minimum.z && it.position.x in batch.minimum.x..batch.maximum.x } }
            .let { CivsMaterials.required(plan.copy(blocks = it)) } shouldBe CivsMaterials.required(plan)
    }

    test("manual foundation batches have no invented worker route and preserve the authored price") {
        val building = CivBuilding(java.util.UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(0, 70, 0), 0, "test",
            CivBox(CivPosition(0, 70, 0), CivPosition(0, 72, 0)), CivPosition(0, 71, -1), BuildingPhase.CONSTRUCTING)
        val foundation = (0..19).map { i -> CivPlanBlock(CivPosition(0, 50 + i, 0), "minecraft:air", "minecraft:dirt") }
        val plan = CivPlacementPlan(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), building,
            listOf(CivPlanBlock(CivPosition(0, 70, 0), "minecraft:air", "minecraft:stone")),
            terrain = CivTerraformPlan(CivBox(foundation.first().position, foundation.last().position), foundation, emptyList(), 69, 0, 20))
        val batches = civsTerraformBatches(plan)
        batches.size shouldBe 2
        batches.map { it.terrainCount } shouldBe listOf(16, 4)
        batches.all { it.workplace == null } shouldBe true
        batches.flatMap { civsBatchBlocks(plan, it) } shouldBe foundation
        CivsMaterials.required(plan) shouldBe mapOf("STONE" to 1L)
    }

    test("terraform prefix preserves cut fill ordering and resumes before the same authored building rows") {
        val building = CivBuilding(java.util.UUID.randomUUID(), BuildingType.BUILDER_HUT, 1,
            CivPosition(12, 64, 0), 0, "test", CivBox(CivPosition(12, 63, 0), CivPosition(15, 68, 2)),
            CivPosition(12, 64, -1), BuildingPhase.CONSTRUCTING)
        val terrainCells = listOf(
            CivPlanBlock(CivPosition(5, 66, 0), "minecraft:dirt", "minecraft:air"),
            CivPlanBlock(CivPosition(5, 65, 0), "minecraft:dirt", "minecraft:air"),
            CivPlanBlock(CivPosition(6, 62, 0), "minecraft:air", "minecraft:cobblestone"),
            CivPlanBlock(CivPosition(6, 63, 0), "minecraft:air", "minecraft:cobblestone"))
        val route = (0..12).map { CivPosition(it, 64, 0) }
        val plan = CivPlacementPlan(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            building, listOf(CivPlanBlock(CivPosition(12, 64, 0), "minecraft:air", "minecraft:oak_planks")),
            terrain = CivTerraformPlan(CivBox(CivPosition(5, 62, 0), CivPosition(6, 66, 0)), terrainCells, route, 63, 2, 2))
        val prefix = civsTerraformBatches(plan)
        prefix.flatMap { civsBatchBlocks(plan, it) } shouldBe terrainCells
        prefix.map { it.workplace } shouldBe listOf(route[2], route[3])
        val order = CivConstruction(plan.id, building.id, 1, prefix.size + 1, 1, ConstructionPhase.PLACING, Stock(),
            plannedBuilding = building, batches = CivConstructionBatches(prefix.size + 1, 16, true))
        val batches = civsConstructionBatches(plan, order)
        batches.take(prefix.size) shouldBe prefix
        civsBatchBlocks(plan, batches[order.workDone]) shouldBe terrainCells.drop(2)
        batches.last().terrainStart shouldBe null
        batches.last().workplace shouldBe route.last()
        civsBatchBlocks(plan, batches.last()) shouldBe plan.blocks
        // The village pays its existing abstract building price once, not an invented item bill for earthworks.
        CivsMaterials.required(plan) shouldBe mapOf("OAK_PLANKS" to 1L)
        civsConstructionBatches(plan.copy(terrain = null), order).all { it.terrainStart == null } shouldBe true
    }
})
