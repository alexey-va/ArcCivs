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
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

data class CivsWorkplaceSnapshot(val buildingId: UUID, val worldId: UUID, val control: CivPosition,
    val type: BuildingType, val rotation: Int)

/** A large tool behind its resource, both resting above the barrel lid with no entity or hitbox. */
internal class CivsWorkplaceDecor(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Shown(val value: CivsWorkplaceSnapshot, val handles: List<PacketItemDisplay>)
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
        val handles = mutableListOf<PacketItemDisplay>()
        try {
            civsWorkplaceItems(next.type).forEachIndexed { index, item ->
                val handle = displays.spawnItem(at, ItemStack(item)).also(handles::add)
                handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                handle.billboard = Display.Billboard.FIXED
                handle.viewRange = 1f
                handle.brightness = Display.Brightness(15, 15)
                handle.displayWidth = 2f
                handle.displayHeight = 2f
                handle.shadowRadius = 0f
                handle.shadowStrength = 0f
                handle.teleportDuration = 0
                handle.transformation = civsWorkplaceTransform(next.rotation, index)
            }
            shown[next.buildingId] = Shown(next, handles)
        } catch (failure: Exception) { handles.forEach(PacketItemDisplay::remove); throw failure }
        return false
    }

    private fun remove(id: UUID) { shown.remove(id)?.handles?.forEach(PacketItemDisplay::remove) }
    override fun close() { shown.keys.toList().forEach(::remove); desired = emptyMap() }
}

internal fun civsWorkplaceItems(type: BuildingType): List<Material> = when (type) {
    BuildingType.FARM -> listOf(Material.IRON_HOE, Material.WHEAT)
    BuildingType.FORESTER -> listOf(Material.IRON_AXE, Material.STICK)
    BuildingType.QUARRY -> listOf(Material.IRON_PICKAXE, Material.RAW_IRON)
    BuildingType.TOWN_HALL -> listOf(Material.WRITABLE_BOOK, Material.FEATHER)
    BuildingType.WAREHOUSE -> listOf(Material.BUNDLE, Material.PAPER)
    BuildingType.BUILDER_HUT -> listOf(Material.IRON_SHOVEL, Material.BRICK)
    BuildingType.SAWMILL -> listOf(Material.IRON_AXE, Material.STICK)
    BuildingType.BLACKSMITH -> listOf(Material.IRON_AXE, Material.IRON_INGOT)
    BuildingType.STONEMASON -> listOf(Material.IRON_PICKAXE, Material.BRICK)
    BuildingType.LIBRARY -> listOf(Material.BOOK, Material.FEATHER)
    BuildingType.HOUSE -> emptyList()
}

internal fun civsWorkplaceTransform(rotation: Int, index: Int): Transformation {
    val facing = Quaternionf().rotationY(Math.toRadians(90.0 - rotation).toFloat())
    // Generated FIXED items have a 1/16-block depth. Keep their complete lower edge
    // above y=1, with the smaller resource in front of the inclined tool.
    val position = if (index == 0) Vector3f(-.08f, 1.35f, -.08f) else Vector3f(.22f, 1.17f, .18f)
    facing.transform(position)
    val tilt = Math.toRadians(if (index == 0) 35.0 else 55.0).toFloat()
    return Transformation(position, facing.rotateX(tilt), Vector3f(if (index == 0) .78f else .50f), Quaternionf())
}
