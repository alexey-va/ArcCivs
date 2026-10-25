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
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

class CivsWorkplaceDecorTest : FunSpec({
    test("lined barrel trays hold three distinct flat props and relocate without duplicate handles") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val handles = mutableListOf<PacketDisplay>()
            val itemHandles = mutableListOf<PacketItemDisplay>()
            val materials = mutableListOf<Material>()
            every { displays.spawnItem(any(), any()) } answers {
                materials += secondArg<ItemStack>().type
                mockk<PacketItemDisplay>(relaxed = true).also { handles += it; itemHandles += it }
            }
            every { displays.spawnBlock(any(), any()) } answers {
                mockk<PacketBlockDisplay>(relaxed = true).also(handles::add)
            }
            val owner = CivsWorkplaceDecor(displays)
            val farm = CivsWorkplaceSnapshot(UUID(0, 1), viewer.world.uid, CivPosition(0, 64, 0), BuildingType.FARM, 0)
            owner.capture(listOf(farm)); while (!owner.reconcileOne()) { }
            handles.size shouldBe 7
            materials shouldBe listOf(Material.IRON_HOE, Material.WHEAT, Material.CARROT)
            handles.forEach { handle ->
                verify {
                    handle.billboard = Display.Billboard.FIXED
                    handle.viewRange = 1f
                    handle.brightness = match<Display.Brightness> { it.blockLight == 15 && it.skyLight == 15 }
                }
            }
            itemHandles.forEach { handle -> verify { handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED } }
            verify(exactly = 3) { displays.spawnItem(match { it.yaw == 0f && it.pitch == 0f }, any()) }
            verify(exactly = 4) { displays.spawnBlock(match { it.yaw == 0f && it.pitch == 0f }, any()) }
            owner.capture(listOf(farm)); owner.current() shouldBe true
            owner.reconcileOne(); handles.size shouldBe 7
            owner.capture(listOf(farm.copy(control = CivPosition(4, 64, 0)))); while (!owner.reconcileOne()) { }
            handles.take(7).forEach { verify(exactly = 1) { it.remove() } }
            handles.size shouldBe 14
            owner.capture(emptyList()); while (!owner.reconcileOne()) { }
            owner.close()
            handles.forEach { verify(exactly = 1) { it.remove() } }
        }
        for (rotation in listOf(0, 90, 180, 270)) {
            val blocks = civsWorkplacePieces(BuildingType.FARM, rotation).filter { it.block }
            blocks.size shouldBe 4
            blocks.forEach { piece ->
                val value = piece.transform
                for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) for (z in listOf(0f, 1f)) {
                    val corner = Vector3f(x, y, z)
                    value.rightRotation.transform(corner)
                    value.leftRotation.transform(corner.mul(value.scale)).add(value.translation)
                    (maxOf(abs(corner.x), abs(corner.z)) < .5f) shouldBe true
                    (corner.y >= 1.00599f && corner.y <= 1.10101f) shouldBe true
                }
            }
            val footprints = mutableListOf<List<Vector3f>>()
            for (index in 0..2) {
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
                (abs(corners.minOf { it.y } - 1.074f) < .00001f) shouldBe true
                (abs(corners.maxOf { it.y } - (1.074f + value.scale.z / 16)) < .00001f) shouldBe true
                corners.forEach { corner ->
                    (CIVS_CONTROL_TEXT_OFFSET - corner.y >= .20) shouldBe true
                    (maxOf(abs(corner.x), abs(corner.z)) < .435f) shouldBe true
                }
            }
            for (a in footprints.indices) for (b in a + 1 until footprints.size) {
                val first = footprints[a]; val second = footprints[b]
                val gap = maxOf(
                    second.minOf { it.x } - first.maxOf { it.x }, first.minOf { it.x } - second.maxOf { it.x },
                    second.minOf { it.z } - first.maxOf { it.z }, first.minOf { it.z } - second.maxOf { it.z },
                )
                (gap >= .01999f) shouldBe true
            }
        }
        civsWorkplaceTransform(0, 0).scale.x shouldBe .56f
        civsWorkplaceTransform(0, 1).scale.x shouldBe .26f
        civsWorkplaceTransform(0, 2).scale.x shouldBe .24f
        for (type in listOf(BuildingType.HOUSE, BuildingType.PLAN_BOARD) + BuildingType.entries.filter { it.isInfrastructure }) {
            civsWorkplacePieces(type, 0) shouldBe emptyList()
            civsWorkplaceItems(type) shouldBe emptyList()
        }
        for (type in BuildingType.entries.filter { it != BuildingType.HOUSE && it != BuildingType.PLAN_BOARD && !it.isInfrastructure }) {
            civsWorkplacePieces(type, 0).size shouldBe 7
            civsWorkplaceItems(type).distinct().size shouldBe 3
        }
        civsWorkplaceItems(BuildingType.BUILDER_HUT) shouldBe listOf(Material.IRON_SHOVEL, Material.BRICK, Material.PAPER)
        System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
            val out = Path.of(directory); Files.createDirectories(out)
            val states = listOf(BuildingType.BUILDER_HUT, BuildingType.FARM, BuildingType.QUARRY, BuildingType.LIBRARY).map { type ->
                mapOf("id" to type.name.lowercase(), "pieces" to civsWorkplacePieces(type, 0).map { piece ->
                    val transform = piece.transform
                    mapOf("kind" to if (piece.block) "block" else "item",
                        "material" to piece.material.name, "brightness" to listOf(15, 15), "viewRangeBlocks" to 64,
                        "itemDisplayTransform" to if (piece.block) "NONE" else "FIXED", "entityYaw" to 0, "entityPitch" to 0,
                        "transform" to mapOf("translation" to transform.translation.let { listOf(it.x, it.y, it.z) },
                            "scale" to transform.scale.let { listOf(it.x, it.y, it.z) },
                            "rotation" to transform.leftRotation.let { listOf(it.x, it.y, it.z, it.w) },
                            "rightRotation" to transform.rightRotation.let { listOf(it.x, it.y, it.z, it.w) }))
                })
            }
            Files.writeString(out.resolve("workplace-decor.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "compiled packet block tray and flat item transforms; native barrel hitbox unchanged; offline preview only", "states" to states)))
        }
    }
})
