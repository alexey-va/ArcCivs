package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.concurrent.Executor

class CivsMineLandingClearanceTest : FunSpec({
    test("a captured one-high landing prioritizes its own paid body clearance then seals the old frontier across reload") {
        withLandingClearance { f ->
            val beforeCobble = CivsEquipment.plainCount(f.city.workers.single(), "COBBLESTONE").toLong()
            val old = f.excavation
            f.block(LANDING_FAR_WALL).type shouldBe Material.AIR
            f.block(LANDING_NECK.copy(y = 44)).type shouldBe Material.STONE
            citizenCanReachBlock(f.world, Location(f.world, 3.5, 44.62, 9.5),
                LANDING_FAR_WALL, CITIZEN_MINE_REACH) shouldBe false
            var cuts = 0L
            var placements = 0L
            var minedCobble = 0L
            var spentCobble = 0L
            var sealed = false
            val trace = mutableListOf<String>()
            for (receipt in 0 until 24) {
                val work = f.nextWork()
                val cell = work.changes.single()
                if (work.status == "digging-mine") {
                    f.plan.section(requireNotNull(work.miningOrdinal)) shouldBe "5/shaft/landing"
                    if (cell.before == "minecraft:stone" && cell.after == "minecraft:air") cuts++
                } else work.status shouldBe "securing-mine"
                if (cell.before == "minecraft:stone") {
                    // Replacing a natural floor also breaks STONE with the real pickaxe before paying for COBBLESTONE.
                    work.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                }
                if (cell.after == "minecraft:cobblestone") work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                if (cell.before == "minecraft:air") work.outputs shouldBe emptyMap()
                minedCobble += work.outputs["COBBLESTONE"] ?: 0L
                spentCobble += work.inputs["COBBLESTONE"] ?: 0L
                if (receipt == 0) {
                    // Revalidate the OLD deferred opening, including a change during this action's delay.
                    for (changed in listOf(Material.WATER, Material.STONE_BRICKS)) {
                        f.block(LANDING_FAR_WALL).type = changed
                        shouldThrow<CivsRejection> { f.runtime.validate(f.city, work) }
                        f.block(cell.position).blockData.asString shouldBe cell.before
                    }
                    f.block(LANDING_FAR_WALL).type = Material.AIR
                }
                if (cell.after == "minecraft:cobblestone") placements++
                if (cell.position == LANDING_FAR_WALL && cell.after == "minecraft:cobblestone") {
                    work.standing shouldBe CivPosition(2, 43, 9)
                    val state = f.city.minePlans!!.getValue(f.mine.id.toString())
                    CivsMineApproach.mineBuiltStanding(f.mine, work.standing, state.nextStep,
                        state.sectionSteps.orEmpty(), state) shouldBe true
                    citizenCanReachBlock(f.world, Location(f.world, work.standing.x + .5,
                        work.standing.y + 1.62, work.standing.z + .5), LANDING_FAR_WALL, CITIZEN_MINE_REACH) shouldBe true
                }
                f.commit(work)
                trace += "$receipt ${work.status} ordinal=${work.miningOrdinal} ${cell.position} ${cell.before}->${cell.after}" +
                    " standing=${work.standing} excavation=${f.city.minePlans!!.getValue(f.mine.id.toString()).excavation}"
                f.city.minePlans!!.getValue(f.mine.id.toString()).excavation shouldBe old
                if (cell.position == LANDING_FAR_WALL && cell.after == "minecraft:cobblestone") {
                    work.status shouldBe "securing-mine"
                    work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                    work.outputs shouldBe emptyMap()
                    sealed = true
                    break
                }
                f.reload()
            }
            sealed shouldBe true
            withClue("native receipt trace:\n${trace.joinToString("\n")}") {
                cuts shouldBe 6L
                placements shouldBe 3L
                minedCobble shouldBe 8L
                spentCobble shouldBe 3L
                f.block(CivPosition(2, 44, 9)).type shouldBe Material.AIR
                // The new paid stance reaches the seal below this roof; no extra neck cut is needed.
                f.block(LANDING_NECK.copy(y = 44)).type shouldBe Material.STONE
            }
            f.block(LANDING_FAR_WALL).type shouldBe Material.COBBLESTONE
            CivsEquipment.plainCount(f.city.workers.single(), "COBBLESTONE").toLong() shouldBe
                beforeCobble + 5L
        }
    }

    test("liquid behind the low landing neck cannot be deferred into body clearance or an unrelated gallery") {
        withLandingClearance { f ->
            f.block(LANDING_FAR_WALL).type = Material.WATER
            val before = f.city
            repeat(256) {
                f.runtime.pulse(listOf(f.city))
                f.runtime.plan(f.city, f.city.workers.single())?.let { work ->
                    work.status shouldBe "securing-mine"
                    work.changes.single().position shouldBe LANDING_FAR_WALL
                }
            }
            f.block(LANDING_FAR_WALL).type shouldBe Material.WATER
            f.block(LANDING_NECK.copy(y = 44)).type shouldBe Material.STONE
            f.city shouldBe before
        }
    }

    test("an unplanned backing clearance cannot borrow a later landing or unrelated gallery") {
        withLandingClearance { f ->
            // A missing backing is not a future AIR cut: the immutable spine requires solid cobblestone here.
            f.block(LANDING_FAR_WALL).type = Material.COBBLESTONE
            val backing = CivPosition(4, 43, 10)
            f.block(backing).type = Material.AIR
            f.block(backing.copy(y = 44)).type = Material.COBBLESTONE
            f.block(backing.copy(x = 5)).type = Material.AIR
            val local = CivsMinePlan.localPoint(f.mine, backing.copy(y = 44))
            f.plan.cutOrdinals[local] shouldBe null
            val before = f.city
            repeat(256) {
                f.runtime.pulse(listOf(f.city))
                f.runtime.plan(f.city, f.city.workers.single())?.let { work ->
                    work.status shouldBe "securing-mine"
                    work.changes.single().position shouldBe backing.copy(x = 5)
                }
            }
            f.block(backing.copy(y = 44)).type shouldBe Material.COBBLESTONE
            f.city shouldBe before
        }
    }
})

private val LANDING_EXCAVATION = CivPosition(-3, -27, -9)
private val LANDING_NECK = CivPosition(4, 43, 9)
private val LANDING_FAR_WALL = CivPosition(5, 43, 9)

private class LandingClearanceFixture(val world: CivsWorksiteWorldFixture, val mine: CivBuilding, var city: Settlement,
    val access: CivsWorld, val plugin: org.bukkit.plugin.Plugin, val directory: java.nio.file.Path) {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val excavation = LANDING_EXCAVATION
    var runtime = freshRuntime()
    fun block(point: CivPosition) = world.getBlockAt(point.x, point.y, point.z)
    private fun freshRuntime(preferUnrelated: Boolean = false) = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L }).also {
        val owner = CivsWorksites::class.java.getDeclaredField("mine").also { field -> field.isAccessible = true }.get(it) as CivsMineWorksite
        val state = city.minePlans!!.getValue(mine.id.toString())
        // A valid ready gallery must yield to the specific dependency, including after restart.
        owner.mineExecutions[mine.id] = CivMineExecution(mine, state, null, state.nextStep, state.nextStep,
            state.sectionSteps.orEmpty().toMutableMap(), selected = if (preferUnrelated) "1/gallery/main" else null)
    }
    fun nextWork(): CivWorldWork {
        for (pulse in 0 until 256) {
            val rays = world.rayTraces
            runtime.pulse(listOf(city))
            (world.rayTraces - rays <= 8) shouldBe true
            runtime.plan(city, city.workers.single())?.let { return it }
            val context = CivsWorksites::class.java.getDeclaredField("context").also { it.isAccessible = true }
                .get(runtime) as CivsWorksiteContext
            if (context.currentNeed(city, city.workers.single())?.let {
                    it.reason == "line-of-sight-blocked" && it.point == LANDING_FAR_WALL } == true) break
        }
        return withClue("landing clearance: idle=${runtime.idleReason(city, city.workers.single())} debug=${runtime.debug()}") {
            requireNotNull(runtime.plan(city, city.workers.single()))
        }
    }
    fun commit(work: CivWorldWork) {
        runtime.validate(city, work)
        citizenStandable(world, work.standing) shouldBe true
        citizenPacketBodyClear(world, work.standing.point(), false) shouldBe true
        val worker = city.workers.single()
        CivsStorage(plugin) { emptyArray() }.use { storage ->
            val id = java.util.UUID.randomUUID()
            storage.reserve(city, id)
            val native = storage.prepareWork(city, mine.id, work.storageInputs(worker), emptyMap(), work.changes)
            val after = CivsRules.finishWorldWork(city, work, 100_000 + worker.completedCycles * 5_000)
                .copy(inventory = native.inventoryAfter)
            val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                worldChanges = native.worldChanges).validated()
            storage.seal(receipt)
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.unlock(id)
            city = receipt.after
        }
        CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
        runtime.invalidate(work.workerId, work)
    }
    fun reload() {
        city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
        runtime = freshRuntime(preferUnrelated = true)
    }
}

private fun withLandingClearance(action: (LandingClearanceFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("native-landing-one-high").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("MineLandingClearance")
        val previous = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0), 180)
            .copy(entrance = CivPosition(0, 71, 0), control = CivPosition(0, 71, 7))
        val mine = spatialBuilding("caledonia", "miner", 2, previous.anchor, previous.rotation)
            .copy(id = previous.id, entrance = previous.entrance, control = previous.control)
        val old = CivMinePlanState(7, 5, sectionSteps = mapOf("spine" to 60L), v5Step = 1818,
            v5Proof = CivMineV5Proof(1818, sectionSteps = emptyMap()))
        val state = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null)).copy(nextStep = 31,
            sectionSteps = mapOf("spine" to 68L, "1/shaft/landing" to 290L, "2/shaft/landing" to 290L,
                "3/shaft/landing" to 290L, "4/shaft/landing" to 96L, "5/shaft/landing" to 11L),
            excavation = LANDING_EXCAVATION,
            seals = mapOf("-5,-27,-11" to "minecraft:cobblestone", "-4,-27,-12" to "minecraft:cobblestone"))
        val base = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
        fun shift(p: CivPosition) = p.copy(x = p.x + mine.anchor.x, z = p.z + mine.anchor.z)
        val buildings = base.buildings.map { b -> if (b.type != BuildingType.TOWN_HALL) b else
            b.copy(anchor = shift(b.anchor), entrance = shift(b.entrance), control = b.control?.let(::shift),
                bounds = CivBox(shift(b.bounds.min), shift(b.bounds.max))) }
        val city = CivsRules.validate(base.copy(center = mine.anchor, buildings = buildings,
            inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state)))
        for (x in (mine.anchor.x shr 4) - 2..(mine.anchor.x shr 4) + 2)
            for (z in (mine.anchor.z shr 4) - 2..(mine.anchor.z shr 4) + 2) world.loadChunk(x, z)
        fun seed(b: CivBuilding, plan: CivsMinePlan, prefix: Long, sections: Map<String, Long>) {
            for ((i, step) in plan.steps.withIndex()) if (plan.completed(i, prefix, sections)) {
                val p = CivsWorksiteLayouts.point(b, step.point)
                world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(plan.expectedAfter(i, b.rotation))
            }
        }
        // MODEL: provide a real deep natural bank, as in the existing boundary-repair fixtures.
        // The standard fixture's y63 surface would otherwise fail cover for this y70 mine before LOS.
        seedMineNaturalCover(world, mine)
        seed(previous, requireNotNull(CivsMinePlan.forBuilding(previous, 5)), 1818, emptyMap())
        seed(mine, requireNotNull(CivsMinePlan.forBuilding(mine)), state.nextStep, state.sectionSteps.orEmpty())
        modelLandingNeck(world)
        val feet = CivPosition(3, 43, 9)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val authoredBottom = CivsWorksiteLayouts.point(mine, plan.bottom)
        val missingRungs = (feet.y..authoredBottom.y).filter {
            world.getBlockAt(authoredBottom.x, it, authoredBottom.z).type != Material.LADDER
        }
        // The procedural spine starts one below this rung; the building schematic supplies its entry.
        withClue("native missing rungs before authored entry=$missingRungs") { missingRungs shouldBe listOf(authoredBottom.y) }
        val ladderOrdinal = plan.steps.indices.first { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
        world.getBlockAt(authoredBottom.x, authoredBottom.y, authoredBottom.z).blockData =
            Bukkit.createBlockData(plan.expectedAfter(ladderOrdinal, mine.rotation))
        val backing = CivsWorksiteLayouts.point(mine, plan.backing)
        world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
        CivsMineApproach.mineBuiltStanding(mine, feet, state.nextStep, state.sectionSteps.orEmpty(), state) shouldBe true
        CivsMineApproach.mineLadderConnected(mine, world, feet) shouldBe true
        citizenStandable(world, feet) shouldBe true
        citizenPacketBodyClear(world, feet.point(), false) shouldBe true
        val control = requireNotNull(mine.control)
        world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
        val directory = Files.createTempDirectory("mine-landing-clearance-")
        try {
            city.revision shouldBe 0L
            CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    action(LandingClearanceFixture(world, mine, city, access, plugin, directory))
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}

/** A translated model of the observed neck, not a private terrain snapshot.
 * Default solid rock and the natural bank are MODEL geometry. These essential open/roof cells
 * preserve the observed AIR43 / solid head44 / distant AIR43 obstruction and the paid ladder. */
private fun modelLandingNeck(world: CivsWorksiteWorldFixture) {
    fun put(x: Int, y: Int, z: Int, material: Material) { world.getBlockAt(x, y, z).type = material }
    // Explicit two-row shaft component; missing model cells retain natural rock, never assumed AIR.
    for (y in 43..45) {
        put(3, y, 9, Material.AIR)
        put(3, y, 11, Material.AIR)
        put(2, y, 10, Material.AIR)
        put(4, y, 11, Material.AIR)
        put(4, y, 10, Material.COBBLESTONE)
        world.getBlockAt(3, y, 10).blockData = Bukkit.createBlockData("minecraft:ladder[facing=west,waterlogged=false]")
    }
    put(4, 43, 9, Material.AIR)
    put(4, 44, 9, Material.STONE)
    put(4, 45, 9, Material.STONE)
    put(5, 42, 9, Material.AIR)
    put(5, 43, 9, Material.AIR)
    put(5, 44, 9, Material.STONE)
    put(5, 45, 9, Material.STONE)
    // Observed support and ceiling around the usable current stance and the one-high neck.
    put(3, 42, 9, Material.STONE)
    put(4, 42, 9, Material.STONE)
    put(3, 42, 11, Material.COBBLESTONE)
    put(2, 42, 10, Material.COBBLESTONE)
    for (x in 2..4) for (z in 9..11) if (x != 3 || z != 10) put(x, 46, z, Material.COBBLESTONE)
    // These two already paid safety cells precede the inaccessible distant boundary.
    put(5, 43, 11, Material.COBBLESTONE)
    put(4, 43, 12, Material.COBBLESTONE)
    put(4, 44, 12, Material.COBBLESTONE)
}
