package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineUpgradedHeadroomTest : FunSpec({
    for (obstruction in listOf("none", "water", "missing-proof", "foreign-fence", "missing-cap")) {
        test("an upgraded spine retains paid lower-level capped headroom with $obstruction") {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-upgraded-headroom").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineUpgradedHeadroom")
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    // The public Nordic origin projects the captured Auto1 floor/fence/headroom to Y64/65/66.
                    val previous = spatialBuilding("nordic", "miner", 1, CivPosition(0, 69, 0), 180)
                    val mine = spatialBuilding("nordic", "miner", 2, previous.anchor, previous.rotation)
                        .copy(id = previous.id, entrance = previous.entrance, control = previous.control)
                    val oldPlan = requireNotNull(CivsMinePlan.forBuilding(previous))
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val ring = oldPlan.sections.getValue("0/shaft/railing")
                    val old = CivMinePlanState(oldPlan.version, ring.last().toLong() + 1,
                        sectionSteps = mapOf("spine" to oldPlan.sections.getValue("spine").size.toLong()))
                    val upgraded = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null))
                    upgraded.nextStep shouldBe 0L
                    upgraded.sectionSteps shouldBe emptyMap()
                    CivsMineProgress.validLevels(mine, upgraded) shouldBe true
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(
                        inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to upgraded))
                    CivsRules.validate(city) shouldBe city
                    for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                    fun native(local: CivPosition) = CivsWorksiteLayouts.point(previous, local).let { world.getBlockAt(it.x, it.y, it.z) }
                    // Keep the existing surface-frontier fixture's natural bank outside the authored room.
                    for ((x, z) in oldPlan.shaftColumns) for (dx in -1..1) for (dz in -1..1)
                        for (y in oldPlan.transferFeetY - 1..oldPlan.transferFeetY + 2)
                            native(CivPosition(x + dx, y, z + dz)).type = Material.STONE
                    for (cell in requireNotNull(oldPlan.entranceLanding).cells) {
                        native(cell).type = Material.COBBLESTONE
                        native(cell.copy(y = cell.y + 1)).type = Material.AIR
                        native(cell.copy(y = cell.y + 2)).type = Material.AIR
                        native(cell.copy(y = cell.y + 3)).type = Material.COBBLESTONE
                    }
                    for ((index, step) in oldPlan.steps.withIndex())
                        if (oldPlan.completed(index, old.nextStep, old.sectionSteps.orEmpty()))
                            native(step.point).blockData = Bukkit.createBlockData(oldPlan.expectedAfter(index, previous.rotation))
                    val rung = oldPlan.sections.getValue("spine").first { oldPlan.steps[it].after?.startsWith("minecraft:ladder[") == true }
                    for (dy in 0..3) {
                        native(oldPlan.bottom.copy(y = oldPlan.bottom.y + dy)).blockData = Bukkit.createBlockData(oldPlan.expectedAfter(rung, previous.rotation))
                        native(oldPlan.backing.copy(y = oldPlan.backing.y + dy)).type = Material.COBBLESTONE
                    }
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }
                            .get(runtime) as CivsMineWorksite
                        val railFeet = ring.mapTo(hashSetOf()) { CivsWorksiteLayouts.point(previous, oldPlan.steps[it].point) }
                        val frontier = requireNotNull(owner.mineFrontier(city, mine, world, plan, plan.steps.first().point,
                            emptySet(), accessStep = 0, sectionSteps = emptyMap()))
                        val headroomTask = frontier.first { task -> CivsWorksiteLayouts.point(mine, task.block).let { p ->
                            p.copy(y = p.y - 1) in railFeet && p.y == 66
                        } }
                        val headroom = headroomTask.block
                        val point = CivsWorksiteLayouts.point(mine, headroom)
                        val fence = world.getBlockAt(point.x, point.y - 1, point.z)
                        val cap = world.getBlockAt(point.x, point.y + 1, point.z)
                        val air = world.getBlockAt(point.x, point.y, point.z)
                        fence.type shouldBe Material.SPRUCE_FENCE
                        cap.type shouldBe Material.COBBLESTONE
                        air.type shouldBe Material.AIR
                        when (obstruction) {
                            "water" -> air.type = Material.WATER
                            "missing-proof" -> city = city.copy(minePlans = mapOf(mine.id.toString() to upgraded.copy(levels = null)))
                            "foreign-fence" -> fence.type = Material.OAK_FENCE
                            "missing-cap" -> cap.type = Material.AIR
                        }
                        val beforeFence = fence.blockData.asString
                        val beforeCap = cap.blockData.asString
                        fun nextWork(): CivWorldWork? {
                            repeat(512) {
                                runtime.pulse(listOf(city))
                                runtime.plan(city, city.workers.single())?.let { return it }
                                if (owner.mineExecutions[mine.id]?.blockedSections?.containsKey("spine") == true) return null
                            }
                            return null
                        }
                        if (obstruction != "none") {
                            withClue(runtime.debug()) { nextWork() shouldBe null }
                            val denial = requireNotNull(owner.mineExecutions[mine.id]?.blockedSections?.get("spine"))
                            denial.reason shouldBe if (obstruction == "water") "adjacent-liquid" else "mine-thin-cover"
                            air.type shouldBe if (obstruction == "water") Material.WATER else Material.AIR
                            city.workers.single().completedCycles shouldBe worker.completedCycles
                        } else {
                            headroomTask.material shouldBe null
                            fun progressed(): Boolean {
                                val saved = city.minePlans!!.getValue(mine.id.toString())
                                return saved.nextStep > 0 || plan.sectionCount("spine", saved.nextStep, saved.sectionSteps.orEmpty()) > 0
                            }
                            var receipts = 0
                            var nativeAfterProgress = false
                            while (receipts < 64 && !nativeAfterProgress) {
                                val work = requireNotNull(nextWork()) {
                                    "${runtime.debug()} frontier=${owner.mineSafety[worker.id]?.frontier?.take(12)}"
                                }
                                withClue(runtime.debug()) {
                                    (work.miningStep != null) shouldBe true
                                    work.changes.isNotEmpty() shouldBe true
                                    work.changes.none { it.position == point } shouldBe true
                                }
                                runtime.validate(city, work)
                                val before = city
                                nativeAfterProgress = progressed()
                                city = CivsRules.finishWorldWork(before, work, 100_000L + receipts * 5_000)
                                city.workers.single().completedCycles shouldBe before.workers.single().completedCycles + 1
                                city.minePlans!!.getValue(mine.id.toString()).levels shouldBe upgraded.levels
                                work.changes.forEach { cell ->
                                    val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                    block.blockData.asString shouldBe cell.before
                                    block.blockData = Bukkit.createBlockData(cell.after)
                                }
                                runtime.invalidate(worker.id, completedWork = work)
                                air.type shouldBe Material.AIR
                                receipts++
                            }
                            withClue("receipts=$receipts proof=${city.minePlans} work=${runtime.debug()}") {
                                progressed() shouldBe true
                                nativeAfterProgress shouldBe true
                            }
                        }
                        fence.blockData.asString shouldBe beforeFence
                        cap.blockData.asString shouldBe beforeCap
                    }
                }
            }
        }
    }
})
