package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPerimeterCrossingsTest : FunSpec({
    fun box(x0: Int, z0: Int, x1: Int, z1: Int, y0: Int = 64, y1: Int = y0) =
        CivBox(CivPosition(x0, y0, z0), CivPosition(x1, y1, z1))
    fun frame() = CivSettlementFrame(CivPosition(0, 64, 0), 0, 24, box(-24, -24, 24, 24),
        box(-2, -2, 2, 2), emptyList(), listOf(box(-12, -1, 12, 1)), emptyList())
    fun road(x: Int) = (-2..2).map { CivPosition(x, 65, it) }
    fun has(box: CivBox, p: CivPosition) = p.x in box.min.x..box.max.x && p.z in box.min.z..box.max.z

    test("a new fourth frontage preserves its north passage while an extra retained north crossing gets its own opening") {
        val city = settlementPlanCity().copy(radius = 128)
        val source = CivsSettlementLayout.frame(city)
        source.gates.map { it.side }.toSet() shouldBe setOf(0, 1, 2, 3)
        val crossing = (-74..-70).map { CivPosition(12, 65, it) }
        val result = CivsPerimeterCrossings.adapt(source, listOf(crossing), emptyList())
        result.gates.size shouldBe 5
        source.gates.all { old -> old in result.gates } shouldBe true
        crossing.none { p -> result.wallObstacles.any { has(it, p) } } shouldBe true
        result.gates.single { it.center.x == 12 }.side shouldBe 0
        CivsSettlementLayout.frame(city.copy(autonomy = CivAutonomy(layoutHalfWidth = 56))).gates.size shouldBe 3
    }

    test("retained routes cut oriented gates while preserving the rest of a wall") {
        val source = frame()
        val routes = listOf(road(-6), road(6))
        val result = CivsPerimeterCrossings.adapt(source, routes, emptyList())
        result.gates.size shouldBe 2
        result.gates.all { it.side == 0 } shouldBe true
        routes.flatten().none { p -> result.wallObstacles.any { has(it, p) } } shouldBe true
        result.wallObstacles.any { has(it, CivPosition(0, 64, 0)) } shouldBe true
        result shouldBe CivsPerimeterCrossings.adapt(source, routes, emptyList())
    }

    test("accepted gates remain openings after their route disappears") {
        val source = frame()
        val passage = box(-2, -2, 2, 2, 64, 70)
        val gate = CivSettlementPiece(UUID(0, 1), BuildingType.GATE, passage,
            CivPosition(-3, 65, 0), emptyList())
        val result = CivsPerimeterCrossings.adapt(source, emptyList(), listOf(gate))
        result.gates.single().passage shouldBe box(-2, -2, 2, 2)
        result.wallObstacles.none { has(it, CivPosition(0, 64, 0)) } shouldBe true
    }

    test("a partially intersecting accepted passage leaves the rest of each wall column intact") {
        val source = frame()
        val passage = box(-2, 0, 2, 2, 64, 70)
        val gate = CivSettlementPiece(UUID(0, 3), BuildingType.GATE, passage,
            CivPosition(-3, 65, 0), emptyList())
        val result = CivsPerimeterCrossings.adapt(source, emptyList(), listOf(gate))
        result.wallObstacles.any { has(it, CivPosition(0, 64, -1)) } shouldBe true
        result.wallObstacles.none { has(it, CivPosition(0, 64, 0)) } shouldBe true
    }

    test("Auto2 retained crossings get gates without replacing the twelve accepted pieces") {
        val origin = CivPosition(-1883, 67, -2760)
        val walls = listOf(
            box(-1940, -2817, -1938, -2703), box(-1828, -2756, -1826, -2703),
            box(-1828, -2817, -1826, -2762), box(-1937, -2705, -1863, -2703),
            box(-1857, -2705, -1829, -2703), box(-1937, -2817, -1863, -2815),
            box(-1857, -2817, -1829, -2815))
        val gates = listOf(
            CivSettlementGate(1, CivPosition(-1860, 67, -2816), box(-1862, -2818, -1858, -2814)),
            CivSettlementGate(2, CivPosition(-1827, 67, -2759), box(-1829, -2761, -1825, -2757)),
            CivSettlementGate(3, CivPosition(-1860, 67, -2704), box(-1862, -2706, -1858, -2702)))
        val source = CivSettlementFrame(origin, 270, 56, box(-1939, -2816, -1827, -2704),
            box(-1867, -2767, -1855, -2751), gates, walls, emptyList())
        val retainedGate = listOf(box(-1862, -2706, -1858, -2702, 62, 68), box(-1829, -2761, -1825, -2757, 63, 68))
        val retainedWalls = listOf(
            box(-1940, -2793, -1938, -2770, 63, 67), box(-1940, -2769, -1938, -2746, 62, 67),
            box(-1937, -2817, -1914, -2815, 62, 70), box(-1913, -2817, -1890, -2815, 62, 69),
            box(-1865, -2705, -1863, -2703, 62, 66), box(-1833, -2705, -1829, -2703, 63, 69),
            box(-1828, -2793, -1826, -2770, 63, 68), box(-1828, -2769, -1826, -2762, 63, 68),
            box(-1828, -2732, -1826, -2709, 62, 68), box(-1828, -2708, -1826, -2703, 63, 67))
        val accepted = (retainedGate.map { BuildingType.GATE to it } + retainedWalls.map { BuildingType.WALL to it })
            .mapIndexed { index, (type, bounds) -> CivSettlementPiece(UUID(1, index.toLong()), type, bounds,
                bounds.min.copy(x = bounds.min.x - 1), emptyList()) }
        val routes = listOf(
            (-2705..-2703).map { CivPosition(-1868, 64, it) },
            (-2705..-2703).map { CivPosition(-1846, 65, it) },
            listOf(CivPosition(-1828, 64, -2738), CivPosition(-1827, 64, -2738),
                CivPosition(-1827, 64, -2737), CivPosition(-1826, 64, -2737)))
        val result = CivsPerimeterCrossings.adapt(source, routes, accepted)
        result.gates.size shouldBe 6
        routes.flatten().none { p -> result.wallObstacles.any { has(it, p) } } shouldBe true
        accepted.size shouldBe 12
        accepted.filter { it.type == BuildingType.GATE }.all { old -> result.gates.any {
            it.passage.min.x == old.bounds.min.x && it.passage.max.x == old.bounds.max.x &&
                it.passage.min.z == old.bounds.min.z && it.passage.max.z == old.bounds.max.z
        } } shouldBe true
    }

    test("an accepted wall cannot be replaced by an inferred gate") {
        val source = frame()
        val existing = CivSettlementPiece(UUID(0, 2), BuildingType.WALL, box(-8, -1, -4, 1, 64, 68),
            CivPosition(-9, 65, 0), emptyList())
        val result = CivsPerimeterCrossings.adapt(source, listOf(road(-6)), listOf(existing))
        result.gates shouldBe emptyList()
        result.wallObstacles shouldBe source.wallObstacles
    }
})
