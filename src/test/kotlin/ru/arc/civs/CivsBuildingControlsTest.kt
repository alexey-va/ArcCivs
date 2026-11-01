package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPInputStream

class CivsBuildingControlsTest : FunSpec({
    test("all functional types choose the same interior authored storage in every rotation") {
        val snapshot = controlRoom()
        for (type in BuildingType.entries.filter(CivsBuildingControls::supported)) for (turns in 0..3) {
            val building = controlBuilding(snapshot, type, turns)
            val found = CivsBuildingControls.authored(building, snapshot)
            found.isNotEmpty() shouldBe true
            found.first().existing shouldBe true
            found.first().retirePrevious shouldBe true
            found.first().position shouldBe CivsWorksiteLayouts.point(building, CivPosition(-2, 1, -5))
            found.none { it.position == building.control || it.position == building.entrance } shouldBe true
            val saved = building.copy(control = found.first().position)
            val stable = CivsBuildingControls.authored(saved, snapshot)
            stable.any { it.position == saved.control } shouldBe true
            stable.all { !it.retirePrevious } shouldBe true
        }
    }

    test("a stale exterior override is checked again and authored furniture is never retired") {
        val snapshot = controlRoom()
        val building = controlBuilding(snapshot, BuildingType.TOWN_HALL, 0)
        val stale = building.copy(control = CivsWorksiteLayouts.point(building, CivPosition(-3, 1, -1)))
        val found = CivsBuildingControls.authored(stale, snapshot)
        found.isNotEmpty() shouldBe true
        found.none { it.position == stale.control } shouldBe true
        found.all { !it.retirePrevious } shouldBe true
    }

    test("an already accessible interior legacy marker keeps its location") {
        val snapshot = controlRoom(markerInside = true)
        val building = controlBuilding(snapshot, BuildingType.TOWN_HALL, 0)
        CivsBuildingControls.authored(building, snapshot).any { it.position == building.control } shouldBe true
    }

    test("native route proof yields in slices accepts path height and rejects a real body obstruction") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = ResidentRuntimeWorld().also(paper.server::addWorld)
            world.loadChunk(0, 0)
            world.getBlockAt(4, 63, 2).type = Material.DIRT_PATH
            val route = (1..12).map { CivPosition(it, 64, 2) }
            fun result(proof: CivsControlRouteProof): Boolean {
                repeat(64) { proof.advance(world)?.let { return it } }
                error("A 64-point proof must finish in at most 64 nonempty slices")
            }
            val proof = CivsControlRouteProof(route)
            proof.advance(world) shouldBe null
            result(proof) shouldBe true
            world.getBlockAt(6, 64, 2).type = Material.STONE
            world.getBlockAt(6, 65, 2).type = Material.STONE
            result(CivsControlRouteProof(route)) shouldBe false
            world.getBlockAt(6, 64, 2).type shouldBe Material.STONE
            result(CivsControlRouteProof((1..65).map { CivPosition(it, 64, 2) })) shouldBe false
        }
    }

    test("unroofed functional yards prefer existing storage and keep spare controls inside the work floor") {
        val room = controlRoom(roof = false)
        val building = controlBuilding(room, BuildingType.FORESTER, 0)
        val found = CivsBuildingControls.authored(building, room)
        found.isNotEmpty() shouldBe true
        found.first().existing shouldBe true
        found.all { it.position.x > building.bounds.min.x && it.position.x < building.bounds.max.x &&
            it.position.z > building.bounds.min.z && it.position.z < building.bounds.max.z } shouldBe true
        for (type in listOf(BuildingType.PLAN_BOARD, BuildingType.DECORATION, BuildingType.ROAD, BuildingType.WALL, BuildingType.GATE))
            CivsBuildingControls.authored(building.copy(type = type), room) shouldBe emptyList()
    }

    val fixture = System.getenv("CIVS_CONTROL_TEMPLATE_FIXTURES")
    test("audit every private ready functional template with the production planner")
        .config(enabled = fixture != null) {
            val results = mutableListOf<Map<String, Any?>>()
            val gson = Gson()
            val cities = linkedMapOf<UUID, Settlement>()
            System.getenv("CIVS_CONTROL_CURRENT_SNAPSHOTS")?.let { directory ->
                Files.list(Path.of(directory)).use { paths -> paths.filter {
                    it.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.json"))
                }.forEach { path -> gson.fromJson(Files.readString(path), Settlement::class.java).let { cities[it.id] = it } } }
            }
            System.getenv("CIVS_CONTROL_CURRENT_OVERRIDE")?.let { path ->
                gson.fromJson(Files.readString(Path.of(path)), Settlement::class.java).let { cities[it.id] = it }
            }
            val actual = cities.values.flatMap { city -> city.buildings.map { building ->
                linkedMapOf<String, Any?>("cityId" to city.id, "city" to city.name, "buildingId" to building.id,
                    "type" to building.type.name, "phase" to building.phase.name, "schematicId" to building.schematicId,
                    "before" to building.control, "status" to when {
                        building.control == null -> "no-control-by-design"
                        !CivsBuildingControls.supported(building.type) -> "dedicated-control"
                        building.phase != BuildingPhase.ACTIVE || city.construction?.buildingId == building.id -> "accepted-construction-deferred"
                        else -> "source-unavailable"
                    })
            } }
            val input = Files.newInputStream(Path.of(requireNotNull(fixture)))
            (if (fixture.endsWith(".gz")) GZIPInputStream(input) else input).bufferedReader().useLines { lines -> lines.filter(String::isNotBlank).forEach { line ->
                val row = JsonParser.parseString(line).asJsonObject
                val size = row.getAsJsonArray("size").map { it.asInt }
                val offset = row.getAsJsonArray("worldEditOffset").map { it.asInt }
                val snapshot = ControlSnapshot(ArcSchematicPoint(offset[0], offset[1], offset[2]),
                    ArcSchematicPoint(0, 0, 0), size, row.getAsJsonArray("cells").map { it.asString }, row["sha256"].asString)
                val building = controlBuilding(snapshot, BuildingType.valueOf(row["type"].asString), 0)
                    .copy(schematicId = row["id"].asString, level = row["level"].asInt)
                val found = CivsBuildingControls.authored(building, snapshot)
                results += mapOf("schematicId" to building.schematicId, "type" to building.type.name,
                    "level" to building.level, "sha256" to snapshot.fileSha256, "before" to building.control,
                    "after" to found.firstOrNull()?.position, "standing" to found.firstOrNull()?.standing,
                    "existing" to found.firstOrNull()?.existing, "candidates" to found.size,
                    "keepsMarker" to found.any { it.position == building.control },
                    "routeLength" to found.firstOrNull()?.route?.size)
                for (city in cities.values) for (current in city.buildings.filter { it.schematicId == building.schematicId }) {
                    val record = actual.single { it["cityId"] == city.id && it["buildingId"] == current.id }
                    if (record["status"] != "source-unavailable") continue
                    val candidates = CivsBuildingControls.authored(current, snapshot)
                    val chosen = candidates.firstOrNull { it.position == current.control } ?: candidates.firstOrNull()
                    record["status"] = when { chosen == null -> "no-interior-candidate"
                        chosen.position == current.control -> "source-interior-kept"
                        else -> "native-relocation-required" }
                    record["after"] = chosen?.position
                    record["standing"] = chosen?.standing
                    record["route"] = chosen?.route
                    record["existing"] = chosen?.existing
                    record["retirePrevious"] = chosen?.retirePrevious
                    record["sha256"] = snapshot.fileSha256
                }
            } }
            System.getenv("CIVS_CONTROL_AUDIT_OUT")?.let { path ->
                Files.writeString(Path.of(path), gson.toJson(results))
                if (actual.isNotEmpty()) Files.writeString(Path.of(path.removeSuffix(".json") + "-current.json"), gson.toJson(actual))
            }
            val missing = results.filter { it["after"] == null }
            println("ARCCIVS_CONTROL_CATALOG total=${results.size} interior=${results.size - missing.size} " +
                "unresolved=${missing.size} types=${results.map { it["type"] }.distinct().size}")
            results.isNotEmpty() shouldBe true
            if (actual.isNotEmpty()) println("ARCCIVS_CONTROL_CURRENT ${actual.groupingBy { it["status"] }.eachCount()}")
        }
})

private fun controlRoom(roof: Boolean = true, markerInside: Boolean = false): ControlSnapshot {
    val size = listOf(9, 5, 11)
    val blocks = buildList {
        for (y in 0 until size[1]) for (z in 0 until size[2]) for (x in 0 until size[0]) add(when {
            !markerInside && x == 5 && y == 1 && z == 10 -> "minecraft:structure_void"
            x == 1 && y == 1 && z == 10 -> "minecraft:barrel[facing=up,open=false]"
            x == 2 && y == 1 && z == 6 -> if (markerInside) "minecraft:structure_void" else "minecraft:barrel[facing=up,open=false]"
            y == 0 || roof && y == 4 || y in 1..3 && (x == 0 || x == 8 || z == 0 || z == 9 && x != 4) -> "minecraft:stone"
            else -> "minecraft:air"
        })
    }
    return ControlSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(4, 0, 11), size, blocks)
}

private fun controlBuilding(snapshot: ArcSchematicSnapshot, type: BuildingType, turns: Int): CivBuilding {
    val anchor = CivPosition(20, 63, 20)
    fun point(p: ArcSchematicPoint): CivPosition = CivsBlueprintGeometry.rotate(ArcSchematicPoint(
        p.x - snapshot.origin.x, p.y - snapshot.origin.y, p.z - snapshot.origin.z), turns).let {
        CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z)
    }
    val corners = listOf(snapshot.minimum.x, snapshot.maximum.x).flatMap { x ->
        listOf(snapshot.minimum.z, snapshot.maximum.z).flatMap { z ->
            listOf(snapshot.minimum.y, snapshot.maximum.y).map { y -> point(ArcSchematicPoint(x, y, z)) }
        }
    }
    return CivBuilding(UUID(0, 1), type, 1, anchor, turns * 90, "fixture/control-room",
        CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
            CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })),
        point(CivsBlueprintGeometry.entrance(snapshot).position), BuildingPhase.ACTIVE,
        point(CivsBlueprintGeometry.controlMarker(snapshot)))
}

private class ControlSnapshot(override val minimum: ArcSchematicPoint, override val origin: ArcSchematicPoint,
    size: List<Int>, private val states: List<String>, override val fileSha256: String = "a".repeat(64)) : ArcSchematicSnapshot {
    override val sizeX = size[0]
    override val sizeY = size[1]
    override val sizeZ = size[2]
    override val maximum = ArcSchematicPoint(minimum.x + sizeX - 1, minimum.y + sizeY - 1, minimum.z + sizeZ - 1)
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount = states.count { it != "minecraft:barrier" }
    override val hasBiomes = false
    override val entityCount = 0
    override val contentSha256 = fileSha256
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in minimum.x..maximum.x && y in minimum.y..maximum.y && z in minimum.z..maximum.z)
        val state = states[x - minimum.x + (z - minimum.z) * sizeX + (y - minimum.y) * sizeX * sizeZ]
        return ArcSchematicBlock(ArcSchematicPoint(x, y, z), state,
            state in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air"), state == "minecraft:barrier")
    }
    override fun blocks(): List<ArcSchematicBlock> = buildList {
        for (y in minimum.y..maximum.y) for (z in minimum.z..maximum.z) for (x in minimum.x..maximum.x) add(blockAt(x, y, z))
    }
}
