package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsInitialMineProjectionTest : FunSpec({
    test("V7 waives cover only at its exact authored surface actions and preserves every other requirement") {
        for (style in listOf("medievaloak", "nordic", "caledonia")) for (level in 1..3) for (version in 2..7) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 75, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, version))
            fun ordinary(ordinal: Int, point: CivPosition): Int =
                if (plan.section(ordinal) == "spine" && kotlin.math.abs(point.x - plan.bottom.x) <= 1 &&
                    kotlin.math.abs(point.z - plan.bottom.z) <= 1) 0
                else if (maxOf(kotlin.math.abs(point.x - plan.center.x), kotlin.math.abs(point.z - plan.center.z)) <= 8) 6 else 16
            for ((ordinal, step) in plan.steps.withIndex()) {
                val surface = version == 7 && plan.section(ordinal) in setOf("0/shaft/entrance", "0/shaft/opening")
                CivsMineSafety.requiredCover(plan, ordinal, step.point) shouldBe if (surface) 0 else ordinary(ordinal, step.point)
                if (surface) {
                    val neighbour = step.point.copy(x = step.point.x + 1)
                    CivsMineSafety.requiredCover(plan, ordinal, neighbour) shouldBe ordinary(ordinal, neighbour)
                }
            }
            if (version == 7) {
                plan.sections.getValue("0/shaft/opening").size shouldBe 27
                plan.sections.getValue("0/shaft/entrance").map { plan.steps[it].after }.toSet() shouldBe
                    setOf(null, "minecraft:cobblestone", "minecraft:torch")
            }
        }
    }

    test("retained schematic overwrites terrain only for exact mouth cuts and structural identity") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val plugin = paper.createSimplePlugin("InitialMineProjection")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val root = Files.createTempDirectory("initial-mine-projection-")
            val direct = Executor(Runnable::run)
            try {
                CivsStore(root, direct).use { store ->
                    val mine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 75, 0), 90)
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val point = plan.sections.getValue("0/shaft/opening").map { plan.steps[it].point }
                        .first { it.y == plan.bottom.y }.let { CivsWorksiteLayouts.point(mine, it) }
                    val outside = mine.bounds.max
                    val city = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                    val retained = CivPlacementPlan(mine.id, city.id, city.worldId, mine.copy(phase = BuildingPhase.CONSTRUCTING),
                        listOf(CivPlanBlock(point, "minecraft:stone", "minecraft:cobblestone"),
                            CivPlanBlock(outside, "minecraft:air", "minecraft:cobblestone")), "a".repeat(64),
                        CivTerraformPlan(CivBox(point, point), listOf(CivPlanBlock(point, "minecraft:dirt", "minecraft:stone")),
                            listOf(mine.entrance), mine.anchor.y, 0, 1))
                    store.savePlan(retained).join() shouldBe retained
                    val owner = CivsCityPlans(mockk(), mockk(), tasks, store, direct, mockk())
                    val prepared = owner.prepareInitialMines(city).join()
                    prepared.single().cuts shouldBe mapOf(point to "minecraft:cobblestone")
                    owner.initialMineBlock(city, mine, point, "minecraft:cobblestone") shouldBe false
                    owner.publishInitialMines(city.id, prepared)
                    owner.initialMineBlock(city, mine, point, "minecraft:cobblestone") shouldBe true
                    owner.initialMineBlock(city, mine, point, "minecraft:stone") shouldBe false
                    owner.initialMineBlock(city, mine, outside, "minecraft:cobblestone") shouldBe false
                    val control = requireNotNull(mine.control)
                    val movedControl = mine.copy(control = control.copy(x = control.x + 1), production = "STONE")
                    val moved = city.copy(buildings = city.buildings.map { if (it.id == mine.id) movedControl else it })
                    owner.initialMinesUnchanged(city, moved) shouldBe true
                    owner.initialMineBlock(moved, movedControl, point, "minecraft:cobblestone") shouldBe true
                    for (changed in listOf(mine.copy(id = UUID.randomUUID()), mine.copy(type = BuildingType.FARM),
                        mine.copy(level = 2), mine.copy(schematicId = mine.schematicId + "-other"),
                        mine.copy(anchor = mine.anchor.copy(x = mine.anchor.x + 1)), mine.copy(rotation = 180),
                        mine.copy(bounds = mine.bounds.copy(max = mine.bounds.max.copy(y = mine.bounds.max.y + 1))),
                        mine.copy(entrance = mine.entrance.copy(x = mine.entrance.x + 1)), mine.copy(phase = BuildingPhase.SITE))) {
                        val candidate = city.copy(buildings = city.buildings.map { if (it.id == mine.id) changed else it })
                        owner.initialMinesUnchanged(city, candidate) shouldBe false
                        owner.initialMineBlock(candidate, changed, point, "minecraft:cobblestone") shouldBe false
                        owner.prepareInitialMines(candidate).join() shouldBe emptyList()
                    }
                    for (changed in listOf(city.copy(id = UUID.randomUUID()), city.copy(worldId = UUID.randomUUID()))) {
                        owner.initialMineBlock(changed, mine, point, "minecraft:cobblestone") shouldBe false
                        owner.prepareInitialMines(changed).join() shouldBe emptyList()
                    }
                    val transferred = city.copy(owner = UUID.randomUUID())
                    owner.initialMinesUnchanged(city, transferred) shouldBe false
                    owner.initialMineBlock(transferred, mine, point, "minecraft:cobblestone") shouldBe false
                    owner.publishInitialMines(city.id, owner.prepareInitialMines(transferred).join())
                    owner.initialMineBlock(transferred, mine, point, "minecraft:cobblestone") shouldBe true
                    val virtual = city.copy(inventory = null)
                    owner.initialMinesUnchanged(city, virtual) shouldBe false
                    owner.prepareInitialMines(virtual).join() shouldBe emptyList()
                    owner.remove(city.id)
                    owner.initialMineBlock(transferred, mine, point, "minecraft:cobblestone") shouldBe false
                    // A genuinely absent capture is ordinary legacy data; a damaged capture is an IO failure.
                    store.deletePlan(mine.id).join()
                    owner.prepareInitialMines(city).join() shouldBe emptyList()
                    Files.writeString(root.resolve("plans").resolve("${mine.id}.json"), "{broken")
                    shouldThrow<CompletionException> { owner.prepareInitialMines(city).join() }
                }
            } finally { tasks.close(); root.toFile().deleteRecursively() }
        }
    }
})
