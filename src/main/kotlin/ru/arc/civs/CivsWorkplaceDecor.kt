package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

data class CivsWorkplaceSnapshot(val buildingId: UUID, val worldId: UUID, val control: CivPosition,
    val type: BuildingType, val rotation: Int)

internal data class CivsWorkplacePiece(val material: Material, val transform: Transformation, val block: Boolean = false)

/** A compact work tray with flat tools and supplies; all parts are visual, with no hitboxes. */
internal class CivsWorkplaceDecor(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Shown(val value: CivsWorkplaceSnapshot, val handles: List<PacketDisplay>)
    private val shown = linkedMapOf<UUID, Shown>()
    private var desired = emptyMap<UUID, CivsWorkplaceSnapshot>()

    fun capture(values: List<CivsWorkplaceSnapshot>) {
        desired = values.filter { civsWorkplaceItems(it.type).isNotEmpty() }.associateBy(CivsWorkplaceSnapshot::buildingId)
    }
    fun current(): Boolean = shown.size == desired.size && shown.all { (id, value) -> value.value == desired[id] }

    fun reconcileOne(): Boolean {
        shown.keys.firstOrNull { it !in desired }?.let { remove(it); return false }
        val next = desired.values.firstOrNull { shown[it.buildingId]?.value != it } ?: return true
        remove(next.buildingId)
        val world = Bukkit.getWorld(next.worldId) ?: run { desired = desired - next.buildingId; return false }
        val at = Location(world, next.control.x + .5, next.control.y.toDouble(), next.control.z + .5)
        val handles = mutableListOf<PacketDisplay>()
        try {
            for (piece in civsWorkplacePieces(next.type, next.rotation)) {
                val handle = if (piece.block) displays.spawnBlock(at, piece.material.createBlockData()).also(handles::add)
                    else displays.spawnItem(at, ItemStack(piece.material)).also {
                        handles += it
                        it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    }
                handle.billboard = Display.Billboard.FIXED
                handle.viewRange = 1f
                handle.brightness = Display.Brightness(15, 15)
                handle.displayWidth = 2f
                handle.displayHeight = 2f
                handle.shadowRadius = 0f
                handle.shadowStrength = 0f
                handle.teleportDuration = 0
                handle.transformation = piece.transform
            }
            shown[next.buildingId] = Shown(next, handles)
        } catch (failure: Exception) { handles.forEach(PacketDisplay::remove); throw failure }
        return false
    }

    private fun remove(id: UUID) { shown.remove(id)?.handles?.forEach(PacketDisplay::remove) }
    override fun close() { shown.keys.toList().forEach(::remove); desired = emptyMap() }
}

internal fun civsWorkplaceItems(type: BuildingType): List<Material> = when (type) {
    BuildingType.FARM -> listOf(Material.IRON_HOE, Material.WHEAT, Material.CARROT)
    BuildingType.FORESTER -> listOf(Material.IRON_AXE, Material.STICK, Material.PAPER)
    BuildingType.QUARRY -> listOf(Material.IRON_PICKAXE, Material.RAW_IRON, Material.COAL)
    BuildingType.TOWN_HALL -> listOf(Material.WRITABLE_BOOK, Material.FEATHER, Material.INK_SAC)
    BuildingType.WAREHOUSE -> listOf(Material.BUNDLE, Material.PAPER, Material.STRING)
    BuildingType.BUILDER_HUT -> listOf(Material.IRON_SHOVEL, Material.BRICK, Material.PAPER)
    BuildingType.SAWMILL -> listOf(Material.IRON_AXE, Material.STICK, Material.PAPER)
    BuildingType.BLACKSMITH -> listOf(Material.IRON_AXE, Material.IRON_INGOT, Material.COAL)
    BuildingType.STONEMASON -> listOf(Material.IRON_PICKAXE, Material.BRICK, Material.FLINT)
    BuildingType.LIBRARY -> listOf(Material.BOOK, Material.FEATHER, Material.INK_SAC)
    BuildingType.UNIVERSITY -> listOf(Material.WRITABLE_BOOK, Material.PAPER, Material.FEATHER)
    BuildingType.SMELTERY -> listOf(Material.IRON_INGOT, Material.CHARCOAL, Material.RAW_IRON)
    BuildingType.CRUSHER -> listOf(Material.IRON_PICKAXE, Material.FLINT, Material.RAW_IRON)
    BuildingType.TAVERN -> listOf(Material.BREAD, Material.APPLE, Material.PAPER)
    BuildingType.BAKERY -> listOf(Material.WHEAT, Material.BREAD, Material.SUGAR)
    BuildingType.GUARD_TOWER -> listOf(Material.IRON_SWORD, Material.PAPER, Material.FEATHER)
    BuildingType.HOUSE -> listOf(Material.BOOK, Material.APPLE, Material.STRING)
    BuildingType.WALL, BuildingType.GATE, BuildingType.ROAD, BuildingType.PLAN_BOARD, BuildingType.DECORATION -> emptyList()
}

internal fun civsWorkplacePieces(type: BuildingType, rotation: Int): List<CivsWorkplacePiece> {
    val items = civsWorkplaceItems(type)
    if (items.isEmpty()) return emptyList()
    val facing = Quaternionf().rotationY(Math.toRadians(90.0 - rotation).toFloat())
    fun block(material: Material, position: Vector3f, size: Vector3f) = CivsWorkplacePiece(material,
        Transformation(facing.transform(position), Quaternionf(facing), size, Quaternionf()), block = true)
    val lining = when (type) {
        BuildingType.FARM, BuildingType.FORESTER, BuildingType.SAWMILL -> Material.GREEN_WOOL
        BuildingType.TOWN_HALL, BuildingType.LIBRARY, BuildingType.UNIVERSITY -> Material.BLUE_WOOL
        BuildingType.WAREHOUSE -> Material.BROWN_WOOL
        BuildingType.HOUSE -> Material.PINK_WOOL
        else -> Material.GRAY_WOOL
    }
    return listOf(
        block(Material.SPRUCE_PLANKS, Vector3f(-.47f, 1.006f, -.45f), Vector3f(.94f, .05f, .90f)),
        block(lining, Vector3f(-.435f, 1.056f, -.41f), Vector3f(.87f, .012f, .82f)),
        block(Material.DARK_OAK_PLANKS, Vector3f(-.47f, 1.056f, -.45f), Vector3f(.035f, .045f, .90f)),
        block(Material.DARK_OAK_PLANKS, Vector3f(.435f, 1.056f, -.45f), Vector3f(.035f, .045f, .90f)),
    ) + items.mapIndexed { index, material -> CivsWorkplacePiece(material, civsWorkplaceTransform(rotation, index)) }
}

internal fun civsWorkplaceTransform(rotation: Int, index: Int): Transformation {
    val facing = Quaternionf().rotationY(Math.toRadians(90.0 - rotation).toFloat())
    val scale = when (index) { 0 -> .56f; 1 -> .26f; else -> .24f }
    // Generated/handheld FIXED Y180 cancels the native ItemDisplayRenderer Y180.
    // Centered 1/16 depth rests 0.006 above the tray lining, whose top is y=1.068.
    val height = 1.074f + scale / 32
    val position = when (index) {
        0 -> Vector3f(-.14f, height, 0f)
        1 -> Vector3f(.29f, height, .22f)
        else -> Vector3f(.29f, height, -.20f)
    }
    facing.transform(position)
    return Transformation(position, facing.rotateX(Math.toRadians(90.0).toFloat()), Vector3f(scale), Quaternionf())
}
