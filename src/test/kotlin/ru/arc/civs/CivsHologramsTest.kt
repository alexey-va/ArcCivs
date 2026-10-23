package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.util.Transformation
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture

class CivsHologramsTest : FunSpec({
    test("packet carriers move nameplates without a second teleport follower and despawn removes the label") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CivsHologramsTest")
            val player = paper.addPlayer("Viewer").apply { setLocale(Locale.forLanguageTag("ru-RU")) }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val budget = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0 })
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val handles = mutableListOf<PacketTextDisplay>()
            val spawned = mutableListOf<Location>()
            val poses = java.util.IdentityHashMap<PacketTextDisplay, Transformation>()
            every { displays.spawnText(any(), any()) } answers {
                spawned += firstArg<Location>().clone()
                mockk<PacketTextDisplay>(relaxed = true).also { handle ->
                    handles += handle
                    every { handle.transformation = any() } answers { poses[handle] = firstArg(); Unit }
                }
            }
            every { displays.closeAsync() } returns CompletableFuture.completedFuture(null)
            val carrier = mockk<PacketPlayerNpc> { every { isValid } returns true }
            val npc = CivsNpcHologramSnapshot(UUID(0, 1), player.world.uid, 2.0, 64.0, 3.0, "Василий", "working", 18.5, 20.0,
                actor = carrier, profession = "farmer")
            var values = listOf(npc, npc.copy(workerId = UUID(0, 2), x = 5.0))
            val renderer = CivsHolograms(plugin, tasks, CivsText.fromYaml(civsLocaleSource()), { values }, displays, budget)
            try {
                renderer.pulse()
                paper.performTicks(1)
                handles.size shouldBe 1
                paper.performTicks(3)
                handles.size shouldBe 2
                spawned.first().y shouldBe 65.8
                verify { handles.first().billboard = Display.Billboard.CENTER; handles.first().viewRange = 1f }
                verify { handles.first().transformation = match {
                    kotlin.math.abs(spawned.first().y + it.translation.y - (npc.y + 2.00)) < 0.00001 &&
                        kotlin.math.abs(it.translation.y - .20f) < .00001f
                } }
                verify { handles.first().attachTo(carrier) }
                values = listOf(npc.copy(x = 2.5, status = "walking", health = 17.0), values[1])
                renderer.pulse(); paper.performTicks(3)
                handles.size shouldBe 2
                verify { handles.first().text(any()) }
                verify(exactly = 0) { handles.first().teleport(any()) }
                values = listOf(values.first().copy(x = 30.0), values[1])
                renderer.pulse(); paper.performTicks(3)
                verify { handles.first().teleportDuration = 0 }
                verify(exactly = 0) { handles.first().teleport(any()) }
                // Pose and child scale come from the actual carrier, not the status string.
                for ((height, bottom) in listOf(1.5 to 1.7, .2 to .4, .9 to 1.1, .75 to .95, 1.8 to 2.0)) {
                    values = listOf(values.first().copy(nativeHeight = height), values[1])
                    renderer.pulse(); paper.performTicks(3)
                    val pose = poses.getValue(handles.first())
                    (kotlin.math.abs(height + pose.translation.y - bottom) < .00001) shouldBe true
                    pose.scale.x shouldBe .55f
                }
                handles.size shouldBe 2
                verify(exactly = 0) { handles.first().teleport(any()) }
                values = listOf(values[1])
                renderer.pulse(); paper.performTicks(3)
                verify(exactly = 1) { handles.first().remove() }
                renderer.close()
                paper.performTicks(8)
                verify(exactly = 1) { displays.closeAsync() }
                handles.size shouldBe 2
            } finally { renderer.close(); budget.close(); tasks.close() }
        }
    }

    test("nameplates use literal actor name observed action and actual health without a percentage") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Viewer").apply { setLocale(Locale.forLanguageTag("ru-RU")) }
            val text = CivsText.fromYaml(civsLocaleSource())
            val npc = CivsNpcHologramSnapshot(UUID(0, 1), player.world.uid, 0.0, 64.0, 0.0, "Василий", "working", 18.5, 20.0,
                profession = "farmer")
            val label = civsNpcHologramText(text, npc)
            plainText(label) shouldContain "Василий\n"
            plainText(label) shouldContain "\nФермер\n"
            plainText(label) shouldContain "\n\uE322 18.5/20   🍖 20/20"
            plainText(civsNpcHologramText(text, npc.copy(hunger = 6))) shouldContain "🍖 6/20"
            fun components(value: Component): List<Component> = listOf(value) + value.children().flatMap(::components)
            val icons = components(label).filterIsInstance<TextComponent>().filter { it.content() in setOf("\uE322", "🍖") }
            icons.size shouldBe 2
            icons.forEach {
                it.color() shouldBe TextColor.color(0xFFFFFF)
                it.font() shouldBe Key.key("minecraft:default")
            }
            plainText(civsNpcHologramText(text, npc.copy(child = true, status = "playing"))) shouldContain
                "\nРебёнок\nИграет\n"
            plainText(civsNpcHologramText(text, npc.copy(status = "workers.status.waiting-tool"))) shouldContain "Нужна мотыга"
            plainText(civsNpcHologramText(text, npc.copy(profession = "courier"))) shouldContain "Грузчик"
            plainText(civsNpcHologramText(text, npc.copy(name = "<red>Worker</red>"))) shouldContain "<red>Worker</red>"
            val building = CivBuilding(UUID(0, 2), BuildingType.FARM, 1, CivPosition(0, 64, 0), 0, "fixture/farm",
                CivBox(CivPosition(-4, 64, -6), CivPosition(4, 70, 0)), CivPosition(0, 64, 1), BuildingPhase.CONSTRUCTING)
            val city = Settlement(UUID(0, 3), player.uniqueId, "Fixture", player.world.uid, CivPosition(0, 64, 0), 96,
                "fixture", Stock(), listOf(building), emptyList(), construction =
                    CivConstruction(UUID(0, 4), building.id, 1, 400, 25, ConstructionPhase.CONSTRUCTING, Stock(),
                        blocksPlaced = 37, blocksRequired = 826))
            val construction = text.buildingHologram(player, city, building)
            plainText(construction) shouldContain "37 / 826 блоков"
            plainText(text.buildingHologram(player, city.copy(construction = city.construction!!.copy(blocksPlaced = null)), building))
                .contains("826") shouldBe false
            plainText(text.buildingHologram(player, city.copy(constructionQueue = listOf(building.id)),
                building, "building-status.queued")) shouldContain "В очереди · №1"
            System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
                val serializer = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
                val out = Path.of(directory)
                Files.createDirectories(out)
                Files.writeString(out.resolve("nameplates.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                    "evidence" to "compiled TextDisplay text and transforms, not native-client acceptance",
                    "npc" to mapOf("text" to serializer.serialize(label), "scale" to CIVS_NPC_TEXT_SCALE,
                        "lineWidth" to CIVS_NPC_TEXT_WIDTH, "offset" to CIVS_NPC_TEXT_OFFSET,
                        "viewRangeBlocks" to 64,
                        "background" to listOf(28, 24, 19, 105)),
                    "hungry" to mapOf("text" to serializer.serialize(civsNpcHologramText(text,
                        npc.copy(health = 6.0, hunger = 3, status = "eating"))), "scale" to CIVS_NPC_TEXT_SCALE,
                        "lineWidth" to CIVS_NPC_TEXT_WIDTH, "offset" to CIVS_NPC_TEXT_OFFSET),
                    "courier" to mapOf("text" to serializer.serialize(civsNpcHologramText(text,
                        npc.copy(profession = "courier", status = "carrying-cargo"))), "scale" to CIVS_NPC_TEXT_SCALE,
                        "lineWidth" to CIVS_NPC_TEXT_WIDTH, "offset" to CIVS_NPC_TEXT_OFFSET),
                    "building" to mapOf("text" to serializer.serialize(text.buildingHologram(player, city, building)),
                        "scale" to CIVS_CONTROL_TEXT_SCALE, "lineWidth" to CIVS_CONTROL_TEXT_WIDTH,
                        "offset" to CIVS_CONTROL_TEXT_OFFSET),
                )))
            }
        }
    }
})
