package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsResidentRegenerationTest : FunSpec({
    for (automatic in listOf(false, true)) for (hunger in listOf(17, 18, 19, 20))
    test("Auto $automatic hunger $hunger heals one health only after four observed seconds") {
        regenerationScenario(automatic) { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = hunger) })
            val before = h.city
            for (at in 0L..3_000L step 1_000L) h.pulse(at)
            h.city shouldBe before
            h.writes shouldBe 0
            h.pulse(4_000)
            val healed = hunger >= 18
            CivsResidents.health(h.city.workers.single()) shouldBe if (healed) 9.0 else 8.0
            h.city.revision shouldBe before.revision + if (healed) 1 else 0
            h.city.workers.single().copy(health = before.workers.single().health) shouldBe before.workers.single()
            h.city.stock shouldBe before.stock
        }
    }

    for (health in listOf(0.0, 20.0, 28.0))
    test("dead or fully healthy resident at $health does not regenerate or write a city") {
        regenerationScenario { h ->
            h.city = h.city.copy(workers = h.city.workers.map {
                it.copy(health = health, skills = if (health == 28.0) mapOf("FORESTER" to 40L) else null)
            })
            val before = h.city
            for (at in 0L..10_000L step 1_000L) h.pulse(at)
            h.city shouldBe before
            h.writes shouldBe 0
        }
    }

    test("regeneration respects the resident personal maximum and keeps fractional health") {
        regenerationScenario { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(health = 27.5, skills = mapOf("FORESTER" to 40L)) })
            for (at in 0L..8_000L step 1_000L) h.pulse(at)
            CivsResidents.health(h.city.workers.single()) shouldBe 28.0
            h.writes shouldBe 1
        }
    }

    test("each resident needs its own four observed seconds") {
        regenerationScenario { h ->
            val first = h.city.workers.single()
            val second = first.copy(id = UUID(0, 101), name = "Second")
            h.city = h.city.copy(workers = listOf(first, second))
            h.awake = setOf(first.id)
            for (at in 0L..3_000L step 1_000L) h.pulse(at)
            h.awake += second.id
            for (at in 4_000L..7_000L step 1_000L) h.pulse(at)
            h.city.workers.map(CivsResidents::health) shouldBe listOf(9.0, 8.0)
            h.pulse(8_000)
            h.city.workers.map(CivsResidents::health) shouldBe listOf(10.0, 9.0)
        }
    }

    for (interruption in listOf("absent", "paused", "inactive", "unhealthy", "clock-reset", "restart", "pulse-gap", "hungry"))
    test("$interruption discards unfinished healing and grants no catch-up") {
        regenerationScenario { h ->
            for (at in 0L..3_000L step 1_000L) h.pulse(at)
            when (interruption) {
                "absent" -> h.awake = emptySet()
                "paused" -> h.city = h.city.copy(autonomy = CivAutonomy(paused = true))
                "inactive" -> h.active = false
                "unhealthy" -> h.unhealthy += h.city.id
                "clock-reset" -> h.runtime.resetMinuteClock()
                "restart" -> h.runtime = h.newRuntime()
                "hungry" -> h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 17) })
            }
            if (interruption !in listOf("clock-reset", "restart", "pulse-gap")) h.pulse(4_000)
            h.awake = h.city.workers.mapTo(hashSetOf(), CivWorker::id)
            h.city = h.city.copy(autonomy = null, workers = h.city.workers.map { it.copy(hunger = 18) })
            h.active = true
            h.unhealthy.clear()
            val resumedAt = if (interruption == "pulse-gap") 40_000L else 5_000L
            for (at in resumedAt..resumedAt + 3_000L step 1_000L) h.pulse(at)
            CivsResidents.health(h.city.workers.single()) shouldBe 8.0
            h.pulse(resumedAt + 4_000L)
            CivsResidents.health(h.city.workers.single()) shouldBe 9.0
            h.writes shouldBe 1
        }
    }

    test("short observation gaps count elapsed time without replaying more than one heal") {
        regenerationScenario { h ->
            for (at in listOf(0L, 1_500L, 3_000L)) h.pulse(at)
            CivsResidents.health(h.city.workers.single()) shouldBe 8.0
            h.pulse(4_500)
            CivsResidents.health(h.city.workers.single()) shouldBe 9.0
            h.pulse(4_500)
            h.writes shouldBe 1
            for (at in listOf(6_000L, 7_500L, 9_000L)) h.pulse(at)
            CivsResidents.health(h.city.workers.single()) shouldBe 10.0
            h.writes shouldBe 2
        }
    }
})

private fun regenerationScenario(automatic: Boolean = false, action: (RegenerationScenario) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper -> RegenerationScenario(paper, automatic).use(action) }
    }

private class RegenerationScenario(private val paper: MockBukkitTestRuntime, automatic: Boolean) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("ResidentRegeneration")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val citizens = mockk<CivsCitizens>()
    private val chunks = mockk<CivsChunkLoading>()
    private val cities = linkedMapOf<UUID, Settlement>()
    val unhealthy = mutableSetOf<UUID>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    var awake: Set<UUID>
    var active = true
    var at = 0L
    var writes = 0
    var runtime: CivsResidentLifeRuntime

    init {
        val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 3, CivPosition(0, 64, 0), 0,
            "test", CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 3), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 20), "Life", UUID(0, 30), CivPosition(0, 64, 0),
            80, "test", Stock(), listOf(hall), listOf(CivWorker(UUID(0, 100), "Resident", null, health = 8.0, hunger = 18)),
            autonomy = if (automatic) CivAutonomy() else null))
        awake = city.workers.mapTo(hashSetOf(), CivWorker::id)
        every { citizens.awakeResidentIds(any()) } answers { awake }
        runtime = newRuntime()
    }

    fun newRuntime() = CivsResidentLifeRuntime(plugin, tasks, citizens, chunks, 48, { cities }, emptySet(), unhealthy,
        { active }, { _, action -> action() }, { _, action -> action() }, { _, transform ->
            val next = transform(city)
            if (next != city) writes++
            city = next
            CompletableFuture.completedFuture(next)
        }, { at })

    fun pulse(now: Long) { at = now; runtime.pulsePopulation(); paper.performTicks(1) }
    override fun close() { tasks.close() }
}
