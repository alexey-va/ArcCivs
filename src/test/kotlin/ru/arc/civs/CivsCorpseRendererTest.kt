package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketNpcInteraction
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsCorpseRendererTest : FunSpec({
    test("saved corpse keeps resident appearance and native equipment then reflects exact looted slots") {
        withCorpses { f ->
            verify(exactly = 1) { f.packetNpcs.enableSceneObservation() }
            val custom = ItemStack(Material.IRON_PICKAXE).apply {
                editMeta { it.displayName(Component.text("Старая кирка")) }
            }
            val items = List(CivsEquipment.SLOTS) { when (it) {
                0 -> CivResidentItem("IRON_HELMET", 1)
                4 -> CivResidentItem.of(custom)
                6 -> CivResidentItem("COBBLESTONE", 42)
                else -> null
            } }
            f.corpse = f.corpse.copy(worker = f.corpse.worker.copy(items = items))
            f.capture()
            f.npcs.size shouldBe 1
            val npc = f.npcs.single()
            val skin = citizenSkin(loadCitizenSkins(), f.corpse.worker)
            f.profiles.single().texture shouldBe skin.texture
            f.profiles.single().signature shouldBe skin.signature
            f.profiles.single().uuid shouldNotBe f.corpse.worker.id
            verify { npc.pose = PacketNpcPose.SLEEPING; npc.sleepingBed = null; npc.scale = 1.0 }
            verify { npc.setEquipment(EquipmentSlot.HEAD, match { it.type == Material.IRON_HELMET }) }
            verify { npc.setEquipment(EquipmentSlot.HAND, match { it == custom }) }
            plainText(f.labels.single()) shouldContain "Погибшая · Анна"
            plainText(f.labels.single()) shouldContain "Убийца: Зомби"
            f.capture()
            f.npcs.size shouldBe 1
            verify(exactly = 1) { npc.setEquipment(EquipmentSlot.HAND, any()) }
            f.corpse = f.corpse.copy(worker = f.corpse.worker.copy(items = items.toMutableList().also { it[0] = null; it[4] = null }))
            f.capture()
            f.npcs.size shouldBe 1
            verify { npc.setEquipment(EquipmentSlot.HEAD, match { it.type.isAir }) }
            verify { npc.setEquipment(EquipmentSlot.HAND, match { it.type.isAir }) }
            f.corpse.worker.items!![6] shouldBe CivResidentItem("COBBLESTONE", 42)
        }
    }

    test("inventory dispatch rechecks the exact saved death current membership world proximity and expiry") {
        withCorpses { f ->
            f.capture()
            val id = f.id()
            f.renderer.openIfCurrent(f.player, id)
            f.opened shouldBe listOf(f.city.id to f.corpse.worker.id)
            f.opened.clear()
            f.interact!!(f.player, f.npcs.single(), PacketNpcInteraction.ATTACK)
            f.opened shouldBe emptyList()
            f.renderer.openIfCurrent(f.player, id.copy(diedAt = id.diedAt - 1))
            f.renderer.openIfCurrent(f.player, id.copy(workerId = UUID.randomUUID()))
            f.city = f.city.copy(owner = UUID.randomUUID())
            f.renderer.openIfCurrent(f.player, id)
            f.opened shouldBe emptyList()
            f.city = f.city.copy(members = setOf(f.player.uniqueId))
            f.renderer.openIfCurrent(f.player, id)
            f.opened.size shouldBe 1
            f.opened.clear()
            f.sent = false
            f.renderer.openIfCurrent(f.player, id)
            f.sent = true
            f.player.teleport(Location(f.world, 30.0, 64.0, 0.0))
            f.renderer.openIfCurrent(f.player, id)
            f.player.teleport(Location(f.paper.addSimpleWorld("other"), 0.5, 64.0, 2.0))
            f.renderer.openIfCurrent(f.player, id)
            f.player.teleport(Location(f.world, 0.5, 64.0, 2.0))
            f.clock = f.corpse.expiresAt
            f.renderer.openIfCurrent(f.player, id)
            f.opened shouldBe emptyList()
            f.capture()
            verify(exactly = 1) { f.npcs.single().remove() }
            verify(exactly = 1) { f.handles.single().remove() }
        }
    }

    test("an airborne saved death is rendered and opened at its actual grounded position") {
        withCorpses { f ->
            f.corpse = f.corpse.copy(position = CivPosition(0, 73, 0), feetY = 73.25)
            val saved = f.corpse
            f.capture()
            f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt)!!.y shouldBe 64.0
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe listOf(f.city.id to saved.worker.id)
            f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt - 1) shouldBe null
            val detached = f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt)!!
            detached.y = 100.0
            f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt)!!.y shouldBe 64.0
            f.corpse shouldBe saved
        }
    }

    test("a wall invalidates corpse clicks immediately and the next pulse rotates the same body clear") {
        withCorpses { f ->
            f.capture()
            val saved = f.corpse
            f.world.getBlockAt(-2, 64, 0).type = Material.STONE
            f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt) shouldBe null
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe emptyList()
            f.capture()
            f.npcs.size shouldBe 1
            verify { f.npcs.single().teleport(match { it.yaw == 90f }) }
            f.renderer.resolvedLocation(f.city.id, saved.worker.id, saved.diedAt)!!.y shouldBe 64.0
            f.corpse shouldBe saved
        }
    }

    test("Auto admins may use the body while foreign personal cities retain their authority boundary") {
        withCorpses { f ->
            f.city = f.city.copy(autonomy = CivAutonomy())
            f.capture()
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe emptyList()
            f.city = f.city.copy(owner = UUID.randomUUID())
            f.player.addAttachment(f.plugin, "arccivs.admin", true)
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe listOf(f.city.id to f.corpse.worker.id)
            f.opened.clear()
            f.city = f.city.copy(autonomy = null)
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe emptyList()
        }
    }

    test("unloaded chunks inactive state and duplicate living resident cannot retain a corpse visual") {
        withCorpses { f ->
            f.loaded = false
            f.capture()
            f.npcs shouldBe emptyList()
            f.loaded = true
            f.capture()
            f.npcs.size shouldBe 1
            val chunk = mockk<Chunk> {
                every { world } returns f.world
                every { x } returns 0
                every { z } returns 0
            }
            f.renderer.onChunkUnload(ChunkUnloadEvent(chunk))
            verify(exactly = 1) { f.npcs.first().remove() }
            f.loaded = false
            f.capture()
            f.npcs.size shouldBe 1
            f.loaded = true
            f.capture()
            f.npcs.size shouldBe 2
            f.profiles[0].uuid shouldBe f.profiles[1].uuid
            f.city = f.city.copy(workers = listOf(f.corpse.worker))
            f.capture()
            verify(exactly = 1) { f.npcs[1].remove() }
            f.city = f.city.copy(workers = emptyList())
            f.capture()
            f.npcs.size shouldBe 3
            f.available = false
            f.capture()
            verify(exactly = 1) { f.npcs[2].remove() }
        }
    }

    test("close stops all new frames and waits for both provider cleanup futures") {
        withCorpses { f ->
            f.capture()
            val npcDrain = CompletableFuture<Void>()
            val displayDrain = CompletableFuture<Void>()
            every { f.packetNpcs.closeAsync() } returns npcDrain
            every { f.displays.closeAsync() } returns displayDrain
            f.renderer.close()
            f.renderer.close()
            f.renderer.drained().isDone shouldBe false
            npcDrain.complete(null)
            f.renderer.drained().isDone shouldBe false
            displayDrain.complete(null)
            f.renderer.drained().isDone shouldBe true
            f.capture()
            f.npcs.size shouldBe 1
            f.interact shouldBe null
            verify(exactly = 1) { f.packetNpcs.closeAsync() }
            verify(exactly = 1) { f.displays.closeAsync() }
        }
    }

    test("a synchronous NPC cleanup failure still waits for display cleanup and reports failure") {
        withCorpses { f ->
            f.capture()
            val displayDrain = CompletableFuture<Void>()
            every { f.packetNpcs.closeAsync() } throws IllegalStateException("NPC cleanup failed")
            every { f.displays.closeAsync() } returns displayDrain
            f.renderer.close()
            f.renderer.drained().isDone shouldBe false
            verify(exactly = 1) { f.displays.closeAsync() }
            displayDrain.complete(null)
            shouldThrow<CompletionException> { f.renderer.drained().join() }.cause!!.message shouldBe "NPC cleanup failed"
        }
    }

    test("removing a corpse closes its nameplate even when NPC removal fails") {
        withCorpses { f ->
            f.capture()
            every { f.npcs.single().remove() } throws IllegalStateException("NPC removal failed")
            val chunk = mockk<Chunk> {
                every { world } returns f.world
                every { x } returns 0
                every { z } returns 0
            }
            shouldThrow<IllegalStateException> { f.renderer.onChunkUnload(ChunkUnloadEvent(chunk)) }
            verify(exactly = 1) { f.handles.single().remove() }
            f.renderer.openIfCurrent(f.player, f.id())
            f.opened shouldBe emptyList()
        }
    }

    test("lying body controls follow adult and child geometry with no large invisible square around the body") {
        withCorpses { f ->
            val adult = requireNotNull(civsCorpsePlacement(f.world, f.corpse))
            val child = requireNotNull(civsCorpsePlacement(f.world, f.corpse.copy(worker = f.corpse.worker.copy(childMinutes = 0))))
            adult.targets.size shouldBe 2
            adult.bounds.widthX shouldBe (2.03 plusOrMinus 1e-6)
            adult.bounds.widthZ shouldBe (1.24 plusOrMinus 1e-6)
            adult.targets.all { it.native.width == 1.015f && it.native.height == .62f && it.planes.size == 6 } shouldBe true
            adult.targets.first().bounds.maxX shouldBe (adult.targets.last().bounds.minX plusOrMinus 1e-6)
            adult.targets.all { it.bounds.minY > f.corpse.position.y } shouldBe true
            child.bounds.widthX shouldBe (1.015 plusOrMinus 1e-6)
            child.bounds.widthZ shouldBe (.62 plusOrMinus 1e-6)
            child.targets.all { it.native.width == .5075f && it.native.height == .31f } shouldBe true
            adult.touches(-1, 0) shouldBe true
            adult.touches(0, 0) shouldBe true
            adult.touches(1, 0) shouldBe false
            (adult.label.y > adult.bounds.maxY) shouldBe true
            for (x in -4..4) for (z in -4..4) f.world.getBlockAt(x, 63, z).type = Material.DIRT_PATH
            val path = requireNotNull(civsCorpsePlacement(f.world, f.corpse.copy(position = CivPosition(0, 63, 0), feetY = 63.9375)))
            path.anchor.y shouldBe 63.9375
            path.bounds.minY shouldBe (adult.bounds.minY - .0625 plusOrMinus 1e-6)
            path.feet.y shouldBe (adult.feet.y - .0625 plusOrMinus 1e-6)
            path.label.y shouldBe (adult.label.y - .0625 plusOrMinus 1e-6)
            f.export(adult, child)
        }
    }

    test("public death text is forced Russian with literal player names and a localized environmental fallback") {
        withCorpses { f ->
            val text = CivsText.fromYaml(civsLocaleSource(), "en")
            plainText(civsCorpseText(text, f.corpse.copy(worker = f.corpse.worker.copy(name = "<red>Анна"),
                killerName = "<green>Alex"))) shouldBe "Погибшая · <red>Анна\nУбийца: <green>Alex"
            plainText(civsCorpseText(text, f.corpse.copy(killerType = null, cause = "FALL"))) shouldContain "Причина смерти: Падение"
            plainText(civsCorpseText(text, f.corpse.copy(killerType = "NEW_UNKNOWN_MOB"))) shouldContain "Убийца: Неизвестное существо"
            plainText(civsCorpseText(text, f.corpse.copy(killerType = null, cause = "FUTURE_CAUSE"))) shouldContain "Причина смерти: Неизвестна"
        }
    }
})

private fun withCorpses(test: (CorpseRendererFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> CorpseRendererFixture(paper).use(test) }
}

private class CorpseRendererFixture(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val plugin = paper.createSimplePlugin("CorpseRendererTest")
    val world = CivsWorksiteWorldFixture("corpses").also(paper.server::addWorld).also {
        for (x in -1..1) for (z in -1..1) it.loadChunk(x, z)
    }
    val player = paper.addPlayer("Owner").apply { teleport(Location(world, .5, 64.0, 2.0)) }
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val packetNpcs = mockk<PaperPacketNpcs>(relaxed = true)
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val space = mockk<PaperSpatialSpace>()
    val npcs = mutableListOf<PacketPlayerNpc>()
    val profiles = mutableListOf<PacketNpcProfile>()
    val handles = mutableListOf<PacketTextDisplay>()
    val labels = mutableListOf<Component>()
    val opened = mutableListOf<Pair<UUID, UUID>>()
    var interact: ((Player, PacketPlayerNpc, PacketNpcInteraction) -> Unit)? = null
    var loaded = true
    var sent = true
    var available = true
    var clock = 20_000L
    var corpse = CivsCorpses.create(CivWorker(UUID(0, 7), "Анна", null, sex = CivSex.FEMALE),
        CivDeathContext(CivPosition(0, 64, 0), 10_000L, killerType = "ZOMBIE", cause = "ENTITY_ATTACK"))
    var city = Settlement(UUID(0, 8), player.uniqueId, "Город", world.uid, CivPosition(0, 64, 0),
        64, "medieval", Stock(), emptyList(), emptyList())
    val renderer: CivsCorpseRenderer

    init {
        every { packetNpcs.onInteract = any() } answers { interact = firstArg(); Unit }
        every { packetNpcs.spawnPlayer(any(), any()) } answers {
            profiles += secondArg<PacketNpcProfile>()
            mockk<PacketPlayerNpc>(relaxed = true).also { npcs += it }
        }
        every { packetNpcs.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { displays.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { displays.spawnText(any(), any()) } answers {
            labels += secondArg<Component>()
            mockk<PacketTextDisplay>(relaxed = true).also { handle ->
                handles += handle
                every { handle.isValid } returns true
            }
        }
        every { space.known(any(), any(), any()) } answers { loaded && (thirdArg<Player?>() == null || sent) }
        every { space.clearBounds(any(), any(), any()) } answers { loaded }
        renderer = CivsCorpseRenderer(plugin, tasks, CivsText.fromYaml(civsLocaleSource(), "en"),
            { if (available) listOf(city.copy(corpses = listOf(corpse))) else emptyList() },
            { _, city, worker -> opened += city to worker }, packetNpcs, displays, { clock }, loadCitizenSkins(), space)
    }

    fun id() = CivsCorpseIdentity(city.id, corpse.worker.id, corpse.diedAt)
    fun capture() { renderer.pulse(); paper.performTicks(3) }
    override fun close() { renderer.close(); tasks.close() }

    fun export(adult: CivsCorpseGeometry, child: CivsCorpseGeometry) {
        val directory = System.getenv("CIVS_CORPSE_PREVIEW_OUT") ?: return
        fun point(at: Location) = listOf(at.x, at.y, at.z)
        fun geometry(value: CivsCorpseGeometry) = mapOf("feet" to point(value.feet), "label" to point(value.label),
            "bounds" to listOf(value.bounds.minX, value.bounds.minY, value.bounds.minZ,
                value.bounds.maxX, value.bounds.maxY, value.bounds.maxZ),
            "controls" to value.targets.map { mapOf("bottom" to point(it.native.location()),
                "width" to it.native.width, "height" to it.native.height) })
        val skin = citizenSkin(loadCitizenSkins(), corpse.worker)
        val source = Path.of("src/main/kotlin/ru/arc/civs/CivsCorpseRenderer.kt")
        val encoded = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        val data = mapOf("evidence" to "Compiled corpse geometry and text; source-derived model transform, not a native client capture",
            "sourceSha256" to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).joinToString("") { "%02x".format(it) },
            "skinTexture" to skin.texture, "pose" to "SLEEPING", "yaw" to 0, "sleepingBed" to null,
            "adult" to geometry(adult), "child" to geometry(child), "labelScale" to .5, "labelWidth" to 240,
            "text" to encoded.serialize(civsCorpseText(CivsText.fromYaml(civsLocaleSource()), corpse)))
        val out = Path.of(directory)
        Files.createDirectories(out)
        Files.writeString(out.resolve("corpse.json"), GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(data))
    }
}
