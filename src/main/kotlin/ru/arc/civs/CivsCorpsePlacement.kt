package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialNativeTarget
import kotlin.math.abs
import kotlin.math.floor

/** At most 25 loaded columns and 26 vertical cells per column; no terrain changes or chunk loads. */
internal fun civsCorpsePlacement(world: World, corpse: CivCorpse,
    previous: CivsCorpseGeometry? = null): CivsCorpseGeometry? {
    if (previous != null && previous.world.uid == world.uid && civsCorpsePlacementValid(previous)) return previous
    val savedY = corpse.feetY ?: corpse.position.y.toDouble()
    val scale = citizenScale(corpse.worker)
    val offsets = (-2..2).flatMap { dx -> (-2..2).map { dz -> dx to dz } }
        .sortedWith(compareBy<Pair<Int, Int>> { it.first * it.first + it.second * it.second }
            .thenBy { it.first }.thenBy { it.second })
    for ((dx, dz) in offsets) {
        val x = corpse.position.x + dx; val z = corpse.position.z + dz
        if (!citizenChunkReady(world, x shr 4, z shr 4)) continue
        val top = minOf(world.maxHeight - 2, floor(savedY + .13).toInt())
        val bottom = maxOf(world.minHeight, floor(savedY).toInt() - 24)
        for (y in top downTo bottom) {
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid) break
            val surface = block.collisionShape.boundingBoxes.filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }
                .maxOfOrNull { y + it.maxY } ?: continue
            if (surface > savedY + .13) break
            val anchor = Location(world, x + .5, surface, z + .5)
            if (!civsCorpseStandingSafe(anchor)) break
            val pose = citizenSleepPose(world, anchor, scale) { body -> corpseBodySupported(world, body, surface) }
            if (pose != null) return corpseGeometry(world, anchor, pose)
            // Never search through a solid surface for an underground cavity.
            break
        }
    }
    return null
}

/** A displayed or clicked body must still fit the same native floor and full animated envelope. */
internal fun civsCorpsePlacementValid(geometry: CivsCorpseGeometry): Boolean {
    val anchor = geometry.anchor
    return civsCorpseStandingSafe(anchor) &&
        citizenSleepClear(geometry.world, geometry.bounds) &&
        corpseBodySupported(geometry.world, geometry.bounds, anchor.y)
}

/** Recheck an already accepted resurrection point without moving it or loading chunks. */
internal fun civsCorpseStandingSafe(location: Location): Boolean {
    val world = location.world ?: return false
    val body = BoundingBox(location.x - .299, location.y + .001, location.z - .299,
        location.x + .299, location.y + 1.799, location.z + .299)
    return citizenSleepClear(world, body) && corpseBodySupported(world, body, location.y)
}

/** Head, torso and legs have real support across the body width, including fractional collision tops. */
private fun corpseBodySupported(world: World, body: BoundingBox, floorY: Double): Boolean {
    val inset = minOf(.1, body.widthX / 4, body.widthZ / 4)
    val xs = listOf(body.minX + inset, body.centerX, body.maxX - inset)
    val zs = listOf(body.minZ + inset, body.centerZ, body.maxZ - inset)
    val y = citizenSupportY(floorY)
    if (y !in world.minHeight until world.maxHeight) return false
    return xs.all { x -> zs.all { z ->
        val bx = floor(x).toInt(); val bz = floor(z).toInt()
        if (!citizenChunkReady(world, bx shr 4, bz shr 4)) false
        else world.getBlockAt(bx, y, bz).let { block -> !block.isLiquid && block.type !in corpseUnsafeSupport && block.collisionShape.boundingBoxes.any {
            abs(y + it.maxY - floorY) <= .02 && x - bx in it.minX..it.maxX && z - bz in it.minZ..it.maxZ
        } }
    } }
}

private val corpseUnsafeSupport = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)

/** Controls follow the same accepted sleeping transform; no independent corpse model offsets. */
private fun corpseGeometry(world: World, anchor: Location, pose: CitizenSleepPose): CivsCorpseGeometry {
    val body = pose.bounds
    val alongX = body.widthX >= body.widthZ
    val length = if (alongX) body.widthX else body.widthZ
    val width = minOf(length / 2, if (alongX) body.widthZ else body.widthX)
    val height = body.height
    val targets = listOf(-.25, .25).map { offset ->
        val at = Location(world, body.centerX + if (alongX) offset * length else 0.0,
            body.minY, body.centerZ + if (alongX) 0.0 else offset * length)
        val box = BoundingBox(at.x - width / 2, at.y, at.z - width / 2,
            at.x + width / 2, at.y + height, at.z + width / 2)
        val planes = listOf(
            SpatialHitPlane(Location(world, at.x, box.maxY, at.z), Vector(1, 0, 0), Vector(0, 0, 1), width, width),
            SpatialHitPlane(Location(world, at.x, box.minY, at.z), Vector(1, 0, 0), Vector(0, 0, 1), width, width),
            SpatialHitPlane(Location(world, box.minX, box.centerY, at.z), Vector(0, 0, 1), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, box.maxX, box.centerY, at.z), Vector(0, 0, 1), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, at.x, box.centerY, box.minZ), Vector(1, 0, 0), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, at.x, box.centerY, box.maxZ), Vector(1, 0, 0), Vector(0, 1, 0), width, height),
        )
        CivsCorpseTarget(box, SpatialNativeTarget(at, width.toFloat(), height.toFloat()), planes)
    }
    return CivsCorpseGeometry(world, pose.position.clone(), Location(world, body.centerX,
        body.maxY + height * .8, body.centerZ), body.clone(), targets, anchor.clone())
}
