package ru.arc.civs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class CivsMineReceiptSelfConsistencyTest : FunSpec({
    test("a selected V7 Nordic second landing passes receipt validation on unchanged native blocks") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-receipt-self-consistency").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineReceiptSelfConsistency")
                val publicMine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 75, 0), 90)
                val id = "fixture/nordic/receipt-self-consistency1"
                CivsWorksiteLayouts.installCatalog(mapOf(id to receiptNordicSite(publicMine)))
                try {
                    // Actual model from 2026-11-05, translated only in X/Z and with synthetic identity.
                    val mine = publicMine.copy(schematicId = id,
                        bounds = CivBox(CivPosition(1, 72, -6), CivPosition(23, 99, 14)),
                        entrance = CivPosition(0, 76, 0), control = CivPosition(16, 76, 7))
                    val state = CivMinePlanState(7, 4, sectionSteps = mapOf("spine" to 60L),
                        excavation = CivPosition(8, -12, -12), seals = mapOf(
                            "10,-12,-11" to "minecraft:cobblestone", "10,-12,-12" to "minecraft:cobblestone",
                            "10,-13,-11" to "minecraft:cobblestone", "10,-13,-12" to "minecraft:cobblestone"))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    CivsMineProgress.valid(geometry, state, mine) shouldBe true
                    val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 73)
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(
                        inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state))
                    for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                    // Constructed natural bank, not a replay of the private native capture. Its top
                    // leaves the upper landings shallow and gives floor 2 its required six-block cover.
                    seedMineNaturalCover(world, mine, top = 70)
                    fun put(local: CivPosition, data: String) {
                        val point = CivsWorksiteLayouts.point(mine, local)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(data)
                    }
                    for ((ordinal, step) in geometry.steps.withIndex()) {
                        if (geometry.completed(ordinal, state.nextStep, state.sectionSteps.orEmpty()))
                            put(step.point, geometry.expectedAfter(ordinal, mine.rotation))
                    }
                    // Authored entrance access is native ladder/backing, not additional paid progress.
                    val ladder = geometry.sections.getValue("spine").first { geometry.steps[it].after?.startsWith("minecraft:ladder") == true }
                    for (dy in 0..2) {
                        put(geometry.bottom.copy(y = geometry.bottom.y + dy), geometry.expectedAfter(ladder, mine.rotation))
                        put(geometry.backing.copy(y = geometry.backing.y + dy), "minecraft:cobblestone")
                    }
                    state.seals.orEmpty().forEach { (key, data) ->
                        val p = key.split(',').map(String::toInt)
                        put(CivPosition(p[0], p[1], p[2]), data)
                    }
                    LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                            repeat(512) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                            val work = withClue(runtime.debug(setOf(worker.id))) { requireNotNull(runtime.plan(city, worker)) }
                            work.status shouldBe "digging-mine"
                            work.miningStep shouldBe 4L
                            geometry.section(requireNotNull(work.miningOrdinal)) shouldBe "2/shaft/landing"
                            val cell = work.changes.single()
                            CivsMinePlan.localPoint(mine, cell.position) shouldBe CivPosition(8, -11, -11)
                            cell.before shouldBe "minecraft:stone"
                            cell.after shouldBe "minecraft:air"
                            val beforeItems = worker.items?.toList()
                            // Selection and admission must agree without any intervening world/state change.
                            runtime.validate(city, work)
                            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                            city.workers.single().items shouldBe beforeItems
                            city.workers.single().completedCycles shouldBe 73L
                            city.minePlans?.get(mine.id.toString()) shouldBe state
                        }
                    }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
    }
})

/** The shipped public layout matches the curated mine after this verified half-turn and translation. */
private fun receiptNordicSite(publicMine: CivBuilding) = requireNotNull(CivsWorksiteLayouts.site(publicMine)).deepCopy().apply {
    fun point(value: JsonElement) = CivsWorksiteLayouts.vector(value).let { p ->
        JsonArray().apply { add(4 - p.x); add(p.y - 1); add(-23 - p.z) }
    }
    val mine = getAsJsonObject("mine")
    for (key in listOf("ladderBottom", "shaftCenter", "firstSubgroundPoint")) mine.add(key, point(mine[key]))
    mine.addProperty("ladderFacing", "south")
    mine.add("shaftFootprint", JsonArray().apply {
        mine.getAsJsonArray("shaftFootprint").forEach { entry ->
            add(JsonArray().apply { add(4 - entry.asJsonArray[0].asInt); add(-23 - entry.asJsonArray[1].asInt) })
        }
    })
    mine.getAsJsonArray("floors").forEach { entry ->
        val floor = entry.asJsonObject
        floor.addProperty("feetY", floor["feetY"].asInt - 1)
        floor.add("landing", point(floor["landing"]))
        for (key in listOf("floorCells", "torchCells"))
            floor.add(key, JsonArray().apply { floor.getAsJsonArray(key).forEach { add(point(it)) } })
    }
    add("schematicBounds", JsonParser.parseString("""{"min":[-6,-3,-23],"max":[14,24,-1]}"""))
}
