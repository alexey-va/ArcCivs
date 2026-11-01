package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Public visits never select a management city or change membership. */
internal class CivsVillageTravel(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val records: CivsCityRecords,
    private val ready: () -> Boolean,
    private val load: (World, Int, Int) -> CompletableFuture<Chunk?> = { world, x, z -> world.getChunkAtAsync(x, z, false) },
    private val teleport: (Player, Location) -> Boolean = { player, at -> player.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN) },
) {
    fun visit(player: Player, cityId: UUID): CompletableFuture<Unit> {
        check(Bukkit.isPrimaryThread())
        val city = target(player, cityId)
        val hall = hall(city)
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        val epoch = tasks.token()
        // The 27 entrance candidates plus their collision neighbours span at most four chunks.
        val chunks = buildList {
            for (x in ((hall.entrance.x.toLong() - 2) shr 4)..((hall.entrance.x.toLong() + 2) shr 4))
                for (z in ((hall.entrance.z.toLong() - 2) shr 4)..((hall.entrance.z.toLong() + 2) shr 4))
                    add(x.toInt() to z.toInt())
        }
        val loads = chunks.map { (x, z) ->
            if (world.isChunkLoaded(x, z)) CompletableFuture.completedFuture<Chunk?>(null)
            else runCatching { load(world, x, z) }.getOrElse { CompletableFuture.failedFuture(it) }
        }
        return CompletableFuture.allOf(*loads.toTypedArray()).handle { _, failure -> failure }.thenCompose { failure ->
            tasks.callSync(epoch) {
                val current = target(player, cityId)
                val currentHall = hall(current)
                if (current.worldId != city.worldId || currentHall.id != hall.id || currentHall.entrance != hall.entrance)
                    throw CivsRejection("stale-city")
                if (plugin.server.getWorld(current.worldId) !== world) throw CivsRejection("world-unavailable")
                if (failure != null || chunks.any { (x, z) -> !world.isChunkLoaded(x, z) })
                    throw CivsRejection("chunk-unavailable")
                val feet = citizenRecoveryTarget(current, hall.entrance) { safe(world, it) }
                    ?: throw CivsRejection("visit-unavailable")
                val at = Location(world, feet.x + .5, feet.y.toDouble(), feet.z + .5)
                // Native reads and the teleport share this Main callback; every required chunk is already loaded.
                if (!runCatching { teleport(player, at) }.getOrElse { throw CivsRejection("visit-failed").apply { initCause(it) } })
                    throw CivsRejection("visit-failed")
                Unit
            }
        }
    }

    private fun target(player: Player, cityId: UUID): Settlement {
        if (!ready() || records.closed || records.loading) throw CivsRejection("not-ready")
        if (!player.isOnline || plugin.server.getPlayer(player.uniqueId) !== player) throw CivsRejection("player-offline")
        if (!player.hasPermission("arccivs.use")) throw CivsRejection("not-authorized")
        val city = records.cities[cityId] ?: throw CivsRejection("stale-city")
        if (city.autonomy == null) throw CivsRejection("not-authorized")
        if (city.pendingDeletion != null) throw CivsRejection("deletion-pending")
        return city
    }

    private fun hall(city: Settlement): CivBuilding = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
        ?: throw CivsRejection("visit-unavailable")

    private fun safe(world: World, point: CivPosition): Boolean {
        if (point.y <= world.minHeight || point.y.toLong() + 1 >= world.maxHeight ||
            !world.worldBorder.isInside(Location(world, point.x + .5, point.y.toDouble(), point.z + .5))) return false
        val floor = world.getBlockAt(point.x, point.y - 1, point.z)
        if (floor.isLiquid || floor.type in CITIZEN_LEISURE_HAZARDS || floor.collisionShape.boundingBoxes.none {
                it.maxY in .875..1.0 && it.minX <= .2 && it.maxX >= .8 && it.minZ <= .2 && it.maxZ >= .8
            }) return false
        val body = BoundingBox(point.x + .2, point.y.toDouble(), point.z + .2,
            point.x + .8, point.y + 1.8, point.z + .8)
        for (x in point.x - 1..point.x + 1) for (z in point.z - 1..point.z + 1)
            for (y in point.y - 1..point.y + 1) {
                val block = world.getBlockAt(x, y, z)
                val occupied = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                if ((block.isLiquid || block.type in CITIZEN_LEISURE_HAZARDS) && occupied.overlaps(body) ||
                    block.collisionShape.boundingBoxes.any { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(body) }) return false
            }
        return true
    }
}
