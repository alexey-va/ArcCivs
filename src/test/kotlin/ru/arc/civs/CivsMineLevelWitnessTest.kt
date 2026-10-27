package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineLevelWitnessTest : FunSpec({
    for (style in listOf("nordic", "medievaloak", "caledonia"))
    test("$style upgrade retains old paid geometry without treating an old ordinal as a new prefix") {
        var mine = spatialBuilding(style, "miner", 1, CivPosition(0, 63, 0), 90)
        val old = requireNotNull(CivsMinePlan.forBuilding(mine))
        var city = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
            minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, old.steps.size.toLong(), sectionSteps = emptyMap())))
        val worker = city.workers.single()
        for (level in 2..3) {
            val proof = requireNotNull(city.minePlans?.get(mine.id.toString()))
            val next = mine.copy(level = level, schematicId = mine.schematicId.dropLast(1) + level, phase = BuildingPhase.CONSTRUCTING)
            val required = CivsRules.workRequired(BuildingType.QUARRY, level)
            val order = CivConstruction(UUID.randomUUID(), mine.id, level, required, required, ConstructionPhase.PLACING,
                CivsRules.cost(BuildingType.QUARRY, level), plannedBuilding = next)
            city = CivsRules.completeConstruction(city.copy(construction = order), city.owner, order.id)
            val state = requireNotNull(city.minePlans?.get(mine.id.toString()))
            state.nextStep shouldBe 0L
            state.sectionSteps shouldBe emptyMap()
            state.levels!!.size shouldBe level - 1
            state.levels!!.last().building shouldBe mine
            state.levels!!.last().state() shouldBe proof.copy(levels = null)
            city.workers.single() shouldBe worker
            city.stock shouldBe Stock()
            mine = requireNotNull(city.building(mine.id))
            CivsMineProgress.validLevels(mine, state) shouldBe true
            shouldThrow<CivsRejection> { CivsRules.validate(city.copy(minePlans = mapOf(mine.id.toString() to
                state.copy(levels = state.levels!! + state.levels!!.first())))) }
            if (level < 3) city = city.copy(minePlans = mapOf(mine.id.toString() to state.copy(
                nextStep = requireNotNull(CivsMinePlan.forBuilding(mine)).steps.size.toLong())))
        }
    }

    test("lower-level history and its nested proof maps detach before the queued store and survive its codec") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val sections = linkedMapOf<String, Long>()
        val seals = linkedMapOf<String, String>()
        val before = CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = sections, seals = seals)
        val next = mine.copy(level = 2, schematicId = mine.schematicId.dropLast(1) + "2")
        val upgraded = requireNotNull(CivsMineProgress.upgraded(mine, next, before, null))
        val mutableWitness = upgraded.levels!!.single().copy(sectionSteps = sections, seals = seals)
        val levels = mutableListOf(mutableWitness)
        val state = upgraded.copy(levels = levels)
        val city = CivsRules.validate(spatialCity(UUID.randomUUID(), listOf(next), spatialWorker(next, "IRON_PICKAXE")).copy(
            minePlans = mapOf(next.id.toString() to state)))
        val expected = city.copy(minePlans = mapOf(next.id.toString() to CivsMineProgress.detached(state)))
        val pending = ArrayDeque<Runnable>()
        val root = Files.createTempDirectory("arc-civs-mine-levels-")
        try {
            CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                val saving = store.save(city)
                sections["foreign"] = 999
                seals["foreign"] = "minecraft:diamond_block"
                levels.clear()
                pending.removeFirst().run()
                saving.join() shouldBe expected
            }
            CivsStore(root, Executor { it.run() }).use { it.load().join() shouldBe listOf(expected) }
            Gson().fromJson("{\"version\":5,\"nextStep\":0}", CivMinePlanState::class.java).levels shouldBe null
        } finally { root.toFile().deleteRecursively() }
    }

    test("lower prerequisite clearance retains the exact oldest unsealed excavation until its shell is reachable") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val ladders = plan.sections.getValue("spine").filter { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
        val original = plan.steps[ladders.first()].point
        val ordinal = ladders[1]
        val cut = plan.steps[ordinal].point
        val before = CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = emptyMap(), excavation = original)
        val work = CivWorldWork(UUID.randomUUID(), 0, mine.id, mine.entrance,
            listOf(CivPlanBlock(CivsWorksiteLayouts.point(mine, cut), "minecraft:stone", "minecraft:air")),
            status = "digging-mine", miningPlanVersion = CivsMinePlan.VERSION, miningStep = 0, miningAccessStep = 0,
            miningSections = emptyMap(), miningAccessSections = emptyMap(), miningOrdinal = ordinal, miningExcavation = original)
        val retained = CivsMineProgress.after(mine, before, null, work)
        retained.excavation shouldBe original
        retained.nextStep shouldBe 0L
        val restored = Gson().fromJson(Gson().toJson(retained), CivMinePlanState::class.java)
        restored.excavation shouldBe original
        CivsMineProgress.after(mine, before, null, work.copy(miningExcavation = cut)).excavation shouldBe cut
    }
})
