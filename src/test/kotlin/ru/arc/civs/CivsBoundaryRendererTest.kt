package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture

class CivsBoundaryRendererTest : FunSpec({
    test("holograms use saved controls and retain only the nearest four within sixteen blocks") {
        val site = fenceBuilding()
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID()).copy(buildings = List(8) { index ->
            site.copy(id = UUID(0, index.toLong()), control = CivPosition(index * 3, 64, 0))
        } + site.copy(id = UUID(1, 0), control = null))
        var labels = 0
        val parts = civsControlHolograms(city, 0.5, 64.0, 0.5) { labels++; Component.text(it.id.toString()) }
        parts.map { it.buildingId } shouldBe (0L..3L).map { UUID(0, it) }
        labels shouldBe 4
        parts.first().let { listOf(it.x, it.y, it.z) } shouldBe listOf(0.5, 65.3, 0.5)
        val planned = site.copy(control = CivPosition(100, 90, 100))
        val order = CivConstruction(UUID.randomUUID(), site.id, 2, 100, 40, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = planned)
        val upgrading = city.copy(buildings = listOf(site.copy(phase = BuildingPhase.ACTIVE)), construction = order)
        civsControlHolograms(upgrading, 5.5, 65.0, 5.5) { Component.empty() }.single().let { listOf(it.x, it.y, it.z) } shouldBe
            listOf(5.5, 66.3, 5.5)
        civsControlHolograms(upgrading, 100.0, 90.0, 100.0) { Component.empty() } shouldBe emptyList()
    }

    test("control holograms update state and locale in place and retire with their saved building") {
        boundaryScenario(labels = true) { h ->
            h.player.teleport(Location(h.player.world, 5.5, 64.0, 1.5))
            h.player.setLocale(Locale.US)
            val building = fenceBuilding().copy(phase = BuildingPhase.ACTIVE)
            val city = h.city.copy(buildings = listOf(building))
            val entities = h.player.world.entities.size
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            h.labelHandles.size shouldBe 1
            val label = h.labelHandles.single()
            h.player.world.entities.size shouldBe entities
            plainText(h.labelText.getValue(label)) shouldBe "Town hall\nLevel 1\nReady"
            verify(exactly = 1) { label.isVisibleByDefault = false; label.showTo(h.player); label.isSeeThrough = false }
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            verify(exactly = 0) { label.text(any()); label.teleport(any()) }
            val order = CivConstruction(UUID.randomUUID(), building.id, 2, 100, 40, ConstructionPhase.WAITING_MATERIALS, Stock())
            h.renderer.pulse(h.player, city.copy(construction = order)); h.paper.performTicks(2)
            plainText(h.labelText.getValue(label)) shouldBe "Town hall\nLevel 1\nWaiting for materials"
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.renderer.pulse(h.player, city.copy(construction = order)); h.paper.performTicks(2)
            plainText(h.labelText.getValue(label)) shouldBe "Ратуша\nУр. 1\nОжидает материалы"
            h.labelHandles.size shouldBe 1
            val relocated = city.copy(buildings = listOf(building.copy(control = CivPosition(6, 65, 5))))
            h.renderer.pulse(h.player, relocated); h.paper.performTicks(2)
            verify(exactly = 1) { label.teleport(match { it.x == 6.5 && it.y == 66.3 && it.z == 5.5 }) }
            h.renderer.pulse(h.player, relocated.copy(buildings = emptyList())); h.paper.performTicks(2)
            h.labelRemaining.size shouldBe 0
            verify(exactly = 1) { label.remove() }
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            h.renderer.pulse(h.player, null); h.paper.performTicks(2)
            h.labelRemaining.size shouldBe 0
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            h.renderer.onQuit(mockk { every { player } returns h.player }); h.paper.performTicks(2)
            h.labelRemaining.size shouldBe 0
            h.labelHandles.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    test("hologram export uses the runtime text and placement at the actual central barrel") {
        boundaryScenario(labels = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val building = fenceBuilding().copy(control = CivPosition(0, 64, 0))
            val base = h.city.copy(buildings = listOf(building))
            val waiting = base.copy(construction = CivConstruction(UUID(0, 6), building.id, 1, 100, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock()), buildings = listOf(building.copy(phase = BuildingPhase.CONSTRUCTING)))
            val farm = building.copy(type = BuildingType.FARM, level = 2, phase = BuildingPhase.ACTIVE)
            val working = base.copy(buildings = listOf(farm), workers = listOf(CivWorker(UUID(0, 7), "Worker", farm.id)))
            val catalog = CivsText.fromYaml(civsLocaleSource())
            val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
            val scenes = listOf("site-ru" to base, "waiting-ru" to waiting, "working-en" to working).map { (name, city) ->
                if (name.endsWith("en")) h.player.setLocale(Locale.US)
                val label = civsControlHolograms(city, 0.5, 64.0, -3.5) { catalog.buildingHologram(h.player, city, it) }.single()
                mapOf("id" to name, "eye" to listOf(0.5, 65.62, -3.5), "target" to listOf(0.5, 65.2, 0.5),
                    "groundY" to 64, "control" to building.control, "position" to listOf(label.x, label.y, label.z),
                    "text" to legacy.serialize(label.text), "scale" to CIVS_CONTROL_TEXT_SCALE, "lineWidth" to CIVS_CONTROL_TEXT_WIDTH,
                    "backgroundArgb" to listOf(150, 15, 23, 30))
            }
            System.getenv("CIVS_BOUNDARY_PREVIEW_OUT")?.let { destination ->
                val out = Path.of(destination)
                Files.createDirectories(out)
                val hashes = listOf("src/main/kotlin/ru/arc/civs/CivsBoundaryRenderer.kt", "src/main/kotlin/ru/arc/civs/CivsText.kt",
                    "src/main/resources/locales.yml").associateWith { source ->
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(source))).joinToString("") { "%02x".format(it) }
                }
                Files.writeString(out.resolve("holograms.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
                    "evidence" to "Compiled control label text and geometry; explicit barrel/flat-ground fixture, no native-client capture.",
                    "sourceSha256" to hashes, "scenes" to scenes)))
            }
        }
    }

    test("border follows the outer faces of inclusive territory without integer overflow") {
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID())
        civsBoundaryBounds(city) shouldBe CivsBoundaryBounds(-96, 97, -96, 97)
        val shifted = city.copy(center = CivPosition(-123, 64, -67), radius = 128)
        civsBoundaryBounds(shifted) shouldBe CivsBoundaryBounds(-251, 6, -195, 62)
        shifted.contains(5, 61) shouldBe true
        shifted.contains(6, 61) shouldBe false
        civsBoundaryBounds(city.copy(center = CivPosition(Int.MAX_VALUE, 64, Int.MIN_VALUE), radius = Int.MAX_VALUE)) shouldBe
            CivsBoundaryBounds(0, 4_294_967_295L, -4_294_967_295L, 0)
    }

    test("only nearby fixed segments are sampled and cost does not follow the territory area") {
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID())
        civsBoundaryGeometry(city, 0.5, 64.0, 0.5) shouldBe emptyList()
        val edge = civsBoundaryGeometry(city, 85.5, 64.0, 0.5)
        edge.size shouldBe 15
        edge.map { it.key.side }.toSet() shouldBe setOf(CivsBoundarySide.EAST)
        civsBoundaryGeometry(city, 86.5, 64.4, 1.5) shouldBe edge
        for (radius in listOf(1, 96, 128, 1_000_000, Int.MAX_VALUE)) {
            val candidate = city.copy(radius = radius)
            val parts = civsBoundaryGeometry(candidate, radius - 4.0, 125.0, radius - 4.0)
            (parts.size <= 60) shouldBe true
            parts.map { it.key }.distinct().size shouldBe parts.size
            parts.all { it.cuboid.sx > 0 && it.cuboid.sy > 0 && it.cuboid.sz > 0 } shouldBe true
            parts.all { it.cuboid.y == 125.12 } shouldBe true
        }
    }

    test("corner posts are unique and bars join without overlapping coplanar faces") {
        val parts = civsBoundaryGeometry(boundaryCity(UUID.randomUUID(), UUID.randomUUID()), 89.0, 64.0, 89.0)
        parts.map { it.key.side }.toSet() shouldBe setOf(CivsBoundarySide.EAST, CivsBoundarySide.SOUTH)
        val posts = parts.filter { it.key.post }.map { it.cuboid.x to it.cuboid.z }
        posts.distinct().size shouldBe posts.size
        for ((index, a) in parts.withIndex()) for (b in parts.drop(index + 1)) {
            val x = minOf(a.cuboid.x + a.cuboid.sx, b.cuboid.x + b.cuboid.sx) - maxOf(a.cuboid.x, b.cuboid.x)
            val y = minOf(a.cuboid.y + a.cuboid.sy, b.cuboid.y + b.cuboid.sy) - maxOf(a.cuboid.y, b.cuboid.y)
            val z = minOf(a.cuboid.z + a.cuboid.sz, b.cuboid.z + b.cuboid.sz) - maxOf(a.cuboid.z, b.cuboid.z)
            (x > 0.00001 && y > 0.00001 && z > 0.00001) shouldBe false
        }
    }

    test("site fence follows the footprint above buried foundations and leaves its entrance open") {
        val site = fenceBuilding()
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID()).copy(buildings = listOf(site))
        val parts = civsConstructionFenceGeometry(city, 5.5, 64.0, -5.5)
        parts.isNotEmpty() shouldBe true
        parts.map { it.key.side }.toSet() shouldBe CivsBoundarySide.entries.toSet()
        parts.map { it.key }.distinct().size shouldBe parts.size
        parts.all { it.key.buildingId == site.id && it.cuboid.material == Material.OAK_PLANKS } shouldBe true
        parts.all { it.cuboid.y >= 64.0 && it.cuboid.y + it.cuboid.sy <= 65.25 } shouldBe true
        val north = parts.filter { it.key.side == CivsBoundarySide.NORTH }
        // A clear two-block walking line at the authored entrance, not a gate drawn across it.
        north.none { it.cuboid.x < 6.375 && it.cuboid.x + it.cuboid.sx > 4.625 } shouldBe true
        parts.filter { it.key.post }.map { it.cuboid.x to it.cuboid.z }.distinct().size shouldBe parts.count { it.key.post }
        civsConstructionFenceGeometry(city.copy(buildings = listOf(site.copy(phase = BuildingPhase.ACTIVE))), 5.5, 64.0, -5.5) shouldBe emptyList()
        civsConstructionFenceGeometry(city, 5.5, 96.0, -5.5) shouldBe emptyList()
    }

    test("waiting upgrades use their planned footprint and the complete viewer fence budget is bounded") {
        val old = fenceBuilding().copy(phase = BuildingPhase.ACTIVE)
        val planned = old.copy(bounds = old.bounds.copy(max = CivPosition(17, 80, 15)), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), old.id, 2, 100, 20, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = planned)
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID()).copy(buildings = listOf(old), construction = order)
        val parts = civsConstructionFenceGeometry(city, 5.5, 64.0, -5.5)
        parts.filter { it.key.side == CivsBoundarySide.EAST }.all { it.cuboid.x > 17.0 } shouldBe true
        parts shouldBe civsConstructionFenceGeometry(city.copy(construction = order.copy(phase = ConstructionPhase.CONSTRUCTING)), 5.5, 64.0, -5.5)
        civsConstructionFenceGeometry(city.copy(construction = null), 5.5, 64.0, -5.5) shouldBe emptyList()
        val many = city.copy(construction = null, buildings = List(16) { index -> planned.copy(id = UUID(0, index.toLong()),
            bounds = CivBox(CivPosition(-1_000_000, -40, -1_000_000), CivPosition(1_000_000, 80, 1_000_000))) })
        val bounded = civsConstructionFenceGeometry(many, 999_998.5, 64.0, 999_998.5)
        bounded.size shouldBe CIVS_FENCE_PART_LIMIT
        bounded.map { it.key }.distinct().size shouldBe bounded.size
    }

    test("site fences reuse stationary handles and retire on completion or city deletion without world blocks") {
        boundaryScenario { h ->
            h.player.teleport(Location(h.player.world, 5.5, 64.0, -5.5))
            val site = fenceBuilding()
            val city = h.city.copy(buildings = listOf(site))
            val expected = civsConstructionFenceGeometry(city, 5.5, 64.0, -5.5).size
            val nativeEntities = h.player.world.entities.size
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            h.remaining.size shouldBe expected
            h.player.world.entities.size shouldBe nativeEntities
            h.player.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            val order = CivConstruction(UUID.randomUUID(), site.id, 1, 100, 20, ConstructionPhase.WAITING_MATERIALS, Stock())
            h.renderer.pulse(h.player, city.copy(buildings = listOf(site.copy(phase = BuildingPhase.CONSTRUCTING)), construction = order))
            h.paper.performTicks(2)
            h.handles.size shouldBe expected
            h.handles.forEach { verify(exactly = 0) { it.teleport(any()) } }
            h.renderer.pulse(h.player, city.copy(buildings = listOf(site.copy(phase = BuildingPhase.ACTIVE))))
            h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            h.renderer.pulse(h.player, city); h.paper.performTicks(2)
            h.renderer.pulse(h.player, null); h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            h.handles.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    test("stationary membership retains private packet handles and leaving the edge removes them") {
        boundaryScenario { h ->
            val nativeEntities = h.player.world.entities.size
            h.renderer.pulse(h.player, h.city)
            h.handles.size shouldBe 0
            h.paper.performTicks(2)
            h.handles.size shouldBe 15
            h.player.world.entities.size shouldBe nativeEntities
            h.handles.forEach { handle ->
                verify(exactly = 1) { handle.isVisibleByDefault = false; handle.showTo(h.player) }
                verify(exactly = 0) { handle.teleport(any()) }
            }
            repeat(4) { h.renderer.pulse(h.player, h.city); h.paper.performTicks(2) }
            h.handles.size shouldBe 15
            h.handles.forEach { verify(exactly = 0) { it.teleport(any()) } }
            h.player.teleport(Location(h.player.world, 0.5, 64.0, 0.5))
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            h.renderer.clear(h.player.uniqueId)
            h.handles.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    test("shared native budget bounds concurrent viewers and coalesces an obsolete boundary") {
        boundaryScenario(budget = 2) { h ->
            val member = h.paper.addPlayer("Member").apply { teleport(h.player.location) }
            val city = h.city.copy(members = setOf(member.uniqueId))
            h.renderer.pulse(h.player, city)
            h.renderer.pulse(member, city)
            h.paper.performTicks(1)
            h.handles.size shouldBe 2
            h.renderer.pulse(h.player, city.copy(radius = 128)) // New edge is too far away; retire the partial scene.
            h.paper.performTicks(30)
            h.remaining.size shouldBe 15
            h.handles.filter { it in h.remaining }.forEach { verify(exactly = 1) { it.showTo(member) } }
            h.renderer.pulse(member, city.copy(members = emptySet()))
            h.paper.performTicks(20)
            h.remaining.size shouldBe 0
        }
    }

    test("world changes clear private scenes and close retains the display owner's actual drain") {
        boundaryScenario { h ->
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            val other = h.paper.addSimpleWorld("other-boundary")
            h.player.teleport(Location(other, 85.5, 64.0, 0.5))
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            val closing = CompletableFuture<Void>()
            every { h.displays.closeAsync() } returns closing
            h.renderer.close()
            h.renderer.drained().isDone shouldBe false
            closing.complete(null)
            h.renderer.drained().isDone shouldBe true
            h.renderer.close()
            verify(exactly = 1) { h.displays.closeAsync() }
        }
    }

    test("export uses the same near-edge and corner geometry as the runtime") {
        val city = boundaryCity(UUID(0, 1), UUID(0, 2))
        val scenes = listOf(
            Triple("edge", listOf(85.5, 65.62, 0.5), listOf(97.0, 65.2, 0.5)),
            Triple("corner", listOf(88.5, 65.62, 88.5), listOf(97.0, 65.2, 97.0)),
        ).map { (name, eye, target) -> mapOf(
            "id" to name, "eye" to eye, "target" to target, "groundY" to 64,
            "cues" to civsBoundaryGeometry(city, eye[0], eye[1] - 1.62, eye[2]).map { it.cuboid },
        ) } + listOf(
            Triple("site-fence", listOf(5.5, 65.62, -7.5), listOf(5.5, 64.7, 4.0)),
            Triple("site-fence-corner", listOf(-7.5, 65.62, -6.5), listOf(4.0, 64.6, 4.0)),
        ).map { (name, eye, target) -> mapOf(
            "id" to name, "eye" to eye, "target" to target, "groundY" to 64,
            "cues" to civsConstructionFenceGeometry(city.copy(buildings = listOf(fenceBuilding())), eye[0], eye[1] - 1.62, eye[2]).map { it.cuboid },
        ) }
        System.getenv("CIVS_BOUNDARY_PREVIEW_OUT")?.let { destination ->
            val out = Path.of(destination)
            Files.createDirectories(out)
            val source = Path.of("src/main/kotlin/ru/arc/civs/CivsBoundaryRenderer.kt")
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).joinToString("") { "%02x".format(it) }
            Files.writeString(out.resolve("scene.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Compiled canonical boundary geometry; explicit flat terrain fixture, no native-client rendering.",
                "sourceSha256" to mapOf(source.toString() to hash), "scenes" to scenes)))
        }
    }
})

private fun boundaryScenario(budget: Int = 512, labels: Boolean = false, action: (BoundaryHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val h = BoundaryHarness(paper, budget, labels)
        try { action(h) } finally { h.renderer.close(); h.work.close(); h.tasks.close() }
    }
}

private class BoundaryHarness(val paper: MockBukkitTestRuntime, budget: Int, labels: Boolean) {
    val plugin = paper.createSimplePlugin("CivsBoundaryTest")
    val player = paper.addPlayer("Mayor").apply { teleport(Location(world, 85.5, 64.0, 0.5)) }
    val city = boundaryCity(player.world.uid, player.uniqueId)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val work = BudgetedTaskQueue(tasks, maximumSteps = budget, nanoTime = { 0 })
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val handles = mutableListOf<PacketBlockDisplay>()
    val remaining = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PacketBlockDisplay, Boolean>())
    val labelHandles = mutableListOf<PacketTextDisplay>()
    val labelText = java.util.IdentityHashMap<PacketTextDisplay, Component>()
    val labelRemaining = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PacketTextDisplay, Boolean>())
    val renderer: CivsBoundaryRenderer

    init {
        every { displays.spawnBlock(any(), any()) } answers {
            mockk<PacketBlockDisplay>(relaxed = true).also { handle ->
                handles += handle
                remaining += handle
                every { handle.remove() } answers { remaining.remove(handle); Unit }
            }
        }
        every { displays.closeAsync() } answers {
            remaining.toList().forEach { it.remove() }
            labelRemaining.toList().forEach { it.remove() }
            CompletableFuture.completedFuture(null)
        }
        every { displays.spawnText(any(), any()) } answers {
            val initial = secondArg<Component>()
            mockk<PacketTextDisplay>(relaxed = true).also { handle ->
                labelHandles += handle
                labelRemaining += handle
                labelText[handle] = initial
                every { handle.text(any()) } answers { labelText[handle] = firstArg(); Unit }
                every { handle.remove() } answers { labelRemaining.remove(handle); Unit }
            }
        }
        renderer = CivsBoundaryRenderer(plugin, tasks, displays, work, if (labels) CivsText.fromYaml(civsLocaleSource()) else null)
    }
}

private fun boundaryCity(worldId: UUID, owner: UUID): Settlement = Settlement(UUID(1, 1), owner, "Boundary", worldId,
    CivPosition(0, 64, 0), 96, "fixture", Stock(), emptyList(), emptyList())

private fun fenceBuilding() = CivBuilding(UUID(0, 5), BuildingType.TOWN_HALL, 1,
    CivPosition(0, 60, 0), 0, "fixture/hall", CivBox(CivPosition(0, 60, 0), CivPosition(11, 72, 11)),
    CivPosition(5, 64, -1), BuildingPhase.SITE, control = CivPosition(5, 65, 5))
