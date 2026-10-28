package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsMineSparseTest : FunSpec({
    test("deep spine receipts preserve the first unfinished V5 ordinal and only prove their actual section") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
        val gap = plan.steps.indices.first { plan.section(it) != "spine" }
        val before = CivMinePlanState(5, gap.toLong(), sectionSteps = emptyMap())
        val index = plan.sections.getValue("spine").first { it >= gap }
        val step = plan.steps[index]
        val sections = plan.advanceSection(index, emptyMap())
        val next = plan.normalizedProgress(before.nextStep, sections).first
        next shouldBe before.nextStep
        val work = CivWorldWork(UUID.randomUUID(), 0, mine.id, mine.entrance,
            listOf(CivPlanBlock(CivsWorksiteLayouts.point(mine, step.point), "minecraft:air", requireNotNull(step.after))),
            status = "digging-mine", miningPlanVersion = 5, miningStep = next,
            miningAccessStep = before.nextStep, miningSections = sections, miningAccessSections = emptyMap(), miningOrdinal = index)
        val after = CivsMineProgress.after(mine, before, null, work)
        after.nextStep shouldBe gap.toLong()
        after.sectionSteps shouldBe sections
        CivsMineProgress.valid(plan, after) shouldBe true
        // The original linear prefix is frozen once; later sparse ordinals are not
        // blanket permission to remove arbitrary cobblestone or timber.
        val migrated = CivsMineProgress.after(mine, before.copy(sectionSteps = null), null, work)
        migrated.v5Step shouldBe gap.toLong()
        CivsMineProgress.valid(plan, migrated.copy(v5Step = gap.toLong() + 1)) shouldBe false
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
            work.copy(status = "securing-mine")) }
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
            work.copy(changes = listOf(work.changes.single().copy(after = "minecraft:stone")))) }
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
            work.copy(miningStep = index.toLong() + 1)) }
        val allSpine = mapOf("spine" to plan.sections.getValue("spine").size.toLong())
        plan.builtStanding(plan.bottom.copy(y = plan.lowest), gap.toLong(), allSpine) shouldBe true
        val landing = requireNotNull(plan.entranceLanding).cells.first().let { it.copy(y = it.y + 1) }
        plan.builtStanding(landing, gap.toLong(), allSpine) shouldBe false
        plan.sectionReady("${plan.floors.last().index}/gallery/generator", gap.toLong(), allSpine) shouldBe false
        val lowerLanding = "${plan.floors.last().index}/shaft/landing"
        plan.sectionReady(lowerLanding, gap.toLong(), allSpine) shouldBe true
        plan.steps.size shouldBe plan.sections.values.sumOf { it.size }
        plan.sections.values.flatten().sorted() shouldBe plan.steps.indices.toList()
    }

    test("cover scans skip roofs and trees and stop at their fixed native read budget") {
        val cover = CivMineCover(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 82, -64)
        var reads = 0
        while (!cover.finished) cover.step { y ->
            reads++
            when { y >= 76 -> Material.OAK_LOG; y == 72 -> Material.OAK_PLANKS;
                y > 63 -> Material.AIR; else -> Material.GRASS_BLOCK }
        }
        cover.ground shouldBe 63
        reads shouldBe 20
        val tall = CivMineCover(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 200, -64)
        var tallReads = 0
        repeat(100) { tall.step { tallReads++; Material.OAK_LOG } }
        tall.finished shouldBe true
        tall.ground shouldBe null
        tallReads shouldBe CivsMineSafety.MAXIMUM_COVER_READS
    }

    test("the final supported mine level retires a paid seal after its last planned mutation") {
        val mine = spatialBuilding("medievaloak", "miner", 3, CivPosition(0, 63, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val index = plan.steps.lastIndex
        val oldCut = plan.cutOrdinals.entries.first { (_, cuts) -> cuts.all { it < index } }.key
        val before = CivMinePlanState(CivsMinePlan.VERSION, index.toLong(), sectionSteps = emptyMap(),
            seals = mapOf(CivsMineProgress.key(oldCut) to "minecraft:cobblestone"))
        CivsMineProgress.valid(plan, before) shouldBe true
        val sections = plan.advanceSection(index, emptyMap())
        val work = CivWorldWork(UUID.randomUUID(), 0, mine.id, mine.entrance,
            listOf(CivPlanBlock(CivsWorksiteLayouts.point(mine, plan.steps[index].point),
                "minecraft:stone", plan.expectedAfter(index, mine.rotation))), status = "digging-mine",
            miningPlanVersion = CivsMinePlan.VERSION, miningStep = plan.steps.size.toLong(),
            miningAccessStep = index.toLong(), miningSections = sections,
            miningAccessSections = emptyMap(), miningOrdinal = index)
        CivsMineProgress.after(mine, before, null, work).seals shouldBe null
    }

    test("an unsafe upper gallery defers while a paid lower section keeps the exact current prefix") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-upper-deferred").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineSections")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0), 180)
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val old = geometry.steps.indices.first { index -> geometry.steps[index].let { step ->
                    step.stage == "gallery" && step.floor == 1 && step.after == null &&
                        CivsMineSafety.requiredCover(geometry, index, step.point) == CivsMineSafety.FULL_COVER
                } }.toLong()
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, old)))
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                fun set(point: CivPosition, state: String) {
                    val p = CivsWorksiteLayouts.point(mine, point)
                    world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state).also {
                        if (!it.material.isAir) it.rotate(StructureRotation.CLOCKWISE_180)
                    }
                }
                geometry.steps.take(old.toInt()).forEach { set(it.point, it.after ?: "minecraft:air") }
                for (dy in 0..4) {
                    set(geometry.bottom.copy(y = geometry.bottom.y + dy),
                        if (dy <= 2) "minecraft:ladder[facing=${geometry.facing},waterlogged=false]" else "minecraft:air")
                    set(geometry.backing.copy(y = geometry.backing.y + dy), "minecraft:cobblestone")
                }
                city = city.copy(minePlans = mapOf(mine.id.toString() to
                    seedMinePaidSpine(world, mine, city.minePlans!!.getValue(mine.id.toString()))))
                val shallow = CivsWorksiteLayouts.point(mine, geometry.steps[old.toInt()].point)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    repeat(512) { if (runtime.plan(city, city.workers.single()) == null) runtime.pulse(listOf(city)) }
                    val work = requireNotNull(runtime.plan(city, city.workers.single())) { runtime.debug().toString() }
                    (requireNotNull(work.miningOrdinal) > old) shouldBe true
                    (work.changes.single().position.y < shallow.y) shouldBe true
                    work.miningStep shouldBe old
                    world.getBlockAt(shallow.x, shallow.y, shallow.z).type shouldBe Material.STONE
                    runtime.validate(city, work)
                    val after = CivsRules.finishWorldWork(city, work, 100_000)
                    after.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe old
                    after.minePlans!!.getValue(mine.id.toString()).sectionSteps!!.isNotEmpty() shouldBe true
                    after.workers.single().completedCycles shouldBe city.workers.single().completedCycles + 1
                    val section = geometry.section(requireNotNull(work.miningOrdinal))
                    (section != "spine") shouldBe true
                    work.changes.forEach { cell ->
                        world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
                    }
                    runtime.invalidate(city.workers.single().id, work)
                    city = after
                    var continued: CivWorldWork? = null
                    for (pulse in 0 until 512) {
                        runtime.pulse(listOf(city))
                        continued = runtime.plan(city, city.workers.single())
                        if (continued != null) break
                        withClue(runtime.debug()) {
                            runtime.idleReason(city, city.workers.single()) shouldBe "searching-work"
                        }
                    }
                    val next = withClue(runtime.debug()) { requireNotNull(continued) }
                    geometry.section(requireNotNull(next.miningOrdinal)) shouldBe section
                    (requireNotNull(next.miningOrdinal) > requireNotNull(work.miningOrdinal)) shouldBe true
                    runtime.validate(city, next)
                    val advanced = CivsRules.finishWorldWork(city, next, 102_000)
                    advanced.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe old
                    (geometry.sectionCount(section, old, advanced.minePlans!!.getValue(mine.id.toString()).sectionSteps.orEmpty()) >
                        geometry.sectionCount(section, old, after.minePlans!!.getValue(mine.id.toString()).sectionSteps.orEmpty())) shouldBe true
                    advanced.workers.single().completedCycles shouldBe city.workers.single().completedCycles + 1
                }
            } finally { tasks.close() }
        }
    }

    test("optional mine writes use native terrain cover and reject crafted basement blocks") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-cover-authority").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineCoverAuthority")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 45, 0))
                val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                val index = plan.steps.indices.first { plan.steps[it].stage == "gallery" && plan.steps[it].after == null }
                val target = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
                val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    fun work(before: String = "minecraft:stone") = CivWorldWork(city.workers.single().id, 0, mine.id,
                        mine.entrance, listOf(CivPlanBlock(target, before, "minecraft:air")), status = "digging-mine",
                        focus = target, miningPlanVersion = CivsMinePlan.VERSION, miningStep = 0, miningAccessStep = 0,
                        miningSections = emptyMap(), miningAccessSections = emptyMap(), miningOrdinal = index)
                    runtime.validateMineReceiptNative(city, work())
                    world.getBlockAt(target.x, target.y, target.z).type = Material.STONE_BRICKS
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work("minecraft:stone_bricks")) }
                        .reason shouldBe "mine-foreign-block"
                    world.getBlockAt(target.x, target.y, target.z).type = Material.STONE
                    // A lower slope and high timber roof share the same column. The roof is not ground cover.
                    val ground = target.y + 2
                    for (y in ground + 1..90) world.getBlockAt(target.x, y, target.z).type = Material.AIR
                    world.getBlockAt(target.x, ground, target.z).type = Material.GRASS_BLOCK
                    for (y in 75..84) world.getBlockAt(target.x, y, target.z).type = Material.OAK_LOG
                    world.getBlockAt(target.x, 72, target.z).type = Material.OAK_PLANKS
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work()) }.reason shouldBe "mine-thin-cover"
                    // Retirement is not another excavation after a later terrain/protection change.
                    world.getBlockAt(target.x, target.y, target.z).blockData = Bukkit.createBlockData("minecraft:air")
                    runtime.validateMineReceiptNative(city, work())
                }
            } finally { tasks.close() }
        }
    }
})
