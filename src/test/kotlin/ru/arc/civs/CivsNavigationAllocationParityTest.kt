package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class CivsNavigationAllocationParityTest : FunSpec({
    test("node points preserve exact coordinates and identity without adding cached fields to diagnostic JSON") {
        val gson = com.google.gson.Gson()
        for (x in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE))
            for (height in listOf(-64.0, -0.0, 63.9375, 64.0625, 64.5)) {
                val node = CitizenSearchNode(x, height, -1, climbing = true, initial = true)
                val point = node.point()
                node.point() shouldBeSameInstanceAs point
                point.x.toBits() shouldBe (x + .5).toBits()
                point.y.toBits() shouldBe height.toBits()
                point.z shouldBe -.5
                node.copy().point() shouldBe point
                node.copy(y = height + 1).point().y shouldBe height + 1
                gson.toJsonTree(node).asJsonObject.keySet() shouldBe setOf("x", "y", "z", "climbing", "initial")
                val decoded = gson.fromJson(gson.toJson(node), CitizenSearchNode::class.java)
                decoded shouldBe node
                decoded.point() shouldBe point
                decoded.point() shouldBeSameInstanceAs decoded.point()
            }
    }

    test("candidate order and climbing identities match the prior enumeration across captured shapes") {
        val random = Random(311)
        val full = CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val palette = listOf(
            CitizenTerrainBlock(true, false, false, false, false, true, 1.0, emptyList()),
            CitizenTerrainBlock(true, false, false, false, false, true, 1.0, listOf(full, full)),
            CitizenTerrainBlock(true, false, false, false, false, true, 1.0, listOf(full.copy(maxY = .5))),
            CitizenTerrainBlock(true, false, false, false, false, true, 1.0,
                listOf(full.copy(maxY = .5), full.copy(minX = .5), full)),
            CitizenTerrainBlock(true, false, false, true, false, true, 1.0, listOf(full)),
            CitizenTerrainBlock(true, true, false, false, false, true, 1.0, listOf(full)),
            CitizenTerrainBlock(true, false, true, false, false, true, 1.0, listOf(full)),
            CitizenTerrainBlock(true, false, false, false, false, false, 1.0, listOf(full)),
            CitizenTerrainBlock.UNAVAILABLE,
        )
        for (base in listOf(0, -1800, Int.MIN_VALUE + 4, Int.MAX_VALUE - 4))
            for (height in listOf(63.9375, 64.0, 64.0625, 64.5))
                for (initial in listOf(false, true)) for (climbing in listOf(false, true)) repeat(4) {
                    val node = CitizenSearchNode(base, height, base, climbing, initial)
                    val probe = CitizenSearchProbe(node, node.point())
                    val blocks = buildMap {
                        for (x in base - 1..base + 1) for (z in base - 1..base + 1) for (y in 61..67)
                            put(CivPosition(x, y, z), palette[random.nextInt(palette.size)])
                    }
                    withClue("node=$node") {
                        val previous = previousSearchCandidates(probe, blocks, -64, 320)
                        citizenSearchCandidates(probe, blocks, -64, 320) shouldBe previous
                        for (incoming in listOf(false, true)) {
                            val directed = probe.copy(incoming = incoming)
                            val expectedChecks = mutableListOf<Pair<CitizenPoint, CitizenPoint>>()
                            val actualChecks = mutableListOf<Pair<CitizenPoint, CitizenPoint>>()
                            fun permitted(checks: MutableList<Pair<CitizenPoint, CitizenPoint>>, from: CitizenPoint,
                                to: CitizenPoint): Boolean {
                                checks += from to to
                                return from.x <= to.x && from.z <= to.z
                            }
                            val expected = previous.map { edge ->
                                val point = edge.node.point()
                                edge.copy(permitted = if (incoming) permitted(expectedChecks, point, probe.from)
                                    else permitted(expectedChecks, probe.from, point))
                            }
                            citizenSearchCandidates(directed, blocks, -64, 320) { from, to ->
                                permitted(actualChecks, from, to)
                            } shouldBe expected
                            actualChecks shouldBe expectedChecks
                        }
                    }
                }
    }

    test("support samples and short circuit order stay exact for centred fractional and general segments") {
        val segments = buildList {
            for (start in listOf(CitizenPoint(.5, 64.0, .5), CitizenPoint(-10.5, 63.9375, -20.5),
                CitizenPoint(29_999_998.5, 64.0, -29_999_998.5)))
                for (dx in -1..1) for (dz in -1..1) add(start to start.copy(x = start.x + dx, z = start.z + dz))
            add(CitizenPoint(.049769001525, 80.0, .5) to CitizenPoint(1.5, 80.0, -.5))
            add(CitizenPoint(.86499999999, 76.875, .5) to CitizenPoint(1.5, 76.875, .5))
            add(CitizenPoint(.2, 64.0, .2 + 1e-11) to CitizenPoint(2.5, 64.0, 2.5))
            add(CitizenPoint(-3.2, 64.0, 1.4) to CitizenPoint(2.1, 64.0, -4.3))
            add(CitizenPoint(.5, 63.5, .5) to CitizenPoint(1.5, 64.0, 1.5))
            add(CitizenPoint(.5, 64.0, .5) to CitizenPoint(4.5, 64.0, .5))
        }
        for ((from, to) in segments) for (separateEndpoint in listOf(false, true)) for (failAt in -1..12) {
            fun trace(previous: Boolean): Pair<Boolean, List<Pair<String, CitizenPoint>>> {
                val samples = mutableListOf<Pair<String, CitizenPoint>>()
                fun sample(kind: String, at: CitizenPoint): Boolean {
                    samples += kind to at
                    return samples.lastIndex != failAt
                }
                val support: (CitizenPoint) -> Boolean = { sample("support", it) }
                val endpoint: ((CitizenPoint) -> Boolean)? = if (separateEndpoint) ({ sample("endpoint", it) }) else null
                val clear = if (previous) previousFlatSupportClear(from, to, endpoint, support)
                    else citizenFlatSupportClear(from, to, endpoint, support)
                return clear to samples
            }
            withClue("$from -> $to endpoint=$separateEndpoint failAt=$failAt") { trace(false) shouldBe trace(true) }
        }
    }

    test("route cells retain exact extreme coordinates canonical keys and immutable map entries") {
        val cells = CitizenSnapshotCells()
        val expected = linkedMapOf<CivPosition, CitizenTerrainBlock>()
        for (x in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE))
            for (y in listOf(Int.MIN_VALUE, -64, 0, 320, Int.MAX_VALUE))
                for (z in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
                    val point = CivPosition(x, y, z)
                    val block = CitizenTerrainBlock.UNAVAILABLE.copy(cost = expected.size.toDouble())
                    expected[point] = block
                    cells.add(point, block).key shouldBeSameInstanceAs point
                }
        cells shouldBe expected
        cells.hashCode() shouldBe expected.hashCode()
        cells.size shouldBe expected.size
        for ((point, block) in expected) {
            cells.cell(point.x, point.y, point.z)?.key shouldBeSameInstanceAs point
            cells[point] shouldBeSameInstanceAs block
        }
        cells[CivPosition(1, 64, 1)] shouldBe null
        val first = expected.entries.first()
        shouldThrow<IllegalStateException> { cells.add(first.key, CitizenTerrainBlock.UNAVAILABLE) }
        shouldThrow<UnsupportedOperationException> { cells.cell(first.key.x, first.key.y, first.key.z)!!.setValue(CitizenTerrainBlock.UNAVAILABLE) }
        cells shouldBe expected
        val other = CitizenSnapshotCells()
        val replacement = CitizenTerrainBlock.UNAVAILABLE.copy(cost = 42.0)
        other.add(first.key.copy(), replacement)
        other[first.key] shouldBeSameInstanceAs replacement
        cells[first.key] shouldBeSameInstanceAs first.value
    }
})

// Frozen .311 oracles: verify observable candidate/sample order, not allocation counts.
private fun previousSearchCandidates(probe: CitizenSearchProbe, blocks: Map<CivPosition, CitizenTerrainBlock>,
                                     minimumY: Int, maximumY: Int): List<CitizenSearchEdge> {
    val node = probe.node
    val result = linkedSetOf<CitizenSearchNode>()
    val base = floor(node.y).toInt()
    fun standing(x: Int, z: Int) {
        for (by in base - 2..base + 1) {
            if (by < minimumY || by + 2 >= maximumY) continue
            val block = blocks[x, by, z] ?: continue
            // Opening a passage must never remove the foothold selected by the planner.
            if (!block.loaded || block.liquid || block.door) continue
            if (block.climbable && block.allowed && citizenStepHeightAllowed(node.y, by.toDouble()))
                result += CitizenSearchNode(x, by.toDouble(), z, climbing = true)
            for (box in block.boxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val height = by + box.maxY
                if (!citizenStepHeightAllowed(node.y, height)) continue
                val feet = blocks[x, floor(height + .001).toInt(), z] ?: continue
                if (!feet.loaded || !feet.allowed) continue
                // Match packet movement's centre-support row using only captured geometry.
                if (!feet.climbable) {
                    val supportY = citizenSupportY(height)
                    val support = blocks[x, supportY, z] ?: continue
                    if (!support.loaded || support.liquid || support.door || support.boxes.none {
                            abs(supportY + it.maxY - height) <= .13 &&
                                .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ
                        }) continue
                }
                result += CitizenSearchNode(x, height, z)
            }
        }
    }
    if (node.initial) standing(node.x, node.z)
    for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) standing(node.x + dx, node.z + dz)
    val onLadder = blocks[node.x, base, node.z]?.climbable == true
    if (onLadder) for (dy in -1..1) {
        val next = blocks[node.x, base + dy, node.z] ?: continue
        if (next.loaded && next.climbable && next.allowed && citizenStepHeightAllowed(node.y, (base + dy).toDouble()))
            result += CitizenSearchNode(node.x, (base + dy).toDouble(), node.z, climbing = true)
    }
    return result.map { next ->
        val action = when {
            next.climbing && onLadder && next.x == node.x && next.z == node.z -> CitizenRouteAction.CLIMB
            next.climbing -> CitizenRouteAction.LADDER_ENTER
            node.climbing || onLadder -> CitizenRouteAction.LADDER_EXIT
            abs(next.y - node.y) > .0001 -> CitizenRouteAction.STEP
            else -> CitizenRouteAction.WALK
        }
        CitizenSearchEdge(next, action, true)
    }
}

private fun previousFlatSupportClear(from: CitizenPoint, to: CitizenPoint, endpointSupport: ((CitizenPoint) -> Boolean)? = null,
                                    supported: (CitizenPoint) -> Boolean): Boolean {
    val boundaries = sortedSetOf(0.0, 1.0)
    for ((start, end) in listOf(from.x to to.x, from.z to to.z)) {
        if (start == end) continue
        for (edge in floor(min(start, end) * 2).toInt() + 1..floor(max(start, end) * 2).toInt()) {
            val fraction = (edge * .5 - start) / (end - start)
            if (fraction > 0.0 && fraction < 1.0) boundaries += fraction
        }
    }
    return (endpointSupport ?: supported)(to) && boundaries.zipWithNext().all { (first, second) ->
        val fraction = (first + second) / 2
        // The two axes can report the same grid corner a few floating-point units apart.
        second - first <= .000000001 || supported(CitizenPoint(from.x + (to.x - from.x) * fraction, from.y,
            from.z + (to.z - from.z) * fraction))
    }
}
