package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsForesterSpeciesTest : FunSpec({
    for (autonomous in listOf(false, true)) {
        test("all six species persist per forester without changing inventory or an accepted tree in Auto $autonomous") {
            val directory = Files.createTempDirectory("civs-species-store")
            try {
                CivsStore(directory, Executor(Runnable::run)).use { store ->
                    val original = forestryCity(autonomous)
                    val mill = original.buildings.single { it.type == BuildingType.FORESTER }
                    val other = mill.copy(id = UUID(0, 25), anchor = CivPosition(-20, 64, 20),
                        bounds = CivBox(CivPosition(-20, 63, 20), CivPosition(-18, 68, 22)),
                        entrance = CivPosition(-19, 64, 23), production = "ACACIA_LOG")
                    val job = speciesAcceptedOak(CivPosition(30, 64, 30))
                    var city = CivsRules.validate(original.copy(buildings = original.buildings + other,
                        forestryJobs = mapOf(mill.id.toString() to job)))
                    store.save(city).join()
                    for (material in CivsProduction.options(BuildingType.FORESTER)) {
                        city = CivsRules.setProduction(city, city.owner, mill.id, material, 1_000)
                        store.save(city).join()
                        val loaded = store.load().join().single()
                        loaded shouldBe city
                        loaded.building(mill.id)!!.production shouldBe material
                        loaded.building(other.id)!!.production shouldBe "ACACIA_LOG"
                        loaded.forestryJobs!!.getValue(mill.id.toString()) shouldBe job
                        loaded.stock shouldBe original.stock
                        loaded.inventory shouldBe original.inventory
                        loaded.workers.map { it.items to it.inventory } shouldBe original.workers.map { it.items to it.inventory }
                    }
                }
            } finally { directory.toFile().deleteRecursively() }
        }

        test("selected birch reaches native planting and harvesting after an oak search in Auto $autonomous") {
            var supplied = if (autonomous) 1L else 0L
            forestryWorksite("species-flow-$autonomous", autonomous = autonomous,
                supplies = { if (supplied > 0) mapOf("BIRCH_SAPLING" to supplied) else emptyMap() }, strict = true) { world, runtime, original ->
                val mill = original.buildings.single { it.type == BuildingType.FORESTER }
                // A warmed oak search must not retain its species after selection.
                runtime.pulse(listOf(original))
                val city = CivsRules.setProduction(original, original.owner, mill.id, "BIRCH_LOG", 1_000)
                if (!autonomous) {
                    repeat(100) {
                        runtime.pulse(listOf(city))
                        runtime.plan(city, city.workers.single()) shouldBe null
                    }
                    supplied = 1
                }
                val planted = speciesNext(runtime, city)
                planted.status shouldBe "planting-tree"
                planted.inputs shouldBe mapOf("BIRCH_SAPLING" to 1L)
                planted.changes.single().after shouldBe "minecraft:birch_sapling[stage=0]"
                runtime.validate(city, planted)
                planted.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                    .blockData = Bukkit.createBlockData(cell.after) }
                world.getBlockAt(planted.focus!!.x, planted.focus.y, planted.focus.z).type shouldBe Material.BIRCH_SAPLING

                // The fixture supplies a grown native tree; it does not simulate Minecraft growth.
                val root = planted.focus
                for (y in root.y..root.y + 2) world.getBlockAt(root.x, y, root.z).type = Material.BIRCH_LOG
                world.getBlockAt(root.x, root.y + 3, root.z).blockData = Bukkit.createBlockData(
                    "minecraft:birch_leaves[distance=1,persistent=false,waterlogged=false]")
                runtime.invalidate(city.workers.single().id)
                runtime.changed(world.uid, root)
                val harvested = speciesNext(runtime, city)
                harvested.status shouldBe "felling-tree"
                harvested.forestry!!.job.timber shouldBe "BIRCH_LOG"
                harvested.forestry.job.sapling shouldBe "BIRCH_SAPLING"
                harvested.forestry.job.roots shouldBe listOf(root)
                harvested.changes.any { it.before.startsWith("minecraft:birch_log") } shouldBe true
                harvested.changes.none { it.before.startsWith("minecraft:oak_log") } shouldBe true
                runtime.validate(city, harvested)
            }
        }

        test("a saved oak job finishes after selecting birch and keeps its original replant in Auto $autonomous") {
            forestryWorksite("species-accepted-$autonomous", autonomous = autonomous, strict = true) { world, runtime, original ->
                val mill = original.buildings.single { it.type == BuildingType.FORESTER }
                val root = CivPosition(12, 64, 12)
                val job = speciesAcceptedOak(root)
                world.getBlockAt(root.x, root.y, root.z).type = Material.OAK_LOG
                val accepted = CivsRules.validate(original.copy(forestryJobs = mapOf(mill.id.toString() to job)))
                val selected = CivsRules.setProduction(accepted, accepted.owner, mill.id, "BIRCH_LOG", 1_000)
                val work = speciesNext(runtime, selected)
                work.forestry!!.job shouldBe job
                work.inputs shouldBe emptyMap()
                work.changes.single().after shouldBe "minecraft:oak_sapling[stage=0]"
                runtime.validate(selected, work)
                val finished = CivsRules.finishWorldWork(selected, work, 2_000)
                finished.forestryJobs shouldBe null
                CivsProduction.material(finished.building(mill.id)!!) shouldBe "BIRCH_LOG"
            }
        }

        test("service accepts the selected city and rejects unavailable species non-foresters and unauthorized actors in Auto $autonomous") {
            speciesService(autonomous) { h ->
                h.await(h.service.setForesterSpecies(h.player, h.city.id, h.mill.id, "BIRCH_LOG"))
                h.current().building(h.mill.id)!!.production shouldBe "BIRCH_LOG"
                val before = h.current()
                h.rejection(h.service.setForesterSpecies(h.player, h.city.id, h.mill.id, "CHERRY_LOG")) shouldBe "invalid-production"
                h.rejection(h.service.setForesterSpecies(h.player, h.city.id, h.city.buildings.first().id, "BIRCH_LOG")) shouldBe "invalid-production"
                h.rejection(h.service.setForesterSpecies(h.player, UUID.randomUUID(), h.mill.id, "BIRCH_LOG")) shouldBe "no-city"
                val outsider = h.paper.addPlayer("Visitor")
                outsider.addAttachment(h.plugin, "arccivs.use", true)
                outsider.teleport(Location(h.world, 0.0, 64.0, 0.0))
                h.rejection(h.service.setForesterSpecies(outsider, h.city.id, h.mill.id, "SPRUCE_LOG")) shouldBe "not-authorized"
                h.current() shouldBe before
                if (autonomous) {
                    h.player.teleport(Location(h.paper.addSimpleWorld("elsewhere"), 0.0, 64.0, 0.0))
                    h.rejection(h.service.setForesterSpecies(h.player, h.city.id, h.mill.id, "SPRUCE_LOG")) shouldBe "not-authorized"
                    h.current() shouldBe before
                }
            }
        }

        for (language in listOf("ru", "en")) test("the $language building flow exposes a stable species choice in Auto $autonomous") {
            speciesUi(autonomous, language) { h ->
                if (autonomous) h.ui.openBuildingInspection(h.player, h.city.id, h.mill.id)
                else h.ui.openBuilding(h.player, h.city.id, h.mill.id)
                speciesPlain(h.screen.buttons.single { it.id.value == "production" }.label) shouldContain
                    if (language == "ru") "Порода деревьев" else "Tree species"
                h.click("production")
                h.screen.id shouldBe "civs.forester-species"
                h.screen.buttons.count { it.id.value.startsWith("production_") } shouldBe 6
                h.click("production_2")
                h.paper.performTicks(2)
                h.city.building(h.mill.id)!!.production shouldBe "BIRCH_LOG"
                speciesPlain(h.screen.buttons.single { it.id.value == "production_2" }.label) shouldBe
                    if (language == "ru") "✔ Берёза" else "✔ Birch"
                if (autonomous) verify(exactly = 1) { h.host.setForesterSpecies(h.player, h.city.id, h.mill.id, "BIRCH_LOG") }
                else verify(exactly = 1) { h.host.setProduction(h.player, h.mill.id, "BIRCH_LOG") }
            }
        }
    }

    test("queued Auto species changes recheck administrator permission before persistence") {
        speciesService(true) { h ->
            val pending = CompletableFuture<Settlement>()
            var saving: Settlement? = null
            every { h.store.save(any()) } answers { saving = firstArg(); pending }
            val first = h.service.setForesterSpecies(h.player, h.city.id, h.mill.id, "BIRCH_LOG")
            val queued = h.service.setForesterSpecies(h.player, h.city.id, h.mill.id, "SPRUCE_LOG")
            h.admin = false
            pending.complete(requireNotNull(saving))
            h.await(first)
            h.rejection(queued) shouldBe "not-authorized"
            h.current().building(h.mill.id)!!.production shouldBe "BIRCH_LOG"
        }
    }

    test("ordinary visitors can inspect Auto forester species without receiving a mutation control") {
        speciesUi(true, "ru") { h ->
            h.admin = false
            h.ui.openBuildingInspection(h.player, h.city.id, h.mill.id)
            h.screen.buttons.none { it.id.value == "production" } shouldBe true
            h.screen.body.joinToString("\n") { speciesPlain(it.text) } shouldContain "Ель"
            verify(exactly = 0) { h.host.setForesterSpecies(any(), any(), any(), any()) }
        }
    }
})

private fun speciesAcceptedOak(root: CivPosition): CivForestryJob = CivForestryJob(UUID.randomUUID(), root, "OAK_SAPLING",
    listOf(root), listOf("minecraft:oak_log[axis=y]"), listOf(15 + (15 shl 5) + (1 shl 10), 15 + (15 shl 5)), next = 1,
    harvestedLogs = 1)

private fun speciesNext(runtime: CivsWorksites, city: Settlement): CivWorldWork {
    repeat(500) {
        runtime.pulse(listOf(city))
        runtime.plan(city, city.workers.single())?.let { return it }
    }
    error("Selected forester produced no work: ${runtime.debug()}")
}

private fun speciesService(autonomous: Boolean, action: (SpeciesService) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> SpeciesService(paper, autonomous).use(action) }
}

private class SpeciesService(val paper: MockBukkitTestRuntime, autonomous: Boolean) : AutoCloseable {
    val plugin = paper.createSimplePlugin("ForesterSpecies")
    val world = paper.addSimpleWorld("vanilla")
    val player = paper.addPlayer("Mayor")
    private val permissions = player.addAttachment(plugin)
    var admin = true
        set(value) { field = value; permissions.setPermission("arccivs.admin", value) }
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val store = mockk<CivsStore>()
    val city = forestryCity(autonomous).copy(worldId = world.uid, owner = player.uniqueId,
        memberNames = mapOf(player.uniqueId.toString() to player.name), settings = CivSettlementSettings(chunkLoading = false),
        autonomy = if (autonomous) CivAutonomy(paused = true) else null)
    val mill = city.buildings.single { it.type == BuildingType.FORESTER }
    val service: CivsService

    init {
        player.teleport(Location(world, 0.0, 64.0, 0.0))
        permissions.setPermission("arccivs.use", true)
        permissions.setPermission("arccivs.admin", true)
        every { store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        every { store.loadPlan(any()) } returns CompletableFuture.completedFuture(null)
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.homeServer() } returns null
        val citizens = mockk<CivsCitizens>(relaxed = true)
        every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
        val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
        every { boundary.drained() } returns CompletableFuture.completedFuture(null)
        service = CivsService(plugin, tasks, CivsSettings(Path.of("/tmp/civs-unused"), setOf(world.name), 96,
            500, 50_000, 4, 120, 48, 96), store, mockk(relaxed = true), Executor(Runnable::run),
            worldAdapter = mockk(relaxed = true), previewRenderer = renderer, citizenRuntime = citizens,
            boundaryRenderer = boundary, storageAdapter = CivsStorage(plugin) { emptyArray() }, supplyExecutor = Executor(Runnable::run))
        await(service.start())
    }

    fun current(): Settlement = service.settlements().single()
    fun <T> await(future: CompletableFuture<T>): T {
        repeat(100) { if (future.isDone) return future.join(); paper.performTicks(1) }
        error("Species service future did not complete")
    }
    fun rejection(future: CompletableFuture<*>): String? = CivsDiagnostics.rejection(runCatching { await(future) }.exceptionOrNull())?.reason
    override fun close() { service.close(); tasks.close() }
}

private fun speciesPlain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

private fun speciesUi(autonomous: Boolean, language: String, action: (SpeciesUi) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    SpeciesUi(paper, autonomous, language).use(action)
}

private class SpeciesUi(val paper: MockBukkitTestRuntime, autonomous: Boolean, language: String) : AutoCloseable {
    val plugin = paper.createSimplePlugin("ForesterSpeciesUi")
    val player = spyk(paper.addPlayer("Mayor")).apply { setLocale(Locale.forLanguageTag(language)) }
    var admin = true
    var city = forestryCity(autonomous).copy(owner = player.uniqueId)
    val mill = city.buildings.single { it.type == BuildingType.FORESTER }
    val host = mockk<CivsUiHost>(relaxed = true)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val runtime = mockk<PaperDialogRuntime>(relaxed = true)
    lateinit var screen: PaperDialogScreen
    val ui: CivsUi

    init {
        every { player.hasPermission("arccivs.admin") } answers { admin }
        every { host.ready() } returns true
        every { host.city(any()) } answers { city }
        every { host.settlements() } answers { listOf(city) }
        every { host.inspectBuilding(any(), any(), any()) } answers { city }
        every { host.buildingRequest(any(), any()) } returns null
        every { host.cityBusy(any()) } returns false
        every { host.setProduction(any(), any(), any()) } answers {
            city = CivsRules.setProduction(city, player.uniqueId, secondArg(), thirdArg(), 1_000)
            CompletableFuture.completedFuture(Unit)
        }
        every { host.setForesterSpecies(any(), any(), any(), any()) } answers {
            city = CivsRules.setProduction(city, city.owner, arg(2), arg(3), 1_000)
            CompletableFuture.completedFuture(Unit)
        }
        every { runtime.open(any(), any(), any(), any(), any()) } answers { screen = secondArg(); Unit }
        ui = CivsUi(plugin, host, tasks, runtime, CivsText.fromYaml(civsLocaleSource()))
    }

    fun click(id: String) {
        screen.buttons.single { it.id.value == id }.onClick.handle(mockk<PaperDialogClickContext> {
            every { player } returns this@SpeciesUi.player
            every { text(any()) } returns null
        })
    }
    override fun close() { ui.close(); tasks.close() }
}
