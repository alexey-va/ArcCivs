package ru.arc.civs

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

/** A generated piece becomes the same immutable Sponge capture used by ordinary construction.
 * No entities, tile data, external assets or independent native placement engine are involved.
 * Format: https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-3.md
 */
internal object CivsInfrastructure {
    fun building(piece: CivSettlementPiece): CivBuilding = CivBuilding(piece.id, piece.type, 1,
        piece.bounds.min, 0, "civs/infrastructure/v1/${piece.type.name.lowercase()}/${piece.id}",
        piece.bounds, piece.entrance, BuildingPhase.CONSTRUCTING,
        transit = piece.transit.takeIf { it.isNotEmpty() }?.toList())

    /** Detached, bounded encoding. Core decodes and owns the resulting snapshot and native writes. */
    fun schematic(piece: CivSettlementPiece, dataVersion: Int): ByteArray {
        require(piece.type.isInfrastructure)
        val cells = piece.blocks.associate { it.position to it.after }
        require(cells.size == piece.blocks.size)
        return civsGeneratedSchematic(piece.bounds, piece.bounds.min, cells, dataVersion)
    }
}

/** The board and infrastructure share this small, bounded Sponge encoder; Core still owns decoding and placement. */
internal fun civsGeneratedSchematic(box: CivBox, origin: CivPosition, cells: Map<CivPosition, String>, dataVersion: Int): ByteArray {
    require(dataVersion > 0)
    val width = box.max.x.toLong() - box.min.x + 1
    val height = box.max.y.toLong() - box.min.y + 1
    val length = box.max.z.toLong() - box.min.z + 1
    require(width in 1..256 && height in 1..256 && length in 1..256 && width * height * length <= 65_536)
    require(cells.isNotEmpty() && cells.keys.all { box.contains(it.x, it.y, it.z) })
    val palette = linkedMapOf("minecraft:barrier" to 0)
    cells.values.sorted().forEach { palette.putIfAbsent(it, palette.size) }
    require(palette.size <= 128 && palette.keys.all { it.length in 1..512 && it.startsWith("minecraft:") })
    val data = ByteArrayOutputStream()
    for (y in box.min.y..box.max.y) for (z in box.min.z..box.max.z) for (x in box.min.x..box.max.x)
        data.write(palette.getValue(cells[CivPosition(x, y, z)] ?: "minecraft:barrier"))
    val output = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(output)).use { nbt ->
        fun tag(type: Int, name: String) { nbt.writeByte(type); nbt.writeUTF(name) }
        fun int(name: String, value: Int) { tag(3, name); nbt.writeInt(value) }
        fun short(name: String, value: Long) { tag(2, name); nbt.writeShort(value.toInt()) }
        tag(10, ""); tag(10, "Schematic")
        int("Version", 3); int("DataVersion", dataVersion)
        short("Width", width); short("Height", height); short("Length", length)
        tag(11, "Offset"); nbt.writeInt(3)
        nbt.writeInt(Math.subtractExact(box.min.x, origin.x))
        nbt.writeInt(Math.subtractExact(box.min.y, origin.y))
        nbt.writeInt(Math.subtractExact(box.min.z, origin.z))
        tag(10, "Blocks"); tag(10, "Palette")
        palette.forEach { (state, index) -> int(state, index) }
        nbt.writeByte(0)
        tag(7, "Data"); nbt.writeInt(data.size()); nbt.write(data.toByteArray())
        nbt.writeByte(0); nbt.writeByte(0); nbt.writeByte(0)
    }
    return output.toByteArray()
}

/** Exact public columns remain walkable; native body/headroom capture still decides passage. */
internal fun civsPublicTransit(building: CivBuilding, x: Int, z: Int): Boolean =
    building.phase == BuildingPhase.ACTIVE && (building.type == BuildingType.ROAD || building.type == BuildingType.GATE) &&
        building.transit.orEmpty().any { it.x == x && it.z == z }

/** Validated road/gate bounds are filled rectangles, so they are not horizontal route obstacles. */
internal fun civsPublicTransit(building: CivBuilding, allowPlanned: Boolean = false): Boolean =
    (allowPlanned || building.phase == BuildingPhase.ACTIVE) && (building.type == BuildingType.ROAD || building.type == BuildingType.GATE) &&
        !building.transit.isNullOrEmpty()
