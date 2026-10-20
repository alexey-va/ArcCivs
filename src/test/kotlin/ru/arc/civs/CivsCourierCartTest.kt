package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.npc.PacketPlayerNpc
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class CivsCourierCartTest : FunSpec({
    test("one cart persists through empty pickup turns unload and owner removal") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            var yaw = 0f
            val carrier = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns viewer.world
                every { bodyYaw } answers { yaw }
                every { height } returns 1.8
            }
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val body = mockk<PacketItemDisplay>(relaxed = true)
            val pieces = mutableListOf<PacketBlockDisplay>()
            every { displays.spawnItem(any(), any()) } returns body
            every { displays.spawnBlock(any(), any()) } answers {
                mockk<PacketBlockDisplay>(relaxed = true).also(pieces::add)
            }
            val owner = CivsCourierCart(displays) { ItemStack(Material.PAPER) }
            val npc = CivsNpcHologramSnapshot(UUID(0, 1), viewer.world.uid, 0.0, 64.0, 0.0,
                "Савва", "walking", 20.0, 20.0, actor = carrier, profession = "courier", courierCart = true)
            owner.sync(npc)
            pieces.size shouldBe 0
            owner.sync(npc.copy(cargo = mapOf("COBBLESTONE" to 64)))
            pieces.size shouldBe 3
            verify { body.viewRange = 1f }
            pieces.forEach { handle -> verify { handle.viewRange = 1f } }
            yaw = 90f
            owner.updatePoses()
            verify { body.attachTo(carrier); body.transformation = match { it.translation.x < -0.99f } }
            verify(exactly = 0) { body.teleport(any()) }
            owner.sync(npc.copy(yaw = yaw))
            pieces.forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 1) { displays.spawnItem(any(), any()) }
            owner.sync(npc.copy(courierCart = false))
            owner.close()
            verify(exactly = 1) { body.remove() }
        }
    }

    test("cart points forward at cardinal and diagonal yaw and cargo rests on the measured sloping bed") {
        val states = listOf(
            Triple("empty-before-pickup", 0f, emptyMap<String, Int>()),
            Triple("loaded", 0f, mapOf("COBBLESTONE" to 64)),
            Triple("walking", 0f, mapOf("COBBLESTONE" to 64)),
            Triple("turn-45", 45f, mapOf("COBBLESTONE" to 64)),
            Triple("turn-90", 90f, mapOf("COBBLESTONE" to 64)),
            Triple("return-empty", 180f, emptyMap()),
            Triple("food-load", 225f, mapOf("BREAD" to 48)),
        )
        val frames = states.mapIndexed { index, (id, yaw, cargo) ->
            val radians = Math.toRadians(yaw.toDouble())
            val transform = civsCourierCartTransform(yaw, 1.8)
            transform.translation.x.toDouble() shouldBe (-sin(radians) plusOrMinus 0.000001)
            transform.translation.z.toDouble() shouldBe (cos(radians) plusOrMinus 0.000001)
            (transform.translation.y + 1.8 - 0.5625) shouldBe (0.0 plusOrMinus 0.000001)
            val samples = civsCourierCargoSamples(cargo)
            mapOf("id" to id, "timeMillis" to index * 500, "feet" to listOf(if (index >= 2) 1.0 else 0.0, 0.0, if (index >= 2) 1.0 else 0.0),
                "yaw" to yaw, "nativeHeight" to 1.8, "cart" to cartTransformExport(transform),
                "cargo" to samples.mapIndexed { slot, material -> mapOf("material" to material.name, "block" to material.isBlock,
                    "transform" to cartTransformExport(civsCourierCargoTransform(yaw, 1.8, slot, samples.size, material.isBlock))) })
        }
        for (index in 0..2) {
            val value = civsCourierCargoTransform(0f, 1.8, index, 3, true)
            val bottom = value.leftRotation.transform(Vector3f(value.scale.x / 2, 0f, value.scale.z / 2)).add(value.translation)
            val localZ = bottom.z - 1.0
            (bottom.y + 1.8) shouldBe ((0.536585666 - localZ * kotlin.math.tan(Math.PI / 8)) plusOrMinus 0.000001)
        }
        civsCourierCargoSamples(mapOf("AIR" to 64, "COBBLESTONE" to 0)) shouldBe emptyList()
        civsCourierCargoSamples(mapOf("BREAD" to 1)) shouldBe listOf(Material.BREAD)
        System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
            val out = Path.of(directory); Files.createDirectories(out)
            Files.writeString(out.resolve("courier-cart.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "compiled passenger-local transforms and actual cargo samples; no native-client capture",
                "item" to CIVS_COURIER_CART_ITEM, "states" to frames)))
        }
    }
})

private fun cartTransformExport(value: Transformation): Map<String, List<Float>> = mapOf(
    "translation" to value.translation.let { listOf(it.x, it.y, it.z) },
    "scale" to value.scale.let { listOf(it.x, it.y, it.z) },
    "rotation" to value.leftRotation.let { listOf(it.x, it.y, it.z, it.w) },
)
