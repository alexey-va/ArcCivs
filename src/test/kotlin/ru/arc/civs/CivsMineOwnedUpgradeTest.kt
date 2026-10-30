package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Fence
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.block.data.FenceDataMock
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineOwnedUpgradeTest : FunSpec({
    test("the pinned typed fence fixture serializes changed connections with its remaining state intact") {
        MockBukkitTestRuntime.open(OwnedMineUpgradeServerFixture()).use {
            val paid = Bukkit.createBlockData(OWNED_MINE_FENCE) as Fence
            val native = Bukkit.createBlockData(
                "minecraft:dark_oak_fence[east=false,north=false,south=true,waterlogged=false,west=false]") as Fence
            println("mine-fence-typed before=${paid.javaClass.name}/${paid.faces}/${paid.isWaterlogged}/${paid.asString} " +
                "native=${native.javaClass.name}/${native.faces}/${native.isWaterlogged}/${native.asString}")
            paid.allowedFaces.forEach { paid.setFace(it, native.hasFace(it)) }
            println("mine-fence-typed after=${paid.javaClass.name}/${paid.faces}/${paid.isWaterlogged}/${paid.asString}")
            paid.faces shouldBe native.faces
            paid.isWaterlogged shouldBe false
            paid.material shouldBe Material.DARK_OAK_FENCE
            paid.asString shouldBe native.asString
        }
    }

    test("the saved lower-level V5 prefix owns the exact captured landing fences") {
        MockBukkitTestRuntime.open(OwnedMineUpgradeServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-paid-upgrade-proof").also(paper.server::addWorld)
            val previous = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
                .copy(entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
            val mine = spatialBuilding("caledonia", "miner", 2, previous.anchor, previous.rotation)
                .copy(id = previous.id, entrance = previous.entrance, control = previous.control)
            // Pruned from the saved lower-level witness: these prefixes alone prove the four rail cells.
            val old = CivMinePlanState(7, 5, sectionSteps = mapOf("spine" to 60L), v5Step = 1818,
                v5Proof = CivMineV5Proof(1818, sectionSteps = emptyMap()))
            val state = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null))
                .copy(nextStep = 4, sectionSteps = mapOf("spine" to 68L, "3/shaft/landing" to 14L))
            CivsMineProgress.validLevels(mine, state) shouldBe true
            val witness = state.levels!!.single()
            val accepted = requireNotNull(CivsMineProgress.v5State(witness.state()))
            val oldPlan = requireNotNull(CivsMinePlan.forBuilding(witness.building, accepted.version))
            val current = requireNotNull(CivsMinePlan.forBuilding(mine))
            val paid = "minecraft:dark_oak_fence[east=false,north=true,south=true,waterlogged=false,west=false]"
            val shortened = "minecraft:dark_oak_fence[east=false,north=false,south=true,waterlogged=false,west=false]"
            for ((point, captured) in listOf(
                CivPosition(-1842, 63, -2686) to paid,
                CivPosition(-1842, 59, -2686) to paid,
                CivPosition(-1842, 55, -2686) to paid,
                CivPosition(-1842, 51, -2685) to shortened,
            )) {
                world.loadChunk(point.x shr 4, point.z shr 4)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(captured)
                val local = CivsMinePlan.localPoint(witness.building, point)
                val expected = oldPlan.expectedAt(local, accepted.nextStep, accepted.sectionSteps.orEmpty(), witness.building.rotation)
                withClue("world=$point local=$local oldV5Prefix=${accepted.nextStep} shaftEnd=${oldPlan.shaftEnd} expected=$expected native=$captured") {
                    expected shouldBe paid
                    oldPlan.expectedAt(local, 0, emptyMap(), witness.building.rotation) shouldBe null
                    current.cutOrdinals.getValue(local).any { !current.completed(it, state.nextStep, state.sectionSteps.orEmpty()) } shouldBe true
                    world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe captured
                }
                println("paid-mine-upgrade-proof world=$point local=$local expected=$expected native=$captured")
            }
            println("paid-mine-upgrade-proof oldV5Prefix=${accepted.nextStep} shaftEnd=${oldPlan.shaftEnd}")
        }
    }

    for (shortened in listOf(false, true))
        test("a paid lower-level fence with shortened connections=$shortened keeps its native cut and item receipt") {
        ownedMineUpgradeFixture { f ->
            val point = if (shortened) CivPosition(-1842, 51, -2685) else CivPosition(-1842, 59, -2686)
            val work = f.cut(point)
            val before = f.block(point).blockData.asString
            work.outputs shouldBe mapOf("DARK_OAK_FENCE" to 1L)
            work.inputs shouldBe emptyMap()
            work.durationMillis shouldBe 2_000L
            f.runtime.validateMineReceiptNative(f.city, work)
            f.block(point).blockData.asString shouldBe before // Admission does not perform the cut.
            val worker = f.city.workers.single()
            val after = CivsRules.finishWorldWork(f.city, work, 10_000)
            CivsEquipment.plainCount(worker, "DARK_OAK_FENCE") shouldBe 0L
            CivsEquipment.plainCount(after.workers.single(), "DARK_OAK_FENCE") shouldBe 1L
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
            after.minePlans!!.getValue(f.mine.id.toString()).levels shouldBe f.state.levels
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 12_000) }
            f.block(point).type = Material.AIR
            f.runtime.validateMineReceiptNative(after, work) // An already applied receipt can retire after restart.
            CivsEquipment.plainCount(after.workers.single(), "DARK_OAK_FENCE") shouldBe 1L
        }
    }

    test("foreign wet and unproved fences remain protected without native writes") {
        ownedMineUpgradeFixture { f ->
            val point = CivPosition(-1842, 59, -2686)
            val witness = f.state.levels!!.single()
            val unpaid = witness.copy(v5Step = 0, v5Proof = CivMineV5Proof(0, sectionSteps = emptyMap()))
            for ((state, data) in listOf(
                f.state.copy(levels = null) to OWNED_MINE_FENCE,
                f.state.copy(levels = listOf(unpaid)) to OWNED_MINE_FENCE,
                f.state to OWNED_MINE_FENCE.replace("dark_oak", "spruce"),
                f.state to OWNED_MINE_FENCE.replace("waterlogged=false", "waterlogged=true"),
                f.state to "minecraft:smooth_basalt",
            )) {
                f.block(point).blockData = Bukkit.createBlockData(data)
                val city = f.city.copy(minePlans = mapOf(f.mine.id.toString() to state))
                shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(city, f.cut(point)) }
                    .reason shouldBe "mine-foreign-block"
                f.block(point).blockData.asString shouldBe data
                city.workers shouldBe f.city.workers
                city.minePlans!!.getValue(f.mine.id.toString()) shouldBe state
            }
        }
    }

    test("new natural excavation keeps its cover gate and paid ownership keeps the live claim gate") {
        ownedMineUpgradeFixture { f ->
            val point = CivPosition(-1842, 59, -2686)
            f.block(point).type = Material.STONE
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut(point)) }
                .reason shouldBe "mine-thin-cover"
            f.block(point).type shouldBe Material.STONE
            f.block(point).blockData = Bukkit.createBlockData(OWNED_MINE_FENCE)
            val lands = f.paper.createSimplePlugin("Lands")
            f.paper.server.pluginManager.disablePlugin(lands)
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(f.city, f.cut(point)) }
            f.block(point).blockData.asString shouldBe OWNED_MINE_FENCE
            f.city.minePlans!!.getValue(f.mine.id.toString()) shouldBe f.state
        }
    }
})

private const val OWNED_MINE_FENCE = "minecraft:dark_oak_fence[east=false,north=true,south=true,waterlogged=false,west=false]"

private class OwnedMineUpgradeFixture(val paper: MockBukkitTestRuntime, val world: CivsWorksiteWorldFixture,
    val mine: CivBuilding, val state: CivMinePlanState, val city: Settlement, val runtime: CivsWorksites) {
    fun block(point: CivPosition) = world.getBlockAt(point.x, point.y, point.z)
    fun cut(point: CivPosition): CivWorldWork {
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val local = CivsMinePlan.localPoint(mine, point)
        val ordinal = plan.cutOrdinals.getValue(local).first { !plan.completed(it, state.nextStep, state.sectionSteps.orEmpty()) }
        val progress = plan.normalizedProgress(state.nextStep, plan.advanceSection(ordinal, state.sectionSteps.orEmpty()))
        val native = block(point)
        // Sample the same native drops as the unchanged mine planner; no replacement/refund item is invented.
        val outputs = if (native.type == Material.SMOOTH_BASALT) emptyMap() else native.getDrops(ItemStack(Material.IRON_PICKAXE))
            .groupBy { it.type.name }.mapValues { (_, stacks) -> stacks.sumOf { it.amount.toLong() } }
        val worker = city.workers.single()
        return CivWorldWork(worker.id, worker.completedCycles, mine.id,
            CivsWorksiteLayouts.point(mine, plan.steps[ordinal].approach ?: local),
            listOf(CivPlanBlock(point, native.blockData.asString, "minecraft:air")), outputs = outputs,
            durationMillis = 2_000, status = "digging-mine", focus = point,
            miningPlanVersion = 7, miningStep = progress.first, miningAccessStep = state.nextStep,
            miningSections = progress.second, miningAccessSections = state.sectionSteps, miningOrdinal = ordinal)
    }
}

private fun ownedMineUpgradeFixture(check: (OwnedMineUpgradeFixture) -> Unit) {
    MockBukkitTestRuntime.open(OwnedMineUpgradeServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("mine-paid-native-upgrade").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("MinePaidNativeUpgrade")
        val previous = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
            .copy(entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
        val mine = spatialBuilding("caledonia", "miner", 2, previous.anchor, previous.rotation)
            .copy(id = previous.id, entrance = previous.entrance, control = previous.control)
        val old = CivMinePlanState(7, 5, sectionSteps = mapOf("spine" to 60L), v5Step = 1818,
            v5Proof = CivMineV5Proof(1818, sectionSteps = emptyMap()))
        val state = requireNotNull(CivsMineProgress.upgraded(previous, mine, old, null)).copy(nextStep = 4,
            sectionSteps = mapOf("spine" to 68L, "1/shaft/landing" to 2L, "2/shaft/landing" to 2L, "3/shaft/landing" to 14L))
        val initial = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
        fun shifted(point: CivPosition) = point.copy(x = point.x + mine.anchor.x, z = point.z + mine.anchor.z)
        val buildings = initial.buildings.map { building ->
            if (building.type != BuildingType.TOWN_HALL) building else building.copy(anchor = shifted(building.anchor),
                bounds = CivBox(shifted(building.bounds.min), shifted(building.bounds.max)), entrance = shifted(building.entrance),
                control = building.control?.let(::shifted))
        }
        val city = CivsRules.validate(initial.copy(center = mine.anchor, buildings = buildings,
            inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state)))
        CivsMineProgress.validLevels(mine, state) shouldBe true
        val cx = mine.anchor.x shr 4; val cz = mine.anchor.z shr 4
        for (x in cx - 2..cx + 2) for (z in cz - 2..cz + 2) world.loadChunk(x, z)
        fun write(plan: CivsMinePlan, building: CivBuilding, prefix: Long, sections: Map<String, Long>) {
            for ((ordinal, step) in plan.steps.withIndex()) if (plan.completed(ordinal, prefix, sections)) {
                val p = CivsWorksiteLayouts.point(building, step.point)
                world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(plan.expectedAfter(ordinal, building.rotation))
            }
        }
        write(requireNotNull(CivsMinePlan.forBuilding(previous, 5)), previous, 1818, emptyMap())
        write(requireNotNull(CivsMinePlan.forBuilding(mine)), mine, state.nextStep, state.sectionSteps.orEmpty())
        // Exact loaded capture column: built AIR/floors/fences above the remaining natural rock.
        for ((y, data) in mapOf(66 to "minecraft:spruce_slab[type=top,waterlogged=false]", 65 to "minecraft:air", 64 to "minecraft:air",
            63 to OWNED_MINE_FENCE, 62 to "minecraft:cobblestone", 61 to "minecraft:air", 60 to "minecraft:air",
            59 to OWNED_MINE_FENCE, 58 to "minecraft:cobblestone", 57 to "minecraft:air", 56 to "minecraft:air", 55 to OWNED_MINE_FENCE))
            world.getBlockAt(-1842, y, -2686).blockData = Bukkit.createBlockData(data)
        world.getBlockAt(-1842, 51, -2685).blockData = Bukkit.createBlockData(
            "minecraft:dark_oak_fence[east=false,north=false,south=true,waterlogged=false,west=false]")
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                check(OwnedMineUpgradeFixture(paper, world, mine, state, city, runtime))
            }
        }
    }
}

/** Use the SDK's actual typed fence state; the shared captured-string fixture is intentionally untyped. */
private class OwnedMineUpgradeServerFixture : CivsWorksiteServerFixture() {
    override fun createBlockData(data: String): BlockData {
        if (!data.substringBefore('[').endsWith("_fence")) return super.createBlockData(data)
        val material = requireNotNull(Material.matchMaterial(data.substringBefore('[')))
        val properties = data.substringAfter('[').removeSuffix("]").split(',').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1].toBooleanStrict()
        }
        require(properties.keys == setOf("east", "north", "south", "west", "waterlogged"))
        return FenceDataMock(material).apply {
            isWaterlogged = properties.getValue("waterlogged")
            allowedFaces.forEach { setFace(it, properties.getValue(it.name.lowercase())) }
        }
    }
}
