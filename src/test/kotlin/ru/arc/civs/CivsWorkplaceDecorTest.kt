package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

class CivsWorkplaceDecorTest : FunSpec({
    test("barrel props stay outside its side below the lid and follow control relocation without duplicate handles") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val handles = mutableListOf<PacketItemDisplay>()
            every { displays.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(handles::add) }
            val owner = CivsWorkplaceDecor(displays)
            val farm = CivsWorkplaceSnapshot(UUID(0, 1), viewer.world.uid, CivPosition(0, 64, 0), BuildingType.FARM, 0)
            owner.capture(listOf(farm)); while (!owner.reconcileOne()) { }
            handles.size shouldBe 2
            owner.capture(listOf(farm)); owner.current() shouldBe true
            owner.reconcileOne(); handles.size shouldBe 2
            owner.capture(listOf(farm.copy(control = CivPosition(4, 64, 0)))); while (!owner.reconcileOne()) { }
            handles.take(2).forEach { verify(exactly = 1) { it.remove() } }
            handles.size shouldBe 4
            owner.capture(emptyList()); while (!owner.reconcileOne()) { }
            owner.close()
            handles.forEach { verify(exactly = 1) { it.remove() } }
        }
        for (rotation in listOf(0, 90, 180, 270)) for (index in 0..1) {
            val value = civsWorkplaceTransform(rotation, index)
            for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) {
                val corner = value.leftRotation.transform(Vector3f(x, y, 0f).mul(value.scale)).add(value.translation)
                (corner.y in 0f..0.85f) shouldBe true
                (maxOf(abs(corner.x), abs(corner.z)) > .515f) shouldBe true
            }
        }
        civsWorkplaceItems(BuildingType.HOUSE) shouldBe emptyList()
        civsWorkplaceItems(BuildingType.FARM) shouldBe listOf(Material.IRON_HOE, Material.WHEAT)
        System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let { directory ->
            val out = Path.of(directory); Files.createDirectories(out)
            val states = listOf(BuildingType.FARM, BuildingType.QUARRY).map { type ->
                mapOf("id" to type.name.lowercase(), "pieces" to civsWorkplaceItems(type).mapIndexed { index, material ->
                    val transform = civsWorkplaceTransform(0, index)
                    mapOf("material" to material.name,
                        "transform" to mapOf("translation" to transform.translation.let { listOf(it.x, it.y, it.z) },
                            "scale" to transform.scale.let { listOf(it.x, it.y, it.z) },
                            "rotation" to transform.leftRotation.let { listOf(it.x, it.y, it.z, it.w) }))
                })
            }
            Files.writeString(out.resolve("workplace-decor.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "compiled side-mounted packet item transforms; native barrel hitbox unchanged", "states" to states)))
        }
    }
})
