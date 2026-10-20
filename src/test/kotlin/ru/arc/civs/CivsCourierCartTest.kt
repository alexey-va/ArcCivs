package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Barrel
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

class CivsCourierCartTest : FunSpec({
    test("one compact parcel exists only during a loaded delivery and reuses its mounted handle through turns") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            var yaw = 0f
            var valid = true
            val carrier = mockk<PacketPlayerNpc> {
                every { isValid } answers { valid }
                every { world } returns viewer.world
                every { bodyYaw } answers { yaw }
                every { height } returns 1.8
            }
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val handles = mutableListOf<PacketBlockDisplay>()
            every { displays.spawnBlock(any(), any()) } answers {
                mockk<PacketBlockDisplay>(relaxed = true).also(handles::add)
            }
            val owner = CivsCourierCart(displays)
            val npc = CivsNpcHologramSnapshot(UUID(0, 1), viewer.world.uid, 0.0, 64.0, 0.0,
                "Савва", "walking", 20.0, 20.0, actor = carrier, profession = "courier", courierCart = true)
            owner.sync(npc)
            handles.size shouldBe 0
            val loaded = npc.copy(cargo = mapOf("COBBLESTONE" to 64))
            owner.sync(loaded)
            owner.sync(loaded.copy(cargo = mapOf("BREAD" to 12)))
            handles.size shouldBe 1
            verify { displays.spawnBlock(any(), match { it.material == Material.BARREL && (it as Barrel).facing == BlockFace.UP }) }
            val body = handles.single()
            verify { body.viewRange = 1f; body.interpolationDuration = 0 }
            yaw = 90f
            owner.updatePoses()
            verify { body.attachTo(carrier); body.transformation = match { it.translation.x > .23f } }
            verify(exactly = 0) { body.teleport(any()); displays.spawnItem(any(), any()) }
            owner.sync(npc.copy(yaw = yaw, cargo = mapOf("COBBLESTONE" to 0)))
            verify(exactly = 1) { body.remove() }
            owner.sync(loaded.copy(courierCart = false))
            handles.size shouldBe 1
            owner.sync(loaded)
            handles.size shouldBe 2
            valid = false
            owner.updatePoses()
            owner.close()
            handles.forEach { handle -> verify(exactly = 1) { handle.remove() } }
        }
    }

    test("parcel corners stay inside the resident horizontal bounds through a full turn and both standing poses") {
        for (height in listOf(1.5, 1.8)) for (yaw in 0 until 360 step 5) {
            val pose = civsCourierParcelTransform(yaw.toFloat(), height)
            for (x in 0..1) for (y in 0..1) for (z in 0..1) {
                val corner = pose.leftRotation.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()).mul(pose.scale))
                    .add(pose.translation).add(0f, height.toFloat(), 0f)
                (abs(corner.x) < .3 && abs(corner.z) < .3) shouldBe true
                (hypot(corner.x.toDouble(), corner.z.toDouble()) < .289) shouldBe true
                corner.y.toDouble() shouldBe ((height * .6 + if (y == 0) -.21 else .21) plusOrMinus .000001)
            }
        }
        val states = listOf(
            Triple("empty-before-pickup", 0f, 0), Triple("loaded", 0f, 64), Triple("walking", 0f, 64),
            Triple("turn-45", 45f, 64), Triple("turn-90", 90f, 64), Triple("return-empty", 180f, 0),
            Triple("food-load", 225f, 48), Triple("off-duty", 0f, 64),
        )
        val frames = states.mapIndexed { index, (id, yaw, amount) ->
            val carrying = amount > 0 && id != "off-duty"
            mapOf("id" to id, "timeMillis" to index * 500,
                "feet" to listOf(if (index >= 2) 1.0 else 0.0, 0.0, if (index >= 2) 1.0 else 0.0),
                "yaw" to yaw, "nativeHeight" to 1.8, "courierCart" to (id != "off-duty"),
                "cargoAmount" to amount, "parcel" to if (carrying) parcelTransformExport(civsCourierParcelTransform(yaw, 1.8)) else null)
        }
        System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
            val out = Path.of(directory); Files.createDirectories(out)
            val source = "src/main/kotlin/ru/arc/civs/CivsCourierCart.kt"
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(source))).joinToString("") { "%02x".format(it) }
            Files.writeString(out.resolve("courier-parcel.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Compiled passenger-local parcel transforms and delivery states; no native-client capture",
                "kind" to "parcel", "material" to "BARREL", "facing" to "UP", "sourceSha256" to mapOf(source to hash), "states" to frames)))
        }
    }
})

private fun parcelTransformExport(value: Transformation): Map<String, List<Float>> = mapOf(
    "translation" to value.translation.let { listOf(it.x, it.y, it.z) },
    "scale" to value.scale.let { listOf(it.x, it.y, it.z) },
    "rotation" to value.leftRotation.let { listOf(it.x, it.y, it.z, it.w) },
)
