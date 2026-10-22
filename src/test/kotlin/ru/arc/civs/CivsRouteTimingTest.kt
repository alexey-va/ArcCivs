package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsRouteTimingTest : FunSpec({
    for ((allowance, preparation) in listOf(18_000L to 20_000L, 90_000L to 100_000L))
        test("$preparation milliseconds of preparation preserves the full $allowance movement allowance") {
            val timing = CitizenRouteTiming(1_000L, allowance)
            val preparedAt = 1_000L + preparation

            timing.preparationElapsed(preparedAt) shouldBe preparation
            timing.elapsed(preparedAt) shouldBe 0L
            timing.timedOut(preparedAt, searching = true) shouldBe false

            timing.ready(preparedAt)
            timing.elapsed(preparedAt + allowance - 1) shouldBe allowance - 1
            timing.timedOut(preparedAt + allowance - 1, searching = false) shouldBe false
            timing.timedOut(preparedAt + allowance + 1, searching = false) shouldBe true
        }

    test("observing a ready route repeatedly does not refresh its movement deadline") {
        val timing = CitizenRouteTiming(1_000L, 18_000L)
        timing.ready(2_000L)
        timing.ready(12_000L)

        timing.elapsed(19_999L) shouldBe 17_999L
        timing.timedOut(19_999L, searching = false) shouldBe false
        timing.timedOut(20_001L, searching = true) shouldBe false
        timing.timedOut(20_001L, searching = false) shouldBe true
    }

    test("replanning pauses spent movement time and resumes only the remaining allowance") {
        val timing = CitizenRouteTiming(1_000L, 18_000L)
        timing.ready(2_000L)
        timing.elapsed(7_000L) shouldBe 5_000L
        timing.prepare(7_000L)

        timing.preparationElapsed(7_000L) shouldBe 0L
        timing.preparationElapsed(127_000L) shouldBe 120_000L
        timing.elapsed(127_000L) shouldBe 5_000L
        timing.timedOut(127_000L, searching = true) shouldBe false

        timing.ready(127_000L)
        timing.elapsed(139_999L) shouldBe 17_999L
        timing.timedOut(139_999L, searching = false) shouldBe false
        timing.timedOut(140_001L, searching = false) shouldBe true
    }

    test("a backwards clock cannot make movement elapsed negative or reduce already observed time") {
        val timing = CitizenRouteTiming(10_000L, 18_000L)
        timing.preparationElapsed(9_000L) shouldBe 0L
        timing.elapsed(9_000L) shouldBe 0L
        timing.ready(10_000L)

        timing.elapsed(9_000L) shouldBe 0L
        timing.elapsed(13_000L) shouldBe 3_000L
        timing.elapsed(12_000L) shouldBe 3_000L
        timing.prepare(11_000L)
        timing.elapsed(10_000L) shouldBe 3_000L
        timing.preparationElapsed(10_000L) shouldBe 0L
        timing.timedOut(10_000L, searching = true) shouldBe false
    }
})
