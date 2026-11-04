package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import java.util.UUID

class CivsRoadStandingRetryTest : FunSpec({
    test("a rejected upper stance keeps its admitted column and selects the supported lower side with the same bill") {
        StandingRetryFixture(claimed = true).use { h ->
            val original = requireNotNull(h.plan())
            original.standing shouldBe CivPosition(1, 69, 0)
            val before = h.foundation.column()
            h.maintenance.admitDevelopment(h.city) shouldBe false
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.maintenance.holdsAssignment(h.city, h.worker().id) shouldBe true
            h.maintenance.admitDevelopment(h.city) shouldBe false
            h.tick++
            val lower = requireNotNull(h.plan())
            lower.standing shouldBe CivPosition(-1, 66, 0)
            lower.upkeep shouldBe original.upkeep
            lower.changes shouldBe original.changes
            lower.inputs shouldBe mapOf("COBBLESTONE" to 10L)
            lower.inputs shouldBe original.inputs
            h.maintenance.requirements(h.city) shouldBe mapOf(h.worker().id to original.inputs)
            h.exclusions.last() shouldBe setOf(original.standing)
            h.foundation.native.validateRoadUpkeep(h.city, lower)
            h.foundation.column() shouldBe before
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("same native changes accept a refreshed standing and focus after the original standing is rejected") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.foundation.block(CivPosition(1, 68, 0)).type = Material.AIR
            h.refreshedFocus = h.target.floor.copy(y = h.target.floor.y - 1)
            h.tick++
            val refreshed = requireNotNull(h.plan())
            refreshed.standing shouldBe CivPosition(-1, 66, 0)
            refreshed.focus shouldBe h.refreshedFocus
            refreshed.changes shouldBe original.changes
            refreshed.inputs shouldBe original.inputs
            h.exclusions.first() shouldBe emptySet() // Initial capture keeps the legacy four-argument wrapper.
            h.exclusions.last() shouldBe setOf(original.standing)
        }
    }

    test("an exhausted eight probe budget never returns the rejected cached placement") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            repeat(7) { h.plan() shouldBe original }
            h.probesByTick.getValue(0) shouldBe 8
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.plan() shouldBe null
            h.probesByTick.getValue(0) shouldBe 8
            h.maintenance.holdsAssignment(h.city, h.worker().id) shouldBe true
            h.tick++
            requireNotNull(h.plan()).standing shouldBe CivPosition(-1, 66, 0)
            h.probesByTick.values.all { it <= 8 } shouldBe true
        }
    }

    for (stale in listOf("work", "cycle", "assignment", "owner", "world", "source"))
    test("a stale $stale rejection cannot exclude a point from the current selection") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            val before = h.city
            val worker = h.worker()
            val staleCity = when (stale) {
                "cycle" -> before.copy(workers = listOf(worker.copy(completedCycles = 1)))
                "assignment" -> before.copy(workers = listOf(worker.copy(assignment = null)))
                "owner" -> before.copy(owner = UUID(0, 501))
                "world" -> before.copy(worldId = UUID(0, 502))
                "source" -> before.copy(buildings = before.buildings.map { if (it.id == h.target.source.id) it.copy(phase = BuildingPhase.SITE) else it })
                else -> before
            }
            val failed = if (stale == "work") original.copy(standing = CivPosition(-1, 66, 0)) else original
            h.maintenance.rejectStanding(staleCity, staleCity.workers.single(), failed) shouldBe false
            h.maintenance.rejectStanding(before, worker, original) shouldBe true
            h.tick++
            requireNotNull(h.plan()).standing shouldBe CivPosition(-1, 66, 0)
            h.exclusions.last() shouldBe setOf(original.standing)
            h.maintenance.rejectStanding(before, worker, original) shouldBe false
        }
    }

    test("a changed network discards its exclusions and rejects the old callback") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.maintenance.publish(h.city, h.network.copy(targets = emptyList()))
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe false
            h.maintenance.publish(h.city, h.network)
            h.tick++
            requireNotNull(h.plan()).standing shouldBe original.standing
            h.exclusions.last() shouldBe emptySet()
        }
    }

    test("native change vectors clear exclusions after accepting their fresh placement and focus") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            val cover = h.target.floor.copy(y = h.target.floor.y + 1)
            h.foundation.block(cover).type = Material.SHORT_GRASS
            h.tick++
            val refreshed = requireNotNull(h.plan())
            refreshed.standing shouldBe CivPosition(-1, 66, 0)
            refreshed.focus shouldBe cover
            refreshed.changes.size shouldBe original.changes.size + 1
            refreshed.inputs shouldBe original.inputs
            h.tick++
            val retained = requireNotNull(h.plan())
            retained.standing shouldBe refreshed.standing
            h.exclusions.last() shouldBe emptySet()
            h.maintenance.rejectStanding(h.city, h.worker(), retained) shouldBe true
            h.tick++
            requireNotNull(h.plan()).standing shouldBe original.standing
            h.exclusions.last() shouldBe setOf(retained.standing)
        }
    }

    test("exhausting every native candidate releases the column claim supplies and hold with the existing backoff") {
        StandingRetryFixture(claimed = true).use { h ->
            val upper = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), upper) shouldBe true
            h.tick++
            val lower = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), lower) shouldBe true
            h.tick++
            h.plan() shouldBe null
            h.exclusions.last() shouldBe setOf(upper.standing, lower.standing)
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.maintenance.holdsAssignment(h.city, h.worker().id) shouldBe false
            h.maintenance.admitDevelopment(h.city) shouldBe true
            h.plan() shouldBe null
            h.tick += 100
            h.maintenance.developmentFinished(h.city.id)
            requireNotNull(h.plan()).standing shouldBe upper.standing
            h.exclusions.last() shouldBe emptySet()
        }
    }

    test("explicit invalidation clears rejected positions before the next ordinary admission") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.maintenance.invalidate(h.worker().id)
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.plan() shouldBe null
            h.tick += 100
            requireNotNull(h.plan()).standing shouldBe original.standing
            h.exclusions.last() shouldBe emptySet()
        }
    }

    test("retirement clears the placement while the existing exact receipt debits once and healthy work stays free") {
        StandingRetryFixture().use { h ->
            val original = requireNotNull(h.plan())
            h.maintenance.rejectStanding(h.city, h.worker(), original) shouldBe true
            h.tick++
            val lower = requireNotNull(h.plan())
            h.foundation.stock(24)
            h.foundation.storage().use { storage ->
                val id = UUID.randomUUID()
                storage.reserve(h.city, id)
                val plan = storage.prepareWork(h.city, lower.buildingId, lower.storageInputs(h.worker()), emptyMap(), lower.changes)
                val after = CivsRoadMaintenance.complete(h.city, lower, 3_000, nativePaid = true)
                val tx = CivInventoryTransaction(id, h.city.id, h.city.revision, after, plan.changes, worldChanges = lower.changes,
                    upkeep = CivRoadUpkeepReceipt(h.worker(), h.city.stock, lower, 3_000)).validated()
                storage.seal(tx)
                repeat(2) {
                    storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                    h.foundation.stockCount() shouldBe 14
                }
                h.city = after
            }
            h.maintenance.upkeepRetired(h.city, h.target)
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.maintenance.holdsAssignment(h.city, h.worker().id) shouldBe false
            h.maintenance.rejectStanding(h.city, h.worker(), lower) shouldBe false
            h.tick++
            h.plan() shouldBe null
            h.foundation.stockCount() shouldBe 14
            h.worker().completedCycles shouldBe 1L
        }
    }

    test("a retried road position still requires real citizen arrival before any delivery") {
        residentRuntime { h ->
            val original = h.assignRoadUpkeep()
            val actor = h.owner.collisionActors().single()
            val alternate = original.copy(standing = original.standing.copy(z = 1))
            h.work = alternate
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, alternate) }.reason shouldBe "route-unreachable"
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0L
            val spawns = h.spawns
            for (tick in 1..300) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(2_000 + tick * 50L)
                (before.distance(actor.location) <= .172001) shouldBe true
                if (h.deliveries > 0) break
            }
            h.deliveries shouldBe 1
            h.spawns shouldBe spawns
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, alternate)
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }
})

private class StandingRetryFixture(claimed: Boolean = false) : AutoCloseable {
    val foundation = FoundationFixture()
    var city: Settlement
        get() = foundation.city
        set(value) { foundation.city = value }
    var tick = 0
    val exclusions = mutableListOf<Set<CivPosition>>()
    val probesByTick = mutableMapOf<Int, Int>()
    var refreshedFocus: CivPosition? = null
    private val adapter = mockk<CivsWorld>()
    val maintenance = CivsRoadMaintenance(adapter) { tick }
    val network: CivRoadUpkeepNetwork
    val target get() = foundation.target
    init {
        foundation.block(CivPosition(1, 68, 0)).type = Material.GRASS_BLOCK
        if (claimed) {
            val tavern = city.buildings.first().copy(id = UUID(0, 503), type = BuildingType.TAVERN,
                anchor = CivPosition(50, 63, 50), bounds = CivBox(CivPosition(49, 63, 49), CivPosition(51, 68, 51)),
                entrance = CivPosition(50, 64, 52), control = CivPosition(50, 64, 50))
            city = city.copy(buildings = city.buildings + tavern, workers = listOf(worker().copy(assignment = tavern.id)))
        }
        every { adapter.captureRoadUpkeep(any(), any(), any()) } answers {
            exclusions += emptySet<CivPosition>()
            probesByTick.merge(tick, 1, Int::plus)
            foundation.native.captureRoadUpkeep(firstArg(), secondArg(), thirdArg())?.let { work ->
                refreshedFocus?.let { work.copy(focus = it) } ?: work
            }
        }
        every { adapter.captureRoadUpkeepAvoiding(any(), any(), any(), any(), any()) } answers {
            val rejected = arg<Set<CivPosition>>(4)
            exclusions += rejected.toSet()
            probesByTick.merge(tick, 1, Int::plus)
            foundation.native.captureRoadUpkeep(firstArg(), secondArg(), thirdArg(), arg(3), rejected)?.let { work ->
                refreshedFocus?.let { work.copy(focus = it) } ?: work
            }
        }
        network = CivsRoadMaintenance.prepare(city, listOf(foundation.plan), proposeCuts = false,
            restrictTo = setOf(civsTerrainKey(target.floor.x, target.floor.z)))
        maintenance.publish(city, network)
        if (claimed) {
            maintenance.backfillSurfaces(city) { true }
            val (id, hut) = requireNotNull(maintenance.maintenanceAssignment(city) { true })
            city = CivsRules.assign(city, city.owner, id, hut, 0)
        }
    }
    fun worker() = city.workers.single()
    fun plan() = maintenance.plan(city, worker())
    override fun close() { foundation.close() }
}
