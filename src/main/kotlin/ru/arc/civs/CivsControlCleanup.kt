package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Native barrel retirement after the service has durably committed a deletion intent. */
internal class CivsControlCleanup(
    private val plugin: Plugin,
    private val canModify: (UUID, Block) -> Boolean,
) {
    /** Validate the whole selection before the durable intent; this method changes nothing. */
    fun prepare(city: Settlement, buildings: List<CivBuilding>): List<CivPosition> {
        require(buildings.all { city.building(it.id) == it }) { "Unregistered building control" }
        return buildings.mapNotNull(CivBuilding::control).distinct().also { resolve(city, it) }
    }

    /**
     * Revalidate all targets before the first write. A retry skips an already absent barrel.
     * Contents retain native metadata and follow ordinary world/item-entity save semantics.
     * Only contents drop: the automatically supplied barrel must not become a repeatable reward.
     */
    fun apply(city: Settlement, controls: List<CivPosition>): Int {
        val barrels = resolve(city, controls)
        for (barrel in barrels) {
            val inventory = barrel.inventory
            val contents = inventory.contents.map { it?.clone() }.toTypedArray()
            inventory.viewers.toList().forEach { it.closeInventory() }
            // Empty before replacing the tile so native block removal cannot also spill its contents.
            inventory.clear()
            try {
                barrel.block.setType(Material.AIR, false)
            } catch (failure: RuntimeException) {
                if (barrel.block.type == Material.BARREL) inventory.contents = contents
                throw failure
            }
            val dropAt = barrel.location.add(0.5, 0.5, 0.5)
            contents.filterNotNull().filter { !it.type.isAir && it.amount > 0 }.forEach {
                barrel.world.dropItemNaturally(dropAt, it)
            }
        }
        return barrels.size
    }

    private fun resolve(city: Settlement, controls: List<CivPosition>): List<Barrel> {
        check(Bukkit.isPrimaryThread()) { "Control cleanup requires the server thread" }
        val registered = city.buildings.mapNotNull(CivBuilding::control).toSet()
        require(controls.size <= CivsRules.MAX_BUILDINGS && controls.distinct().size == controls.size &&
            registered.containsAll(controls)) { "Unregistered building control" }
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        return controls.mapNotNull { position ->
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) throw CivsRejection("chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            // A player replacement (including a different container or bedrock) is not our barrel.
            if (block.type != Material.BARREL) return@mapNotNull null
            if (!canModify(city.owner, block)) throw CivsRejection("lands-denied")
            block.state as? Barrel ?: throw CivsRejection("control-missing")
        }
    }
}
