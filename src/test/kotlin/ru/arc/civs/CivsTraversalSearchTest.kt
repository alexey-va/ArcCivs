package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.floor

class CivsTraversalSearchTest : FunSpec({
    val full = CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    fun point(x: Int, y: Int = 64, z: Int = 0) = CitizenPoint(x + .5, y.toDouble(), z + .5)
    fun route(target: CitizenPoint, start: CitizenPoint = point(0), width: Int = 7, depth: Int = 1,
              special: Boolean = true, unknown: CivPosition? = null,
              allowed: (CivPosition) -> Boolean = { true },
              floorPresent: (CivPosition) -> Boolean = { true },
              solid: (CivPosition) -> Boolean): CitizenSearchResult {
        val search = CitizenNavigationSearch(start, target, 0, 256, emptyList(), specialTraversal = special,
            traversalAllowed = allowed)
        fun capture(probe: CitizenSearchProbe): CitizenSearchCapture {
            val n = probe.node
            val base = floor(n.y).toInt()
            fun rows(ys: IntRange) = buildMap {
                for (x in n.x - 1..n.x + 1) for (z in n.z - 1..n.z + 1) for (y in ys) {
                    val p = CivPosition(x, y, z)
                    val within = x in 0 until width && z in 0 until depth
                    put(p, if (p == unknown) CitizenTerrainBlock.UNAVAILABLE else CitizenTerrainBlock(true, false, false,
                        false, false, within, 1.0, if (within && (y == 63 && floorPresent(p) || solid(p))) listOf(full) else emptyList()))
                }
            }
            var levels = base - 3..base + 3
            var blocks = rows(levels)
            while (true) {
                val extended = citizenGrappleRows(probe, blocks, allowed) ?: break
                if (extended == levels) break
                levels = extended
                blocks = rows(levels)
            }
            return CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
        }
        var result = search.advance(emptyList())
        var batches = 0
        while (result.probes.isNotEmpty()) {
            check(++batches < 100)
            result = search.advance(result.probes.map(::capture))
        }
        return result
    }

    test("a walk through the open gate wins and a sealed wall uses its real crest and far landing") {
        val target = point(6)
        fun wall(p: CivPosition) = p.x == 3 && p.y in 64..70
        val gate = route(target, depth = 3) { p -> wall(p) && p.z != 2 }
        gate.continuation shouldBe false
        gate.path!!.last().point shouldBe target
        gate.path.all { it.action.isGroundStep } shouldBe true
        gate.path.any { it.point == point(3, z = 2) } shouldBe true

        val crossing = route(target, solid = ::wall)
        crossing.continuation shouldBe false
        crossing.path!!.last().point shouldBe target
        crossing.path.filter { it.action == CitizenRouteAction.GRAPPLE }.map { it.point } shouldBe
            listOf(point(3, 71), point(4))
        route(target, allowed = { it != CivPosition(3, 67, 0) }, solid = ::wall).path?.lastOrNull()?.point
            .let { it == target } shouldBe false
        val missing = route(target, unknown = CivPosition(3, 67, 0), solid = ::wall)
        (missing.failure ?: missing.continuationReason) shouldBe "chunk-unloaded"
        (missing.path?.lastOrNull()?.point == target) shouldBe false
        val floating = route(point(3, 69)) { p -> p.x == 3 && p.y == 68 }
        (floating.path?.lastOrNull()?.point == point(3, 69)) shouldBe false
    }

    test("a low tunnel needs a continuous floor and a standing exit for arrival or a partial route") {
        fun roof(p: CivPosition) = p.x in 2..5 && p.y == 65
        val target = point(6)
        val tunnel = route(target, solid = ::roof)
        tunnel.continuation shouldBe false
        tunnel.path!!.last().point shouldBe target
        tunnel.path.filter { it.action == CitizenRouteAction.CRAWL }.map { it.point } shouldBe (2..6).map { point(it) }
        val ordinary = route(target, special = false, solid = ::roof)
        (ordinary.path?.lastOrNull()?.point == target) shouldBe false
        val sealed = route(point(4), width = 6, solid = ::roof)
        (sealed.path?.lastOrNull()?.point?.x ?: 0.0) shouldBe 1.5
        val proneGoal = route(point(3), start = point(3), solid = ::roof)
        (proneGoal.path?.lastOrNull()?.point == point(3)) shouldBe false
        val gap = route(target, unknown = CivPosition(3, 63, 0), solid = ::roof)
        (gap.failure ?: gap.continuationReason) shouldBe "chunk-unloaded"
        (gap.path?.lastOrNull()?.point == target) shouldBe false
        val hole = route(target, floorPresent = { it.x != 3 }, solid = ::roof)
        (hole.path?.lastOrNull()?.point == target) shouldBe false
        val lowStart = route(target, start = CitizenPoint(3.32, 64.0, .5), solid = ::roof)
        lowStart.continuation shouldBe false
        lowStart.path!!.last().point shouldBe target
        lowStart.path.first().action shouldBe CitizenRouteAction.CRAWL
    }
})
