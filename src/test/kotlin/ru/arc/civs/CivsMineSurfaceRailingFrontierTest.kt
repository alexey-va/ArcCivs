package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineSurfaceRailingFrontierTest : FunSpec({
    for (obstruction in listOf(null, Material.WATER, Material.OAK_FENCE)) {
        test("the last three surface fences preserve paid headroom with obstruction=$obstruction") {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-surface-headroom").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineSurfaceHeadroom")
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    val mine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 75, 0), 180)
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val section = "0/shaft/railing"
                    val ring = plan.sections.getValue(section)
                    ring.size shouldBe 16
                    val prefix = ring[13]
                    fun native(local: CivPosition) = CivsWorksiteLayouts.point(mine, local).let { p -> world.getBlockAt(p.x, p.y, p.z) }
                    for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                    for ((x, z) in plan.shaftColumns) for (dx in -1..1) for (dz in -1..1)
                        for (y in plan.transferFeetY - 1..plan.transferFeetY + 2)
                            native(CivPosition(x + dx, y, z + dz)).type = Material.STONE
                    for (index in 0 until ring.first()) native(plan.steps[index].point).blockData =
                        Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
                    val state = seedMinePaidSpine(world, mine, CivMinePlanState(plan.version, prefix.toLong(), sectionSteps = emptyMap()))
                    val ladder = plan.steps.indexOfFirst { it.after?.startsWith("minecraft:ladder[") == true }
                    native(plan.bottom).blockData = Bukkit.createBlockData(plan.expectedAfter(ladder, mine.rotation))
                    native(plan.backing).type = Material.COBBLESTONE
                    for (index in ring) {
                        val rail = plan.steps[index].point
                        native(rail.copy(y = rail.y - 2)).type = Material.STONE
                        native(rail.copy(y = rail.y - 1)).type = Material.COBBLESTONE
                        native(rail).blockData = Bukkit.createBlockData(if (index < prefix) plan.expectedAfter(index, mine.rotation) else "minecraft:air")
                        native(rail.copy(y = rail.y + 1)).type = Material.AIR
                        native(rail.copy(y = rail.y + 2)).type = Material.COBBLESTONE
                    }
                    val lastSide = plan.steps[prefix].point.z
                    val paidFence = ring.take(13).map { plan.steps[it].point }.filter { it.z == lastSide }.minBy { it.x }
                    val headroom = paidFence.copy(y = paidFence.y + 1)
                    val headroomWorld = CivsWorksiteLayouts.point(mine, headroom)
                    if (obstruction == Material.WATER) native(headroom).type = obstruction
                    if (obstruction == Material.OAK_FENCE) native(paidFence).type = obstruction
                    val worker = spatialWorker(mine, "IRON_PICKAXE").let { supplied ->
                        supplied.copy(items = CivsEquipment.slots(supplied).toMutableList().also { slots ->
                            slots[CivsEquipment.BAG + 3] = CivResidentItem("SPRUCE_FENCE", 3)
                        })
                    }
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to state))
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }.get(runtime) as CivsMineWorksite
                        fun denial() = owner.mineExecutions[mine.id]?.blockedSections?.get(section)
                        fun nextWork(): CivWorldWork? {
                            repeat(128) {
                                runtime.pulse(listOf(city))
                                runtime.plan(city, city.workers.single())?.let { return it }
                                if (denial() != null) return null
                            }
                            return null
                        }
                        if (obstruction != null) {
                            withClue(runtime.debug()) { nextWork() shouldBe null }
                            val blocked = requireNotNull(denial()) { runtime.debug().toString() }
                            blocked.point shouldBe headroomWorld
                            blocked.reason shouldBe if (obstruction == Material.WATER) "adjacent-liquid" else "mine-thin-cover"
                        } else {
                            for (index in ring.drop(13)) {
                                val work = requireNotNull(nextWork()) { runtime.debug().toString() }
                                val step = plan.steps[index]
                                work.miningOrdinal shouldBe index
                                work.changes shouldBe listOf(CivPlanBlock(CivsWorksiteLayouts.point(mine, step.point),
                                    "minecraft:air", plan.expectedAfter(index, mine.rotation)))
                                work.inputs shouldBe mapOf("SPRUCE_FENCE" to 1L)
                                runtime.validate(city, work)
                                city = CivsRules.finishWorldWork(city, work, 100_000L + index)
                                native(step.point).blockData = Bukkit.createBlockData(work.changes.single().after)
                                runtime.invalidate(worker.id, work)
                            }
                            val finished = city.minePlans!!.getValue(mine.id.toString())
                            plan.sectionCount(section, finished.nextStep, finished.sectionSteps.orEmpty()) shouldBe 16
                            native(headroom).type shouldBe Material.AIR
                            CivsEquipment.plainCount(city.workers.single(), "SPRUCE_FENCE") shouldBe 0
                        }
                    }
                }
            }
        }
    }
})
