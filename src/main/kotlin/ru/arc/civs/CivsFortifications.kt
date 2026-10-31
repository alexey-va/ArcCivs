package ru.arc.civs

import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

internal data class CivFortificationSource(val slot: String, val id: String, val sha256: String,
    val quarterTurns: Int, val offset: CivPosition)

/** All points are canonical origin-relative coordinates; outward is a direction, not a position. */
data class CivFortificationPort(val ground: List<CivPosition>, val floor: List<CivPosition>,
    val feet: List<CivPosition>, val head: List<CivPosition>, val outward: CivPosition)

data class CivFortificationProof(val groundBarrier: Set<CivPosition>,
    val wallEndPorts: List<CivFortificationPort>, val interiorWitnesses: List<CivPosition>,
    val wallWalkFloors: Set<CivPosition> = emptySet())

/** One immutable paid decoration. No decoded Core snapshot, native object or mutable byte array escapes. */
internal class CivFortificationTemplate internal constructor(val building: CivBuilding,
    cells: List<ArcSchematicBlock>, sourceBytes: ByteArray, val sourceSha256: String, val recipeSha256: String,
    sources: List<CivFortificationSource>, val proof: CivFortificationProof) {
    val cells: List<ArcSchematicBlock> = Collections.unmodifiableList(ArrayList(cells))
    val sources: List<CivFortificationSource> = Collections.unmodifiableList(ArrayList(sources))
    private val encoded = sourceBytes.copyOf()
    val sourceBytes: ByteArray get() = encoded.copyOf()
    val groundBarrier: Set<CivPosition> get() = proof.groundBarrier
    val wallEndPorts: List<CivFortificationPort> get() = proof.wallEndPorts
    val interiorWitnesses: List<CivPosition> get() = proof.interiorWitnesses

    fun proofAt(anchor: CivPosition, rotation: Int): CivFortificationProof {
        require(rotation in listOf(0, 90, 180, 270))
        fun rotate(p: CivPosition) = CivsBlueprintGeometry.rotate(ArcSchematicPoint(p.x, p.y, p.z), rotation / 90)
            .let { CivPosition(it.x, it.y, it.z) }
        fun point(p: CivPosition) = rotate(p).let { CivPosition(Math.addExact(anchor.x, it.x),
            Math.addExact(anchor.y, it.y), Math.addExact(anchor.z, it.z)) }
        fun points(values: List<CivPosition>) = Collections.unmodifiableList(values.map(::point))
        return CivFortificationProof(Collections.unmodifiableSet(groundBarrier.mapTo(linkedSetOf(), ::point)),
            Collections.unmodifiableList(wallEndPorts.map { port -> CivFortificationPort(points(port.ground), points(port.floor),
                points(port.feet), points(port.head), rotate(port.outward)) }), points(interiorWitnesses),
            Collections.unmodifiableSet(proof.wallWalkFloors.mapTo(linkedSetOf(), ::point)))
    }
}

/** Fixed reviewed Caledonia recipe. Core supplies all rotated block states before this pure merge. */
internal object CivsFortifications {
    const val STYLE = "caledonia"
    const val MAXIMUM_VOLUME = 19_600L
    const val TOWER_ID = "civs/v2/caledonia/walls-corners-tower/cf8f20afe60617ae99f3/level1"
    const val TOWER_SHA256 = "29367e352d00fba36e6bceafe6380d28c159860cfe2d252dabc92bf492796e9c"
    const val WALL_ID = "civs/v2/caledonia/walls-walls-long/8a4adc3e91f7cb67b550/level1"
    const val WALL_SHA256 = "5abd3707f43cbc2ca01b23cd766650ddeb4fcb685b244ee44941671f98e4bf55"
    private val crop = CivBox(CivPosition(-21, 0, -12), CivPosition(5, 24, 14))
    private val origin = CivPosition(0, 0, 15)
    private val entrance = CivPosition(-10, 1, 0)
    private val ground = setOf("minecraft:gravel", "minecraft:cobblestone")
    private val fullCubes = ground + setOf("minecraft:dirt", "minecraft:stone_bricks", "minecraft:polished_diorite",
        "minecraft:spruce_planks", "minecraft:oak_planks", "minecraft:spruce_log", "minecraft:birch_log")
    private val provenance = listOf(
        CivFortificationSource("tower", TOWER_ID, TOWER_SHA256, 0, CivPosition(0, 0, 0)),
        CivFortificationSource("west-wall", WALL_ID, WALL_SHA256, 0, CivPosition(-12, 0, -1)),
        CivFortificationSource("south-wall", WALL_ID, WALL_SHA256, 1, CivPosition(-6, 0, 5)))

    fun compile(tower: ArcSchematicSnapshot, westWall: ArcSchematicSnapshot, southWall: ArcSchematicSnapshot,
        dataVersion: Int): CivFortificationTemplate {
        check(dataVersion > 0)
        val snapshots = listOf(tower, westWall, southWall)
        val bounds = listOf(CivBox(CivPosition(-5, 0, -12), CivPosition(5, 24, -1)),
            CivBox(CivPosition(-10, 0, -10), CivPosition(10, 11, -1)),
            CivBox(CivPosition(1, 0, -10), CivPosition(10, 11, 10)))
        val merged = linkedMapOf<CivPosition, String>()
        for (index in snapshots.indices) {
            val snapshot = snapshots[index]
            val part = provenance[index]
            requireRecipe(snapshot.fileSha256 == part.sha256, "source-hash", part.slot)
            requireRecipe(snapshot.origin == ArcSchematicPoint(0, 0, 0) &&
                snapshot.minimum == bounds[index].min.arc() && snapshot.maximum == bounds[index].max.arc(), "source-frame", part.slot)
            val cells = snapshot.blocks()
            requireRecipe(snapshot.volume == civsBoxVolume(bounds[index]) && cells.size.toLong() == snapshot.volume &&
                cells.map { it.position }.toSet().size == cells.size && cells.all {
                    bounds[index].contains(it.position.x, it.position.y, it.position.z)
                },
                "source-cells", part.slot)
            for (cell in cells) {
                val p = CivPosition(Math.addExact(cell.position.x, part.offset.x), Math.addExact(cell.position.y, part.offset.y),
                    Math.addExact(cell.position.z, part.offset.z))
                val material = cell.blockData.substringBefore('[')
                if (!crop.contains(p.x, p.y, p.z)) {
                    requireRecipe(material in setOf("minecraft:air", "minecraft:barrier") || p.y == 0 && material in ground,
                        "crop-solid", part.slot)
                    continue
                }
                if (material == "minecraft:barrier") continue
                val state = if (material == "minecraft:structure_void") "minecraft:air" else cell.blockData
                val previous = merged[p]
                when {
                    state == "minecraft:air" -> merged.putIfAbsent(p, state)
                    previous == null || previous == "minecraft:air" -> merged[p] = state
                    // Only normalized ground overlaps. Tower, west, south is the explicit paid-cell priority.
                    p.y == 0 && previous.substringBefore('[') in ground && material in ground -> Unit
                    else -> requireRecipe(false, "body-conflict", part.slot)
                }
            }
        }
        fun full(p: CivPosition) = merged[p]?.substringBefore('[') in fullCubes
        fun air(p: CivPosition) = merged[p] == "minecraft:air"
        val barrier = linkedSetOf<CivPosition>()
        for (x in crop.min.x..crop.max.x) for (z in crop.min.z..crop.max.z) {
            val p = CivPosition(x, 0, z)
            if ((0..3).all { full(p.copy(y = it)) }) barrier += p
        }
        fun port(center: CivPosition, outward: CivPosition): CivFortificationPort {
            val across = CivPosition(-outward.z, 0, outward.x)
            fun row(y: Int) = (-1..1).map { n -> CivPosition(center.x + across.x * n, y, center.z + across.z * n) }
            val bases = row(0); val floors = row(7); val feet = row(8); val heads = row(9)
            requireRecipe(bases.all { it in barrier } && floors.all(::full) && feet.all(::air) && heads.all(::air), "wall-port")
            return CivFortificationPort(bases, floors, feet, heads, outward)
        }
        val ports = listOf(port(CivPosition(-21, 0, -7), CivPosition(-1, 0, 0)),
            port(CivPosition(0, 0, 14), CivPosition(0, 0, 1)))
        val reachable = linkedSetOf(ports.first().ground[1]); val queue = ArrayDeque(reachable)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val n = p.copy(x = p.x + dx, z = p.z + dz)
                if (n in barrier && reachable.add(n)) queue.addLast(n)
            }
        }
        requireRecipe(ports.all { port -> port.ground.all { it in reachable } }, "disconnected-ground-barrier")
        val witnesses = listOf(CivPosition(-4, 1, -2), CivPosition(-10, 1, -2))
        requireRecipe(witnesses.all { full(it.copy(y = 0)) && air(it) && air(it.copy(y = 2)) }, "interior-witness")
        // The authored deck is flat. Paired wooden doors may be opened normally;
        // this support/headroom proof is not native collision or NPC traversal acceptance.
        fun door(p: CivPosition): Boolean {
            val state = merged[p] ?: return false
            if (state.substringBefore('[') != "minecraft:spruce_door") return false
            fun properties(value: String) = value.substringAfter('[', "").substringBefore(']')
                .split(',').mapNotNull { item -> item.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val props = properties(state)
            val bottom = when (props["half"]) { "lower" -> p; "upper" -> p.copy(y = p.y - 1); else -> return false }
            val lower = merged[bottom] ?: return false
            val upper = merged[bottom.copy(y = bottom.y + 1)] ?: return false
            val low = properties(lower); val high = properties(upper)
            return lower.substringBefore('[') == "minecraft:spruce_door" && upper.substringBefore('[') == "minecraft:spruce_door" &&
                low["half"] == "lower" && high["half"] == "upper" &&
                low["facing"] == high["facing"] && low["hinge"] == high["hinge"]
        }
        val floors = merged.keys.filterTo(hashSetOf()) { p -> p.y == 7 && full(p) &&
            (1..2).all { dy -> p.copy(y = p.y + dy).let { air(it) || door(it) } } }
        val walk = linkedSetOf(ports.first().floor[1]); val walkQueue = ArrayDeque(walk)
        while (walkQueue.isNotEmpty()) {
            val p = walkQueue.removeFirst()
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val n = p.copy(x = p.x + dx, z = p.z + dz)
                if (n in floors && walk.add(n)) walkQueue.addLast(n)
            }
        }
        requireRecipe(ports.all { port -> port.floor.all { it in walk } }, "wall-walk")
        requireRecipe((merged.values.toSet() + "minecraft:barrier").size <= 128, "palette")
        val bytes = civsGeneratedSchematic(crop, origin, merged, dataVersion)
        val sourceHash = sha256(bytes)
        val recipeHash = sha256(("arccivs-fortification/1\n$dataVersion\n$sourceHash\nentry=$entrance\n" + provenance.joinToString("\n") {
            "${it.slot}|${it.id}|${it.sha256}|${it.quarterTurns}|${it.offset.x},${it.offset.y},${it.offset.z}"
        }).toByteArray(StandardCharsets.UTF_8))
        val id = "civs/fortifications/v1/$STYLE/$recipeHash"
        fun local(p: CivPosition) = CivPosition(p.x - origin.x, p.y - origin.y, p.z - origin.z)
        val canonicalBounds = CivBox(local(crop.min), local(crop.max))
        requireRecipe(civsBoxVolume(canonicalBounds) in 1..MAXIMUM_VOLUME, "volume")
        val canonicalCells = buildList {
            for (y in crop.min.y..crop.max.y) for (x in crop.min.x..crop.max.x) for (z in crop.min.z..crop.max.z) {
                val p = CivPosition(x, y, z); val state = merged[p] ?: "minecraft:barrier"
                add(ArcSchematicBlock(local(p).arc(), state, state == "minecraft:air", state == "minecraft:barrier"))
            }
        }
        fun points(values: List<CivPosition>) = Collections.unmodifiableList(values.map(::local))
        val proof = CivFortificationProof(Collections.unmodifiableSet(barrier.mapTo(linkedSetOf(), ::local)),
            Collections.unmodifiableList(ports.map { p -> CivFortificationPort(points(p.ground), points(p.floor), points(p.feet),
                points(p.head), p.outward) }), points(witnesses),
            Collections.unmodifiableSet(walk.mapTo(linkedSetOf(), ::local)))
        return CivFortificationTemplate(CivBuilding(UUID.nameUUIDFromBytes(id.toByteArray(StandardCharsets.UTF_8)),
            BuildingType.DECORATION, 1, CivPosition(0, 0, 0), 0, id, canonicalBounds, entrance,
            BuildingPhase.CONSTRUCTING), canonicalCells, bytes, sourceHash, recipeHash, provenance, proof)
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun CivPosition.arc() = ArcSchematicPoint(x, y, z)
    private fun requireRecipe(condition: Boolean, reason: String, slot: String = "recipe") {
        if (!condition) throw CivsRejection("invalid-plan", mapOf("fortification" to reason, "source" to slot))
    }
}
