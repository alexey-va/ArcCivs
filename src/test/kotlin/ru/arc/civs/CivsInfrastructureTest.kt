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
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream

class CivsInfrastructureTest : FunSpec({
    test("the measured infrastructure bound admits piece 192 preserves retained walls and refuses piece 193") {
        val base = settlementPlanCity().copy(radius = 128, research = CivResearchState(CivResearchId.entries.toSet()))
        fun wall(index: Int): CivBuilding {
            val point = CivPosition(index % 16 * 4 - 30, 64, index / 16 * 4 + 24)
            return CivBuilding(UUID(141, index.toLong()), BuildingType.WALL, 1, point, 0, "fixture/wall/$index",
                CivBox(point, point.copy(y = 67)), point.copy(y = 65, z = point.z - 1), BuildingPhase.ACTIVE)
        }
        for (oldCount in listOf(96, 128, 160)) {
            val old = base.copy(buildings = base.buildings + (0 until oldCount).map(::wall))
            CivsRules.validate(Gson().fromJson(Gson().toJson(old), Settlement::class.java)) shouldBe old
        }
        val before = base.copy(buildings = base.buildings + (0 until 191).map(::wall))
        CivsRules.validate(before) shouldBe before
        val admitted = CivsRules.placeSite(before, before.owner, wall(191))
        admitted.buildings.count { it.type.isInfrastructure } shouldBe 192
        admitted.buildings.take(before.buildings.size) shouldBe before.buildings
        admitted.workers shouldBe before.workers
        admitted.stock shouldBe before.stock
        CivsRules.buildingCount(admitted) shouldBe 1
        CivsRules.validate(admitted) shouldBe admitted
        shouldThrow<CivsRejection> { CivsRules.placeSite(admitted, admitted.owner, wall(192)) }.reason shouldBe "building-limit"
        CivsSettlementLayout.MAXIMUM_PIECES shouldBe CivsRules.MAX_INFRASTRUCTURE
    }

    test("a complete planned town with 192 infrastructure pieces and 28 residents roundtrips within the existing city codec") {
        val base = settlementPlanCity().copy(radius = 128)
        var captured: CivSettlementInfrastructure? = null
        val plan = CivsSettlementPlans.prepare(base, settlementPlanTemplates(), settlementPlanSurface(base.copy(radius = 127)),
            CivAutonomousAccess(), inspect = { _, infrastructure -> captured = infrastructure })
        val infrastructure = requireNotNull(captured)
        infrastructure.rejected shouldBe emptyMap()
        infrastructure.perimeterClosed shouldBe true
        plan.plots.size shouldBe 34
        val ordinary = plan.plots.map { plot -> plot.building.copy(phase = BuildingPhase.ACTIVE,
            bounds = plot.envelope, level = if (plot.building.type == BuildingType.TOWN_HALL) 3 else plot.building.level) }
        val existing = ordinary + infrastructure.pieces.map { CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE) }
        val missingInfrastructure = 192 - infrastructure.pieces.size
        (missingInfrastructure >= 0) shouldBe true
        val reach = base.copy(buildings = existing)
        // Flat free columns represent additional paid pieces without overlapping the complete generated town.
        val spareColumns = buildList {
            for (x in -base.radius + 4..base.radius - 4 step 4)
                for (z in -base.radius + 4..base.radius - 4 step 4) {
                    val point = CivPosition(x, base.center.y, z)
                    if (existing.none { CivsAutonomousGeometry.overlaps(CivBox(point, point), it.bounds, 2) } &&
                        CivsRules.workRouteWithinReach(reach, point.copy(y = point.y + 1, z = point.z - 1))) add(point)
                }
        }.take(missingInfrastructure + CivsRules.MAX_AUTONOMOUS_DECORATIONS)
        spareColumns.size shouldBe missingInfrastructure + CivsRules.MAX_AUTONOMOUS_DECORATIONS
        val extra = spareColumns.mapIndexed { index, point ->
            val type = if (index < missingInfrastructure) BuildingType.WALL else BuildingType.DECORATION
            CivBuilding(UUID(157, index.toLong()), type, 1, point, 0, "fixture/max-town/${type.name}/$index",
                CivBox(point, point.copy(y = point.y + 3)), point.copy(y = point.y + 1, z = point.z - 1), BuildingPhase.ACTIVE)
        }
        val buildings = existing + extra
        val houses = ordinary.filter { it.type == BuildingType.HOUSE }
        val adults = (0 until CivsRules.MAX_POPULATION).map { index ->
            CivWorker(UUID(157, 1_000L + index), "Resident $index", null, completedCycles = 500,
                health = 28.0, hunger = 20, skills = CivsResidents.professions.associateWith { 40L },
                inventory = mapOf("BREAD" to 16L, "IRON_PICKAXE" to 1L),
                sex = if (index % 2 == 0) CivSex.MALE else CivSex.FEMALE, homeId = houses[index / 2].id)
        }
        val children = (0 until CivsPopulation.NURSERY_PLACES).map { index ->
            CivWorker(UUID(157, 2_000L + index), "Child $index", null,
                sex = if (index % 2 == 0) CivSex.MALE else CivSex.FEMALE, homeId = houses[index].id,
                parentIds = setOf(adults[index * 2].id, adults[index * 2 + 1].id), childMinutes = 0)
        }
        val families = adults.chunked(2).mapIndexed { index, parents ->
            CivFamily(parents[0].id, parents[1].id, houses[index].id, 0)
        }
        val city = CivsRules.validate(base.copy(buildings = buildings, workers = adults + children, families = families,
            inventory = CivInventory(CivStorageMode.PHYSICAL),
            autonomy = base.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth)))
        city.buildings.count { it.type.isInfrastructure } shouldBe 192
        city.buildings.count { it.type == BuildingType.DECORATION } shouldBe CivsRules.MAX_AUTONOMOUS_DECORATIONS
        CivsRules.buildingCount(city) shouldBe 34
        city.workers.size shouldBe CivsRules.MAX_RESIDENTS
        CivsSettlementPlans.current(city) shouldBe plan
        val root = Files.createTempDirectory("arc-civs-infrastructure-cap-")
        val direct = Executor { it.run() }
        try {
            CivsStore(root, direct).use { store -> store.save(city).join() shouldBe city }
            val bytes = Files.size(root.resolve("cities/${city.id}.json"))
            (bytes in 1..CivsStore.MAX_CITY_BYTES) shouldBe true
            println("ARCCIVS_INFRASTRUCTURE_CAP_BYTES bytes=$bytes limit=${CivsStore.MAX_CITY_BYTES} " +
                "ordinary=34 infrastructure=192 decorations=${CivsRules.MAX_AUTONOMOUS_DECORATIONS} residents=${city.workers.size}")
            CivsStore(root, direct).use { store -> store.load().join() shouldBe listOf(city) }
        } finally {
            root.toFile().deleteRecursively()
        }
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
