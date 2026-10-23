package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsMineRouteEpisodeTest : FunSpec({
    val worker = UUID.randomUUID()
    val building = UUID.randomUUID()
    val standing = CivPosition(-96, 55, -47)
    fun work(focus: CivPosition) = CivWorldWork(worker, 64, building, standing, emptyList(),
        status = "digging-mine", miningStep = 83, focus = focus, miningPlanVersion = 3)
    val first = work(CivPosition(-97, 58, -49))
    val second = work(CivPosition(-96, 58, -49))
    val key = requireNotNull(CivsMineRouteEpisode.key(first))

    test("A B A focus changes retain the episode and expose its third terminal failure") {
        val episode = CivsMineRouteEpisode()
        requireNotNull(episode.failed(key, 1_000)).failures shouldBe 1

        val secondKey = requireNotNull(CivsMineRouteEpisode.key(second))
        episode.retain(secondKey)
        episode.failed(secondKey, 15_000) shouldBe null
        episode.retain(requireNotNull(CivsMineRouteEpisode.key(first)))
        val stalled = requireNotNull(episode.failed(key, 29_000))

        stalled.failures shouldBe 3
        stalled.elapsedMillis shouldBe 28_000L
        stalled.suppressed shouldBe 1
    }

    test("replacement route and cycle instances keep accumulating but repeated warnings wait sixty seconds") {
        val episode = CivsMineRouteEpisode()
        for (index in 1..3) {
            val replacement = CitizenCycle(CitizenToken(building, 64, 0, null), 2_000)
            replacement.start()
            replacement.failedAttempts shouldBe 0
            episode.failed(key, index * 1_000L)?.failures shouldBe if (index == 2) null else index
            replacement.close()
        }
        episode.failed(key, 62_999) shouldBe null
        val periodic = requireNotNull(episode.failed(key, 63_000))
        periodic.failures shouldBe 5
        periodic.suppressed shouldBe 1
        episode.failed(key, 63_001) shouldBe null
        requireNotNull(episode.snapshot(64_000)).failures shouldBe 6
    }

    test("a new step version cycle assignment or cancelled worker phase clears the bounded episode") {
        for (next in listOf(key.copy(miningStep = 84), key.copy(planVersion = 4), key.copy(cycle = 65),
            key.copy(buildingId = UUID.randomUUID()), null)) {
            val episode = CivsMineRouteEpisode()
            episode.failed(key, 1_000)
            episode.failed(key, 2_000)
            episode.retain(next)

            episode.snapshot(3_000) shouldBe null
            requireNotNull(episode.failed(next ?: key, 4_000)).failures shouldBe 1
        }
    }

    test("safe teleport does not report walking recovery or reuse pre-teleport walking distance") {
        val episode = CivsMineRouteEpisode()
        episode.failed(key, 1_000)
        episode.walked(.3, 2_000) shouldBe null

        episode.teleported()

        requireNotNull(episode.snapshot(3_000)).walkedDistance shouldBe 0.0
        episode.walked(.2, 4_000) shouldBe null
        requireNotNull(episode.walked(.3, 5_000)).failures shouldBe 1
        episode.snapshot(5_000) shouldBe null
    }

    test("a committed physical work completion can recover without claiming walking") {
        val episode = CivsMineRouteEpisode()
        episode.failed(key, 1_000)
        episode.walked(Double.NaN, 2_000) shouldBe null
        episode.walked(-1.0, 2_000) shouldBe null

        val recovered = requireNotNull(episode.recovered(4_000))
        recovered.walkedDistance shouldBe 0.0
        recovered.elapsedMillis shouldBe 3_000L
        episode.snapshot(4_000) shouldBe null
    }
})
