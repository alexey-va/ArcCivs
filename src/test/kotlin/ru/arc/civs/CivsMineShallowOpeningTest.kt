package ru.arc.civs

import com.google.gson.JsonArray
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineShallowOpeningTest : FunSpec({
    test("a retained lower section opens the shallow shaft through paid access receipts without completing its landing") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-shallow-opening").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineShallowOpening")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                val publicMine = spatialBuilding("nordic", "miner", 1, CivPosition(12277, 70, 9718), 180)
                val id = "fixture/nordic/shallow-opening1"
                // The public Nordic site maps to the retained curated site by this verified half-turn and translation.
                val site = requireNotNull(CivsWorksiteLayouts.site(publicMine)).deepCopy()
                val layout = site.getAsJsonObject("mine")
                fun point(value: com.google.gson.JsonElement) = CivsWorksiteLayouts.vector(value).let { p ->
                    JsonArray().apply { add(4 - p.x); add(p.y - 1); add(-23 - p.z) }
                }
                for (key in listOf("ladderBottom", "shaftCenter", "firstSubgroundPoint")) layout.add(key, point(layout[key]))
                layout.addProperty("ladderFacing", "south")
                layout.add("shaftFootprint", JsonArray().apply {
                    layout.getAsJsonArray("shaftFootprint").forEach { entry ->
                        add(JsonArray().apply { add(4 - entry.asJsonArray[0].asInt); add(-23 - entry.asJsonArray[1].asInt) })
                    }
                })
                CivsWorksiteLayouts.installCatalog(mapOf(id to site))
                try {
                    val mine = publicMine.copy(schematicId = id, entrance = publicMine.anchor.copy(y = 71),
                        control = CivPosition(12276, 71, 9719),
                        bounds = CivBox(CivPosition(12263, 67, 9719), CivPosition(12283, 94, 9741)))
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val landing = plan.sections.getValue("1/shaft/landing")
                    val opening = plan.sections.getValue("1/shaft/opening")
                    val stance = requireNotNull(plan.steps[opening.first()].approach)
                    val corridor = landing.takeWhile { index -> plan.steps[index].point.let { it.x == stance.x && it.z == stance.z } }
                    corridor.size shouldBe 4
                    opening.size shouldBe 36
                    (landing.size - corridor.size) shouldBe 286
                    val lower = "6/gallery/right"
                    val sections = mapOf("spine" to plan.sections.getValue("spine").size.toLong()) +
                        (3..6).associate { "$it/shaft/landing" to plan.sections.getValue("$it/shaft/landing").size.toLong() }
                    val prefix = plan.sections.getValue("0/shaft/railing").last().toLong() + 1
                    val oldCut = plan.sections.getValue("6/shaft/landing").last { plan.steps[it].after == null }
                    val state = CivMinePlanState(plan.version, prefix, sectionSteps = sections,
                        excavation = plan.steps[oldCut].point)
                    CivsMineProgress.valid(plan, state, mine) shouldBe true
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val template = spatialCity(world.uid, listOf(mine), worker)
                    fun moved(p: CivPosition) = CivPosition(p.x + mine.anchor.x - template.center.x,
                        p.y + mine.anchor.y - template.center.y, p.z + mine.anchor.z - template.center.z)
                    val buildings = template.buildings.map { building ->
                        if (building.id == mine.id) building else building.copy(anchor = moved(building.anchor),
                            bounds = CivBox(moved(building.bounds.min), moved(building.bounds.max)),
                            entrance = moved(building.entrance), control = building.control?.let(::moved))
                    }
                    var city = template.copy(center = mine.anchor, buildings = buildings,
                        inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state))
                    CivsRules.validate(city) shouldBe city
                    for (x in (mine.anchor.x shr 4) - 4..(mine.anchor.x shr 4) + 4)
                        for (z in (mine.anchor.z shr 4) - 4..(mine.anchor.z shr 4) + 4) world.loadChunk(x, z)
                    fun native(local: CivPosition) = CivsWorksiteLayouts.point(mine, local).let { world.getBlockAt(it.x, it.y, it.z) }
                    // Native Auto1 columns, 2026-11-11: stone through Y63, paid floor Y64,
                    // AIR/fence Y65, AIR Y66, cobblestone roof Y67, and AIR above it.
                    for (cell in requireNotNull(plan.entranceLanding).cells) {
                        native(cell).type = Material.COBBLESTONE
                        native(cell.copy(y = cell.y + 1)).type = Material.AIR
                        native(cell.copy(y = cell.y + 2)).type = Material.AIR
                        native(cell.copy(y = cell.y + 3)).type = Material.COBBLESTONE
                    }
                    for ((index, step) in plan.steps.withIndex()) if (plan.completed(index, state.nextStep, sections))
                        native(step.point).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
                    val rung = plan.sections.getValue("spine").first { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
                    for (dy in 0..3) {
                        native(plan.bottom.copy(y = plan.bottom.y + dy)).blockData = Bukkit.createBlockData(plan.expectedAfter(rung, mine.rotation))
                        native(plan.backing.copy(y = plan.backing.y + dy)).type = Material.COBBLESTONE
                    }
                    for (index in opening) native(plan.steps[index].point).type = Material.STONE
                    native(plan.steps[corridor.first()].point).type shouldBe Material.STONE
                    native(stance.copy(y = stance.y + 3)).type shouldBe Material.COBBLESTONE
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }
                            .get(runtime) as CivsMineWorksite
                        fun proof() = city.minePlans!!.getValue(mine.id.toString())
                        fun receipt(index: Int, snapshot: Settlement = city): CivWorldWork {
                            val step = plan.steps[index]
                            val block = native(step.point)
                            val saved = snapshot.minePlans!!.getValue(mine.id.toString())
                            val accessSections = saved.sectionSteps.orEmpty()
                            val after = plan.normalizedProgress(saved.nextStep, plan.advanceSection(index, accessSections))
                            return CivWorldWork(worker.id, snapshot.workers.single().completedCycles, mine.id,
                                CivsWorksiteLayouts.point(mine, stance),
                                listOf(CivPlanBlock(CivPosition(block.x, block.y, block.z), block.blockData.asString,
                                    plan.expectedAfter(index, mine.rotation))), status = "digging-mine",
                                miningPlanVersion = plan.version, miningOrdinal = index, miningStep = after.first,
                                miningAccessStep = saved.nextStep, miningAccessSections = accessSections, miningSections = after.second)
                        }
                        // A physical fence alone cannot authorize the new access cut without durable upper proof.
                        val missingRail = city.copy(minePlans = mapOf(mine.id.toString() to state.copy(
                            nextStep = plan.sections.getValue("0/shaft/railing").first().toLong())))
                        shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(missingRail, receipt(corridor.first(), missingRail)) }
                            .reason shouldBe "mine-thin-cover"
                        shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, receipt(landing[corridor.size])) }
                            .reason shouldBe "mine-thin-cover"
                        native(plan.steps[corridor.first()].point).type shouldBe Material.STONE
                        proof() shouldBe state
                        owner.mineExecutions[mine.id] = CivMineExecution(mine, state, null, prefix, prefix,
                            sections.toMutableMap(), selected = lower)
                        fun nextWork(): CivWorldWork {
                            repeat(512) {
                                runtime.pulse(listOf(city))
                                runtime.plan(city, city.workers.single())?.let { return it }
                            }
                            error("No selected mine work: ${runtime.debug()}")
                        }
                        var at = 100_000L
                        fun commit(work: CivWorldWork) {
                            val before = city
                            runtime.validate(before, work)
                            val after = CivsRules.finishWorldWork(before, work, at)
                            after.workers.single().completedCycles shouldBe before.workers.single().completedCycles + 1
                            // Canonical progress is committed before the corresponding native write.
                            city = after
                            work.changes.forEach { cell ->
                                val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                block.blockData.asString shouldBe cell.before
                                block.blockData = Bukkit.createBlockData(cell.after)
                            }
                            runtime.invalidate(worker.id, completedWork = work)
                            at += 5_000
                        }
                        var lowerReceipts = 0
                        var warmupReceipts = 0
                        while (lowerReceipts < 8 && warmupReceipts < 64) {
                            val work = nextWork()
                            plan.section(requireNotNull(work.miningOrdinal)) shouldBe lower
                            commit(work)
                            if (work.status == "digging-mine") lowerReceipts++
                            warmupReceipts++
                        }
                        withClue(runtime.debug()) { lowerReceipts shouldBe 8 }
                        val first = nextWork()
                        withClue("Retained lower work must reconsider the now-available shaft access: ${runtime.debug()}") {
                            first.miningOrdinal shouldBe corridor.first()
                        }
                        var pending: CivWorldWork? = first
                        var receipts = 0
                        var actualOpeningCuts = 0
                        while (plan.sectionCount("1/shaft/opening", proof().nextStep, proof().sectionSteps.orEmpty()) < opening.size && receipts < 160) {
                            val work = pending ?: nextWork()
                            pending = null
                            val index = requireNotNull(work.miningOrdinal)
                            if (index in opening && work.status == "digging-mine" && work.changes.single().after == "minecraft:air") actualOpeningCuts++
                            commit(work)
                            receipts++
                            (plan.sectionCount("1/shaft/landing", proof().nextStep, proof().sectionSteps.orEmpty()) <= corridor.size) shouldBe true
                        }
                        withClue("receipts=$receipts work=${runtime.debug()} proof=${proof()}") {
                            plan.sectionCount("1/shaft/landing", proof().nextStep, proof().sectionSteps.orEmpty()) shouldBe corridor.size.toLong()
                            plan.sectionCount("1/shaft/opening", proof().nextStep, proof().sectionSteps.orEmpty()) shouldBe opening.size.toLong()
                            actualOpeningCuts shouldBe 36
                            opening.forEach { native(plan.steps[it].point).type shouldBe Material.AIR }
                            landing.drop(corridor.size).filter { plan.steps[it].after == null }.forEach {
                                native(plan.steps[it].point).type shouldBe Material.STONE
                            }
                            CivsMineProgress.valid(plan, proof(), mine) shouldBe true
                        }
                    }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
    }
})
