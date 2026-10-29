package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineUpgradedEntranceTest : FunSpec({
    test("a paid lower-level vestibule remains an air opening after its independent level upgrade") {
        upgradedEntranceFixture { f ->
            f.plan.bottom shouldBe CivPosition(-3, -5, -10)
            f.local shouldBe CivPosition(-4, -6, -12)
            f.point shouldBe CivPosition(-1840, 64, -2684)
            val proof = f.state.levels!!.single()
            val old = requireNotNull(CivsMinePlan.forBuilding(proof.building, 5))
            old.expectedAt(f.local, proof.v5Proof!!.nextStep, proof.v5Proof!!.sectionSteps.orEmpty(), 180) shouldBe null
            CivsMineProgress.v6State(f.state) shouldBe null
            CivsMineProgress.priorStanding(f.mine, f.state, f.point.copy(y = f.point.y - 1)) shouldBe true
            CivsMineProgress.validLevels(f.mine, f.state) shouldBe true
            f.state.nextStep shouldBe 0L
            f.state.sectionSteps shouldBe emptyMap()

            val opening = f.opening(f.city)
            opening.material shouldBe null
            f.owner.mineProtectionNeeded(f.block(), opening.material) shouldBe false
            // This old AIR passage is observed, not filled with a newly paid inner wall.
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.seal()) }
                .reason shouldBe "stale-worksite"
            f.block().type shouldBe Material.AIR
            f.city.minePlans!!.getValue(f.mine.id.toString()) shouldBe f.state
            f.world.getBlockAt(f.point.x, 65, f.point.z).type shouldBe Material.DIRT
            f.world.getBlockAt(f.point.x, 70, f.point.z).type shouldBe Material.COBBLESTONE_STAIRS
        }
    }

    test("an unrelated or unproved vestibule keeps its real terrain cover requirement") {
        upgradedEntranceFixture { f ->
            val witness = f.state.levels!!.single()
            val altered = witness.copy(nextStep = 0, sectionSteps = emptyMap(), v5Step = null, v5Proof = null)
            val unrelated = witness.copy(building = witness.building.copy(anchor = witness.building.anchor.copy(x = -1908)))
            for (state in listOf(f.state.copy(levels = null), f.state.copy(levels = listOf(altered)),
                f.state.copy(levels = listOf(unrelated)))) {
                val city = f.city.copy(minePlans = mapOf(f.mine.id.toString() to state))
                CivsMineProgress.priorStanding(f.mine, state, f.point.copy(y = f.point.y - 1)) shouldBe false
                f.opening(city).material shouldBe Material.COBBLESTONE
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(city, f.seal()) }
                    .reason shouldBe "mine-thin-cover"
                f.block().type shouldBe Material.AIR
            }
        }
    }

    test("a retained old entrance still detects native liquid and refuses foreign or protected new writes") {
        upgradedEntranceFixture { f ->
            for (liquid in listOf(Material.WATER, Material.LAVA)) {
                f.block().type = liquid
                val opening = f.opening(f.city)
                opening.material shouldBe null
                f.owner.mineProtectionNeeded(f.block(), opening.material) shouldBe true
                f.block().type shouldBe liquid
            }
            f.block().type = Material.AIR
            val support = CivsWorksiteLayouts.point(f.mine, f.around)
            val native = f.world.getBlockAt(support.x, support.y, support.z)
            fun supportWork() = f.seal().copy(status = "digging-mine", miningFrontier = null,
                changes = listOf(CivPlanBlock(support, native.blockData.asString, "minecraft:cobblestone")))
            for (foreign in listOf(Material.STONE_BRICKS, Material.WATER, Material.LAVA)) {
                native.type = foreign
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, supportWork()) }
                    .reason shouldBe "mine-foreign-block"
                native.type shouldBe foreign
            }
            native.type = Material.AIR
            val lands = f.paper.createSimplePlugin("Lands")
            f.paper.server.pluginManager.disablePlugin(lands)
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, supportWork()) }
            native.type shouldBe Material.AIR
            f.city.minePlans!!.getValue(f.mine.id.toString()).nextStep shouldBe 0L
        }
    }
})

private class UpgradedEntranceFixture(val paper: MockBukkitTestRuntime, val world: CivsWorksiteWorldFixture,
    val runtime: CivsWorksites, val mine: CivBuilding, val state: CivMinePlanState, val city: Settlement) {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val local = CivPosition(-4, -6, -12)
    val point = CivsWorksiteLayouts.point(mine, local)
    val around = plan.steps[plan.sections.getValue("spine").first()].point
    val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }.get(runtime) as CivsMineWorksite
    fun block() = world.getBlockAt(point.x, point.y, point.z)
    fun opening(city: Settlement) = requireNotNull(owner.mineFrontier(city, mine, world, plan, around,
        emptySet(), accessStep = 0)).single { it.block == local }
    fun seal() = CivWorldWork(city.workers.single().id, 0, mine.id, mine.entrance,
        listOf(CivPlanBlock(point, "minecraft:air", "minecraft:cobblestone")), inputs = mapOf("COBBLESTONE" to 1L),
        status = "securing-mine", miningPlanVersion = 7, miningStep = 0, miningAccessStep = 0,
        miningSections = emptyMap(), miningAccessSections = emptyMap(),
        miningOrdinal = plan.sections.getValue("spine").first(), miningFrontier = around)
}

/** Observed Caledonia geometry and paid V5 entrance prefix, without private resident or terrain data. */
private fun upgradedEntranceFixture(check: (UpgradedEntranceFixture) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("upgraded-paid-entrance").also(paper.server::addWorld)
        val plugin: Plugin = paper.createSimplePlugin("MineUpgradedEntrance")
        val previous = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
            .copy(entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
        val mine = previous.copy(level = 2, schematicId = previous.schematicId.dropLast(1) + "2")
        // The real old-level witness has these paid prefixes; deeper gallery history is irrelevant to this entrance.
        val old = CivMinePlanState(7, 5, sectionSteps = mapOf("spine" to 60L), v5Step = 1818,
            v5Proof = CivMineV5Proof(1818, sectionSteps = emptyMap()))
        val state = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null))
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        val city = spatialCity(world.uid, listOf(mine), worker).copy(center = mine.anchor,
            minePlans = mapOf(mine.id.toString() to state))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val centerChunkX = mine.anchor.x shr 4
        val centerChunkZ = mine.anchor.z shr 4
        for (x in centerChunkX - 2..centerChunkX + 2) for (z in centerChunkZ - 2..centerChunkZ + 2) world.loadChunk(x, z)
        fun set(local: CivPosition, data: String) {
            val p = CivsWorksiteLayouts.point(mine, local)
            world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(data)
        }
        for (support in requireNotNull(plan.entranceLanding).cells) {
            set(support, "minecraft:cobblestone")
            for (height in 1..2) set(support.copy(y = support.y + height), "minecraft:air")
        }
        for (y in plan.transferFeetY..plan.bottom.y + 3) {
            plan.shaftColumns.forEach { (x, z) -> set(CivPosition(x, y, z), "minecraft:air") }
            set(plan.bottom.copy(y = y), "minecraft:ladder[facing=west,waterlogged=false]")
            set(plan.backing.copy(y = y), "minecraft:cobblestone")
        }
        val point = CivsWorksiteLayouts.point(mine, CivPosition(-4, -6, -12))
        world.getBlockAt(point.x, 65, point.z).type = Material.DIRT
        world.getBlockAt(point.x, 66, point.z).type = Material.COBBLESTONE
        world.getBlockAt(point.x, 70, point.z).blockData = Bukkit.createBlockData(
            "minecraft:cobblestone_stairs[facing=south,half=top,shape=straight,waterlogged=false]")
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                check(UpgradedEntranceFixture(paper, world, runtime, mine, state, city))
            }
        }
    }
}
