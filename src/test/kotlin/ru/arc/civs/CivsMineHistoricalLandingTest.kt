package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.lang.reflect.InvocationTargetException

class CivsMineHistoricalLandingTest : FunSpec({
    test("the captured Y49 stone cut uses its exact paid lower-level Y58 landing and preserves its item receipt") {
        historicalLandingFixture { f ->
            val witness = f.state.levels!!.single()
            val old = requireNotNull(CivsMineProgress.v5State(witness.state()))
            val oldPlan = requireNotNull(CivsMinePlan.forBuilding(witness.building, 5))
            val roof = f.point.copy(y = 58)
            oldPlan.expectedAt(CivsMinePlan.localPoint(witness.building, roof), old.nextStep,
                old.sectionSteps.orEmpty(), witness.building.rotation) shouldBe "minecraft:cobblestone"
            val work = f.cut()
            f.runtime.validateMineReceiptNative(f.city, work)
            f.block(f.point).type shouldBe Material.STONE
            f.block(roof).type shouldBe Material.COBBLESTONE
            work.outputs shouldBe mapOf("COBBLESTONE" to 1L)
            work.durationMillis shouldBe 2_000L
            val after = CivsRules.finishWorldWork(f.city, work, 10_000)
            CivsEquipment.plainCount(f.city.workers.single(), "COBBLESTONE") + 1 shouldBe
                CivsEquipment.plainCount(after.workers.single(), "COBBLESTONE")
            after.workers.single().completedCycles shouldBe f.city.workers.single().completedCycles + 1
            after.minePlans!!.getValue(f.mine.id.toString()).levels shouldBe f.state.levels
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 12_000) }
            f.block(f.point).type = Material.AIR
            f.runtime.validateMineReceiptNative(after, work)
        }
    }

    for ((material, outputs) in listOf(
        Material.SMOOTH_BASALT to mapOf("SMOOTH_BASALT" to 1L),
        Material.GLOW_LICHEN to emptyMap(),
    )) test("native $material excavation preserves its exact cut and pickaxe item receipt") {
        historicalLandingFixture { f ->
            if (material == Material.GLOW_LICHEN) f.block(f.point).blockData = Bukkit.createBlockData(
                "minecraft:glow_lichen[down=false,east=false,north=false,south=true,up=false,waterlogged=false,west=false]")
            else f.block(f.point).type = material
            val before = f.block(f.point).blockData.asString
            val work = f.cut()
            work.changes shouldBe listOf(CivPlanBlock(f.point, before, "minecraft:air"))
            work.inputs shouldBe emptyMap()
            work.outputs shouldBe outputs
            work.durationMillis shouldBe 2_000L
            f.runtime.validateMineReceiptNative(f.city, work)
            f.block(f.point).blockData.asString shouldBe before
            val worker = f.city.workers.single()
            val after = CivsRules.finishWorldWork(f.city, work, 10_000)
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
            CivsEquipment.slots(after.workers.single()).take(CivsEquipment.BAG) shouldBe
                CivsEquipment.slots(worker).take(CivsEquipment.BAG)
            CivsEquipment.plainCount(after.workers.single(), "SMOOTH_BASALT") shouldBe
                CivsEquipment.plainCount(worker, "SMOOTH_BASALT") + outputs.getOrDefault("SMOOTH_BASALT", 0L).toInt()
            CivsEquipment.plainCount(after.workers.single(), "GLOW_LICHEN") shouldBe
                CivsEquipment.plainCount(worker, "GLOW_LICHEN")
            after.minePlans!!.getValue(f.mine.id.toString()).levels shouldBe f.state.levels
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 12_000) }
            f.block(f.point).type = Material.AIR
            f.runtime.validateMineReceiptNative(after, work)
            CivsEquipment.plainCount(after.workers.single(), "SMOOTH_BASALT") shouldBe
                CivsEquipment.plainCount(worker, "SMOOTH_BASALT") + outputs.getOrDefault("SMOOTH_BASALT", 0L).toInt()
        }
    }

    test("smooth basalt provides real rock cover while glow lichen and unowned cobblestone do not") {
        historicalLandingFixture { f ->
            val roof = f.point.copy(y = 58)
            f.block(roof).type = Material.SMOOTH_BASALT
            f.runtime.validateMineReceiptNative(f.city, f.cut())
            CivsMineSafety.natural(Material.SMOOTH_BASALT) shouldBe true
            CivsMineSafety.natural(Material.GLOW_LICHEN) shouldBe false
            CivsMineSafety.natural(Material.COBBLESTONE) shouldBe false
            for (uncovered in listOf(Material.GLOW_LICHEN, Material.COBBLESTONE)) {
                f.block(roof).type = uncovered
                val city = f.city.copy(minePlans = mapOf(f.mine.id.toString() to f.state.copy(levels = null)))
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(city, f.cut()) }
                    .reason shouldBe "mine-thin-cover"
                f.block(f.point).type shouldBe Material.STONE
                f.block(roof).type shouldBe uncovered
            }
        }
    }

    test("unpaid and current-only floors cannot authorize a new landing below them") {
        historicalLandingFixture { f ->
            val witness = f.state.levels!!.single()
            val unpaid = witness.copy(v5Step = 0, v5Proof = CivMineV5Proof(0, sectionSteps = emptyMap()))
            for (state in listOf(f.state.copy(levels = null), f.state.copy(levels = listOf(unpaid)))) {
                val city = f.city.copy(minePlans = mapOf(f.mine.id.toString() to state))
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(city, f.cut()) }
                    .reason shouldBe "mine-thin-cover"
                f.block(f.point).type shouldBe Material.STONE
            }
        }
    }

    test("a lower paid floor still needs six vertical blocks and the exact native column and state") {
        historicalLandingFixture { f ->
            val roof = f.point.copy(y = 58)
            f.block(roof).type = Material.AIR
            f.block(f.point.copy(y = 54)).type = Material.COBBLESTONE // Only five above the current ceiling.
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
                .reason shouldBe "mine-thin-cover"
            f.block(f.point.copy(y = 54)).type = Material.AIR
            f.block(roof.copy(x = roof.x - 1)).type = Material.COBBLESTONE
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
                .reason shouldBe "mine-thin-cover"
            for (changed in listOf(Material.MOSSY_COBBLESTONE, Material.WATER)) {
                f.block(roof).type = changed
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
                    .reason shouldBe "mine-thin-cover"
                f.block(f.point).type shouldBe Material.STONE
            }
        }
    }

    test("a paid depth reference does not authorize a foreign target or revoke the current claim guard") {
        historicalLandingFixture { f ->
            for (foreign in listOf(Material.STONE_BRICKS, Material.COBBLESTONE)) {
                f.block(f.point).type = foreign
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
                    .reason shouldBe "mine-foreign-block"
                f.block(f.point).type shouldBe foreign
            }
            f.block(f.point).type = Material.STONE
            val lands = f.paper.createSimplePlugin("Lands")
            f.paper.server.pluginManager.disablePlugin(lands)
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
            f.block(f.point).type shouldBe Material.STONE
        }
    }

    test("historical landing ground never leaks through a same-column gallery or changed paid proof cache") {
        historicalLandingFixture { f ->
            // This real landing corner is also the later gallery-frame beam's column.
            val corner = CivPosition(-1848, 49, -2690)
            f.column(corner)
            val local = CivsMinePlan.localPoint(f.mine, corner)
            val shaft = f.plan.steps.indices.first { f.plan.steps[it].point == local &&
                f.plan.steps[it].stage == "shaft" && f.plan.steps[it].branch == "landing" }
            val gallery = f.plan.steps.indices.first { f.plan.steps[it].point == local && f.plan.steps[it].stage == "gallery" }
            f.admit(f.city, corner, shaft) shouldBe true
            f.owner.mineCovers.containsKey(f.worker.id) shouldBe true
            f.admit(f.city, corner, gallery) shouldBe false
            f.admit(f.city, corner, shaft) shouldBe true
            val withoutOldProof = f.city.copy(minePlans = mapOf(f.mine.id.toString() to f.state.copy(levels = null)))
            f.admit(withoutOldProof, corner, shaft) shouldBe false
            f.block(corner).type shouldBe Material.STONE
        }
    }

    test("a yielded historical scan resumes inside its existing one-read slices and receipt validation rereads the floor") {
        historicalLandingFixture { f ->
            val ordinal = requireNotNull(f.cut().miningOrdinal)
            var accepted: Boolean? = null
            var consumed = 0
            repeat(CivsMineSafety.MAXIMUM_COVER_READS) {
                if (accepted == null) {
                    accepted = f.admit(f.city, f.point, ordinal, checks = 1)
                    consumed += 1 - f.context.mineChecksRemaining
                    f.context.mineChecksRemaining shouldBe 0
                }
            }
            accepted shouldBe true
            (consumed in 2..CivsMineSafety.MAXIMUM_COVER_READS) shouldBe true
            f.block(f.point.copy(y = 58)).type = Material.MOSSY_COBBLESTONE
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut()) }
                .reason shouldBe "mine-thin-cover"
            f.block(f.point).type shouldBe Material.STONE
        }
    }
})

private class HistoricalLandingFixture(val paper: MockBukkitTestRuntime, val world: CivsWorksiteWorldFixture,
    val mine: CivBuilding, val state: CivMinePlanState, val city: Settlement, val runtime: CivsWorksites) {
    val point = CivPosition(-1842, 49, -2686)
    val worker = city.workers.single()
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }.get(runtime) as CivsMineWorksite
    val context = CivsWorksites::class.java.getDeclaredField("context").also { it.isAccessible = true }.get(runtime) as CivsWorksiteContext
    fun block(point: CivPosition) = world.getBlockAt(point.x, point.y, point.z)
    fun column(point: CivPosition) {
        for (y in 50..69) block(point.copy(y = y)).type = Material.AIR
        block(point.copy(y = 69)).type = Material.OAK_PLANKS // An ordinary roof alone is not terrain cover.
        block(point.copy(y = 50)).type = Material.COBBLESTONE
        block(point.copy(y = 58)).type = Material.COBBLESTONE
    }
    fun cut(): CivWorldWork {
        val local = CivsMinePlan.localPoint(mine, point)
        val ordinal = plan.cutOrdinals.getValue(local).first { plan.section(it) == "4/shaft/landing" }
        val progress = plan.normalizedProgress(state.nextStep, plan.advanceSection(ordinal, state.sectionSteps.orEmpty()))
        val native = block(point)
        return CivWorldWork(worker.id, worker.completedCycles, mine.id,
            CivsWorksiteLayouts.point(mine, requireNotNull(plan.steps[ordinal].approach)),
            listOf(CivPlanBlock(point, native.blockData.asString, "minecraft:air")),
            outputs = when (native.type) {
                Material.STONE -> mapOf("COBBLESTONE" to 1L)
                Material.SMOOTH_BASALT -> mapOf("SMOOTH_BASALT" to 1L)
                else -> emptyMap() // A pickaxe clears lichen without the shears-only item.
            },
            status = "digging-mine", durationMillis = 2_000, focus = point, miningPlanVersion = 7,
            miningStep = progress.first, miningAccessStep = state.nextStep, miningSections = progress.second,
            miningAccessSections = state.sectionSteps, miningOrdinal = ordinal)
    }
    fun admit(city: Settlement, point: CivPosition, ordinal: Int, checks: Int = 64): Boolean? {
        context.needs[worker.id] = CivWorkNeedState(mine, worker.completedCycles, "searching-work")
        context.discoveryBudget.deadline = Long.MAX_VALUE
        context.mineChecksRemaining = checks
        context.spotSearchPending = false
        val method = CivsMineWorksite::class.java.declaredMethods.single { it.name == "mineMutationAllowed" }
            .also { it.isAccessible = true }
        return try { method.invoke(owner, city, mine, world,
            CivPlanBlock(point, block(point).blockData.asString, "minecraft:air"), ordinal, worker, true, false, false, 7, false) as Boolean? }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
}

private fun historicalLandingFixture(action: (HistoricalLandingFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("historical-mine-landing").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("HistoricalMineLanding")
        val previous = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
            .copy(entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
        val mine = spatialBuilding("caledonia", "miner", 2, previous.anchor, previous.rotation)
            .copy(id = previous.id, entrance = previous.entrance, control = previous.control)
        val old = CivMinePlanState(7, 5, sectionSteps = mapOf("spine" to 60L), v5Step = 1818,
            v5Proof = CivMineV5Proof(1818, sectionSteps = emptyMap()))
        val state = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null)).copy(nextStep = 31,
            sectionSteps = mapOf("spine" to 68L, "1/shaft/landing" to 290L, "2/shaft/landing" to 290L, "3/shaft/landing" to 289L))
        val base = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
        fun shifted(point: CivPosition) = point.copy(x = point.x + mine.anchor.x, z = point.z + mine.anchor.z)
        val buildings = base.buildings.map { building -> if (building.type != BuildingType.TOWN_HALL) building else
            building.copy(anchor = shifted(building.anchor), bounds = CivBox(shifted(building.bounds.min), shifted(building.bounds.max)),
                entrance = shifted(building.entrance), control = building.control?.let(::shifted)) }
        val city = CivsRules.validate(base.copy(center = mine.anchor, buildings = buildings,
            inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state)))
        val cx = mine.anchor.x shr 4; val cz = mine.anchor.z shr 4
        for (x in cx - 2..cx + 2) for (z in cz - 2..cz + 2) world.loadChunk(x, z)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        for (ordinal in plan.sections.getValue("spine")) {
            val point = CivsWorksiteLayouts.point(mine, plan.steps[ordinal].point)
            world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(ordinal, mine.rotation))
        }
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                val f = HistoricalLandingFixture(paper, world, mine, state, city, runtime)
                f.column(f.point)
                action(f)
            }
        }
    }
}
