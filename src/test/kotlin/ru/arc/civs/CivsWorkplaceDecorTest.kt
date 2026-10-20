package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

class CivsWorkplaceDecorTest : FunSpec({
    test("bright full-size barrel props lie parallel to the lid and follow control relocation without duplicate handles") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val handles = mutableListOf<PacketItemDisplay>()
            every { displays.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(handles::add) }
            val owner = CivsWorkplaceDecor(displays)
            val farm = CivsWorkplaceSnapshot(UUID(0, 1), viewer.world.uid, CivPosition(0, 64, 0), BuildingType.FARM, 0)
            owner.capture(listOf(farm)); while (!owner.reconcileOne()) { }
            handles.size shouldBe 2
            handles.forEach { handle ->
                verify {
                    handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    handle.billboard = Display.Billboard.FIXED
                    handle.viewRange = 1f
                    handle.brightness = match<Display.Brightness> { it.blockLight == 15 && it.skyLight == 15 }
                }
            }
            verify(exactly = 2) { displays.spawnItem(match { it.yaw == 0f && it.pitch == 0f }, any()) }
            owner.capture(listOf(farm)); owner.current() shouldBe true
            owner.reconcileOne(); handles.size shouldBe 2
            owner.capture(listOf(farm.copy(control = CivPosition(4, 64, 0)))); while (!owner.reconcileOne()) { }
            handles.take(2).forEach { verify(exactly = 1) { it.remove() } }
            handles.size shouldBe 4
            owner.capture(emptyList()); while (!owner.reconcileOne()) { }
            owner.close()
            handles.forEach { verify(exactly = 1) { it.remove() } }
        }
        for (rotation in listOf(0, 90, 180, 270)) {
            val footprints = mutableListOf<List<Vector3f>>()
            for (index in 0..1) {
                val value = civsWorkplaceTransform(rotation, index)
                val faces = listOf(7.5f / 16, 8.5f / 16).map { z ->
                    listOf(0f, 1f).flatMap { x -> listOf(0f, 1f).map { y ->
                        // Native order: center model -> FIXED Y180 -> renderer Y180 ->
                        // display right rotation -> scale -> left rotation -> translation.
                        val corner = Vector3f(x, y, z).sub(.5f, .5f, .5f)
                        Quaternionf().rotationY(Math.PI.toFloat()).transform(corner)
                        Quaternionf().rotationY(Math.PI.toFloat()).transform(corner)
                        value.rightRotation.transform(corner)
                        value.leftRotation.transform(corner.mul(value.scale)).add(value.translation)
                    } }
                }
                faces.forEach { face -> (face.maxOf { it.y } - face.minOf { it.y } < .00001f) shouldBe true }
                val corners = faces.flatten()
                footprints += corners
                (abs(corners.minOf { it.y } - 1.006f) < .00001f) shouldBe true
                (abs(corners.maxOf { it.y } - (1.006f + value.scale.z / 16)) < .00001f) shouldBe true
                corners.forEach { corner ->
                    (CIVS_CONTROL_TEXT_OFFSET - corner.y >= .20) shouldBe true
                    (maxOf(abs(corner.x), abs(corner.z)) < .5f) shouldBe true
                }
            }
            val (tool, resource) = footprints
            val gap = maxOf(
                resource.minOf { it.x } - tool.maxOf { it.x }, tool.minOf { it.x } - resource.maxOf { it.x },
                resource.minOf { it.z } - tool.maxOf { it.z }, tool.minOf { it.z } - resource.maxOf { it.z },
            )
            (gap >= .01999f) shouldBe true
        }
        civsWorkplaceTransform(0, 0).scale.x shouldBe .60f
        civsWorkplaceTransform(0, 1).scale.x shouldBe .34f
        civsWorkplaceItems(BuildingType.HOUSE) shouldBe emptyList()
        civsWorkplaceItems(BuildingType.FARM) shouldBe listOf(Material.IRON_HOE, Material.WHEAT)
        System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
            val out = Path.of(directory); Files.createDirectories(out)
            val states = listOf(BuildingType.FARM, BuildingType.QUARRY, BuildingType.LIBRARY, BuildingType.BUILDER_HUT).map { type ->
                mapOf("id" to type.name.lowercase(), "pieces" to civsWorkplaceItems(type).mapIndexed { index, material ->
                    val transform = civsWorkplaceTransform(0, index)
                    mapOf("material" to material.name, "brightness" to listOf(15, 15), "viewRangeBlocks" to 64,
                        "itemDisplayTransform" to "FIXED", "entityYaw" to 0, "entityPitch" to 0,
                        "transform" to mapOf("translation" to transform.translation.let { listOf(it.x, it.y, it.z) },
                            "scale" to transform.scale.let { listOf(it.x, it.y, it.z) },
                            "rotation" to transform.leftRotation.let { listOf(it.x, it.y, it.z, it.w) },
                            "rightRotation" to transform.rightRotation.let { listOf(it.x, it.y, it.z, it.w) }))
                })
            }
            Files.writeString(out.resolve("workplace-decor.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "compiled lid-top packet item transforms; native barrel hitbox unchanged; offline preview only", "states" to states)))
        }
    }
})
