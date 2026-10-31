package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPInputStream

class CivsInfrastructureTest : FunSpec({
    test("the measured infrastructure bound admits piece 128 preserves retained walls and refuses piece 129") {
        val base = settlementPlanCity().copy(radius = 128, research = CivResearchState(CivResearchId.entries.toSet()))
        fun wall(index: Int): CivBuilding {
            val point = CivPosition(index % 16 * 4 - 30, 64, index / 16 * 4 + 24)
            return CivBuilding(UUID(141, index.toLong()), BuildingType.WALL, 1, point, 0, "fixture/wall/$index",
                CivBox(point, point.copy(y = 67)), point.copy(y = 65, z = point.z - 1), BuildingPhase.ACTIVE)
        }
        val old = base.copy(buildings = base.buildings + (0 until 96).map(::wall))
        CivsRules.validate(Gson().fromJson(Gson().toJson(old), Settlement::class.java)) shouldBe old
        val before = base.copy(buildings = base.buildings + (0 until 127).map(::wall))
        CivsRules.validate(before) shouldBe before
        val admitted = CivsRules.placeSite(before, before.owner, wall(127))
        admitted.buildings.count { it.type.isInfrastructure } shouldBe 128
        admitted.buildings.take(before.buildings.size) shouldBe before.buildings
        admitted.workers shouldBe before.workers
        admitted.stock shouldBe before.stock
        CivsRules.buildingCount(admitted) shouldBe 1
        CivsRules.validate(admitted) shouldBe admitted
        shouldThrow<CivsRejection> { CivsRules.placeSite(admitted, admitted.owner, wall(128)) }.reason shouldBe "building-limit"
        CivsSettlementLayout.MAXIMUM_PIECES shouldBe CivsRules.MAX_INFRASTRUCTURE
    }

    test("generated Sponge capture preserves negative coordinates stepped floors and barrier gaps") {
        val low = CivPosition(-17, 63, -9)
        val high = CivPosition(-16, 66, -9)
        val cells = listOf(
            CivSettlementBlock(low, "minecraft:cobblestone"),
            CivSettlementBlock(low.copy(y = 64), "minecraft:air"),
            CivSettlementBlock(low.copy(y = 65), "minecraft:air"),
            CivSettlementBlock(high.copy(y = 64), "minecraft:cobblestone"),
            CivSettlementBlock(high.copy(y = 65), "minecraft:air"),
            CivSettlementBlock(high, "minecraft:air"),
        )
        val piece = CivSettlementPiece(UUID(68, 1), BuildingType.ROAD, CivBox(low, high), low.copy(x = -18, y = 64),
            cells, listOf(low.copy(y = 64), high.copy(y = 65)))
        val bytes = CivsInfrastructure.schematic(piece, 4671)
        val root = DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            input.readUnsignedByte() shouldBe 10
            input.readUTF() shouldBe ""
            readInfrastructureNbt(input, 10) as Map<*, *>
        }
        val schematic = root.get("Schematic") as Map<*, *>
        schematic["Version"] shouldBe 3
        schematic["DataVersion"] shouldBe 4671
        (schematic["Offset"] as IntArray).toList() shouldBe listOf(0, 0, 0)
        schematic.keys shouldBe setOf("Version", "DataVersion", "Width", "Height", "Length", "Offset", "Blocks")
        val width = (schematic.get("Width") as Number).toInt()
        val height = (schematic.get("Height") as Number).toInt()
        val length = (schematic.get("Length") as Number).toInt()
        listOf(width, height, length) shouldBe listOf(2, 4, 1)
        val blocks = schematic.get("Blocks") as Map<*, *>
        val palette = (blocks.get("Palette") as Map<*, *>).entries.associate { (state, index) -> (index as Int) to state }
        val data = blocks.get("Data") as ByteArray
        data.size shouldBe width * height * length
        val decoded = data.mapIndexed { index, value ->
            CivPosition(low.x + index % width, low.y + index / (width * length), low.z + index / width % length) to
                palette.getValue(value.toInt() and 255)
        }.filterNot { it.second == "minecraft:barrier" }.toMap()
        decoded shouldBe cells.associate { it.position to it.after }
        CivsInfrastructure.building(piece).control shouldBe null
        CivsInfrastructure.building(piece).transit shouldBe piece.transit
        System.getenv("CIVS_LAYOUT_OUTPUT")?.let { out ->
            Files.createDirectories(Path.of(out))
            Files.write(Path.of(out).resolve("generated-road-contract.schem"), bytes)
        }
    }

    test("only completed explicit road and gate columns permit public transit") {
        fun building(type: BuildingType) = CivBuilding(UUID(68, type.ordinal.toLong()), type, 1, CivPosition(10, 64, 10), 0,
            "test/road", CivBox(CivPosition(10, 64, 10), CivPosition(10, 67, 10)), CivPosition(9, 65, 10),
            BuildingPhase.ACTIVE, transit = listOf(CivPosition(10, 65, 10)))
        for (type in listOf(BuildingType.ROAD, BuildingType.GATE)) {
            val b = building(type)
            civsPublicTransit(b, 10, 10) shouldBe true
            civsPublicTransit(b, 11, 10) shouldBe false
            civsPublicTransit(b.copy(phase = BuildingPhase.SITE), 10, 10) shouldBe false
            civsPublicTransit(b.copy(phase = BuildingPhase.CONSTRUCTING), 10, 10) shouldBe false
        }
        civsPublicTransit(building(BuildingType.WALL), 10, 10) shouldBe false
        shouldThrow<CivsRejection> { CivsRules.cost(BuildingType.ROAD, 2) }.reason shouldBe "invalid-level"
        shouldThrow<CivsRejection> { CivsAutonomy.cost(BuildingType.GATE, 2) }.reason shouldBe "invalid-building"
    }
})

/** Independent generic NBT reader; the fixture follows Sponge v3's x + z*w + y*w*l layout. */
private fun readInfrastructureNbt(input: DataInputStream, type: Int): Any = when (type) {
    2 -> input.readShort()
    3 -> input.readInt()
    7 -> ByteArray(input.readInt()).also(input::readFully)
    10 -> linkedMapOf<String, Any>().apply {
        while (true) {
            val child = input.readUnsignedByte()
            if (child == 0) break
            put(input.readUTF(), readInfrastructureNbt(input, child))
        }
    }
    11 -> IntArray(input.readInt()) { input.readInt() }
    else -> error("Unexpected NBT tag $type")
}
