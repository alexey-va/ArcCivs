package ru.arc.civs

import org.bukkit.World
import kotlin.math.abs

/** Loaded-only readback of a short source route; no chunk loads, door opening or terrain writes. */
internal class CivsControlRouteProof(private val route: List<CivPosition>) {
    private var index = 0
    private var previous: CitizenPoint? = null

    /** null yields to the next maintenance pulse. One native step sweep is indivisible. */
    fun advance(world: World): Boolean? {
        if (route.size !in 2..64 || route.zipWithNext().any { (a, b) ->
                abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) != 1L || abs(a.y.toLong() - b.y) > 1L
            }) return false
        val deadline = System.nanoTime() + 2_000_000L
        var checked = 0
        while (index < route.size && checked++ < 8) {
            val point = route[index]
            val next = standing(world, point, true, previous) ?: return false
            previous = next
            index++
            if (System.nanoTime() >= deadline) break
        }
        return if (index == route.size) true else null
    }

    companion object {
        fun standing(world: World, point: CivPosition, allowDoors: Boolean = false,
            previous: CitizenPoint? = null): CitizenPoint? {
            if (point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
                !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return null
            val heights = (point.y - 1..point.y).flatMap { y ->
                val block = world.getBlockAt(point.x, y, point.z)
                if (block.isLiquid || block.type in CITIZEN_LEISURE_HAZARDS || citizenRouteOpenable(block.blockData) != null) emptyList()
                else block.collisionShape.boundingBoxes.filter { it.maxY > 0 && .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }
                    .map { y + it.maxY }
            }.distinct().sortedBy { abs(it - point.y) }
            return heights.asSequence().map { CitizenPoint(point.x + .5, it, point.z + .5) }.firstOrNull { at ->
                abs(at.y - point.y) <= 1.0 && citizenPacketSupported(world, at) &&
                    citizenPacketBodyClear(world, at, allowDoors) && citizenPacketCampfireClear(world, at) &&
                    previous?.let { citizenPacketStepClear(world, it, at, allowDoors) } != false
            }
        }
    }
}
