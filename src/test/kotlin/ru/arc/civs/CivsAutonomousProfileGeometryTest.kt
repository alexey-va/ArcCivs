package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import java.util.UUID

/** Spatial contracts only: private schematic voxels are neither fixtures nor test dependencies. */
class CivsAutonomousProfileGeometryTest : FunSpec({
    for (style in CivsBlueprints.STYLES) for (rotation in listOf(0, 90, 180, 270)) {
        test("$style profile fits real family bounds at radius96 with hall rotation$rotation") {
            val hall = profileBuilding(style, BuildingType.TOWN_HALL, 1, CivPosition(120, 64, -80), rotation)
                .copy(phase = BuildingPhase.SITE)
            // Service founds around the authored control, which is offset from the entrance.
            var city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Geometry", UUID.randomUUID(),
                requireNotNull(hall.control), 96, style, Stock(100_000, 100_000, 100_000),
                listOf(hall), emptyList(), autonomy = CivAutonomy())
            var goals = 0
            var newSites = 0
            while (true) {
                val goal = CivsAutonomy.nextGoal(city) ?: break
                check(++goals <= 22) { "Autonomous profile did not finish" }
                val existing = goal.buildingId?.let(city::building)
                if (existing != null) {
                    val upgraded = profileBuilding(style, existing.type, goal.targetLevel, existing.anchor,
                        existing.rotation, existing.id)
                    upgraded.entrance shouldBe existing.entrance
                    civsContainsBox(CivsAutonomousGeometry.futureBounds(city, existing), upgraded.bounds) shouldBe true
                    city = city.copy(buildings = city.buildings.map { if (it.id == existing.id) upgraded else it })
                    continue
                }

                val current = profileBuilding(style, goal.type, 1)
                val future = profileBuilding(style, goal.type, 3, id = current.id)
                val candidates = CivsAutonomousGeometry.candidates(city, current, future)
                candidates shouldBe CivsAutonomousGeometry.candidates(city, current, future)
                check(candidates.size in 1..64) { "$style ${goal.type} has ${candidates.size} candidates" }
                var columns = 0
                val selected = candidates.map { CivsAutonomousGeometry.atHeight(it.building, 64) }.firstOrNull { candidate ->
                    val b = candidate.bounds
                    columns += (b.max.x - b.min.x + 1) * (b.max.z - b.min.z + 1)
                    check(columns <= CivsAutonomousPlacement.MAXIMUM_SURFACE_COLUMNS)
                    profileConnected(city, candidate)
                } ?: error("$style ${goal.type} has no connected site")
                val placed = profileBuilding(style, goal.type, 1, selected.anchor, selected.rotation, current.id)
                civsContainsBox(selected.bounds, placed.bounds) shouldBe true
                placed.entrance shouldBe selected.entrance
                city = city.copy(buildings = city.buildings + placed)
                newSites++
            }

            newSites shouldBe 10
            city.buildings.map { it.type }.toSet() shouldBe CivsAutonomy.profile.toSet()
            city.buildings.all { it.level == 2 && it.phase == BuildingPhase.ACTIVE } shouldBe true
            city.buildings.forEach { building ->
                val bounds = CivsAutonomousGeometry.futureBounds(city, building)
                city.contains(bounds.min.x, bounds.min.z) shouldBe true
                city.contains(bounds.max.x, bounds.max.z) shouldBe true
                val others = city.buildings.filterNot { it.id == building.id }
                others.none { CivsAutonomousGeometry.overlaps(bounds, CivsAutonomousGeometry.futureBounds(city, it), 2) } shouldBe true
                CivsAutonomousGeometry.approach(building).forEach { point ->
                    city.contains(point.x, point.z) shouldBe true
                    others.none { CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(city, it), point) } shouldBe true
                }
                profileConnected(city.copy(buildings = others), building) shouldBe true
            }
            val farm = city.buildings.single { it.type == BuildingType.FARM }
            (CivsWorksiteLayouts.field(farm).size >= 72) shouldBe true
            CivsWorksiteLayouts.irrigation(farm).isNotEmpty() shouldBe true
        }
    }
})

/** Independent flat-ground oracle: walk around every complete reserved footprint, never through it. */
private fun profileConnected(city: Settlement, candidate: CivBuilding): Boolean {
    val source = city.buildings.filter(CivsRules::isDepot).minByOrNull {
        val dx = it.entrance.x.toLong() - candidate.entrance.x
        val dz = it.entrance.z.toLong() - candidate.entrance.z
        dx * dx + dz * dz
    } ?: return false
    if (!CivsRules.workRouteWithinReach(city, candidate.entrance)) return false
    val obstacles = city.buildings.map { CivsAutonomousGeometry.futureBounds(city, it) } +
        CivsAutonomousGeometry.futureBounds(city, candidate)
    val start = source.entrance.x to source.entrance.z
    val goal = candidate.entrance.x to candidate.entrance.z
    val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
    val seen = hashSetOf(start)
    while (queue.isNotEmpty()) {
        val point = queue.removeFirst()
        if (point == goal) return true
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val next = point.first + dx to point.second + dz
            if (!city.contains(next.first, next.second) || next in seen || obstacles.any {
                    next.first in it.min.x..it.max.x && next.second in it.min.z..it.max.z
                }) continue
            seen += next
            queue += next
        }
    }
    return false
}

private fun profileBuilding(style: String, type: BuildingType, level: Int,
    anchor: CivPosition = CivPosition(0, 0, 0), rotation: Int = 0, id: UUID = UUID.randomUUID()): CivBuilding {
    val initial = CivBuilding(id, type, level, anchor, rotation, CivsBlueprints.schematicId(style, type, level),
        CivBox(anchor, anchor), anchor.copy(y = anchor.y + 1), BuildingPhase.ACTIVE)
    val site = CivsWorksiteLayouts.site(initial)
    val frame = if (site != null) {
        val box = site.getAsJsonObject("schematicBounds")
        ProfileFrame(CivsWorksiteLayouts.vector(box["min"]), CivsWorksiteLayouts.vector(box["max"]))
    } else profileFrames.getValue(style).getValue(type)
    fun point(local: CivPosition) = CivsWorksiteLayouts.point(initial, local)
    val a = point(frame.min)
    val b = point(frame.max)
    return initial.copy(bounds = CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
        CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))), control = frame.control?.let(::point))
}

private data class ProfileFrame(val min: CivPosition, val max: CivPosition, val control: CivPosition? = null)

/* Header coordinates, not source voxels. Hall/house/warehouse: hash-verified private
 * arccivs-20261017/curated/curation-report.json, identical L1..3 frames. The later chest
 * overlay retains warehouse dimensions. Builder: deployed nine-file header inventory
 * 2026-10-23, RCNet job cfde06dcfc77d03f26a2c978b506a0347c27caa31238f84087e72b2c2516803c;
 * each style has identical L1..3 size and WorldEditOffset. Producer frames and full farm
 * soil/irrigation coordinates come directly from the versioned worksites.json contract. */
private val profileFrames = mapOf(
    "nordic" to mapOf(
        BuildingType.TOWN_HALL to ProfileFrame(CivPosition(-16, -1, -26), CivPosition(11, 26, -1), CivPosition(2, 1, -18)),
        BuildingType.BUILDER_HUT to ProfileFrame(CivPosition(-7, -1, -12), CivPosition(10, 22, -1)),
        BuildingType.HOUSE to ProfileFrame(CivPosition(-5, 0, -11), CivPosition(9, 23, -1)),
        BuildingType.WAREHOUSE to ProfileFrame(CivPosition(-11, -1, -31), CivPosition(10, 21, -1)),
    ),
    "medievaloak" to mapOf(
        BuildingType.TOWN_HALL to ProfileFrame(CivPosition(-11, -3, -15), CivPosition(10, 22, -1), CivPosition(2, 2, -9)),
        BuildingType.BUILDER_HUT to ProfileFrame(CivPosition(-3, -4, -14), CivPosition(7, 15, -1)),
        BuildingType.HOUSE to ProfileFrame(CivPosition(-2, 0, -15), CivPosition(11, 18, -1)),
        BuildingType.WAREHOUSE to ProfileFrame(CivPosition(-4, -1, -23), CivPosition(20, 26, -1)),
    ),
    "caledonia" to mapOf(
        BuildingType.TOWN_HALL to ProfileFrame(CivPosition(-8, 0, -19), CivPosition(10, 35, -1), CivPosition(1, 4, -14)),
        BuildingType.BUILDER_HUT to ProfileFrame(CivPosition(-6, -4, -13), CivPosition(6, 12, -1)),
        BuildingType.HOUSE to ProfileFrame(CivPosition(-7, 0, -19), CivPosition(5, 16, -1)),
        BuildingType.WAREHOUSE to ProfileFrame(CivPosition(-8, 0, -19), CivPosition(10, 17, -1)),
    ),
)
