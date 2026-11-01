package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CivsMinePlanCacheTest : FunSpec({
    test("nested version construction survives the first cache resize and retains the cached winner") {
        @Suppress("UNCHECKED_CAST")
        val cache = CivsMinePlan::class.java.getDeclaredField("plans").run {
            isAccessible = true
            get(null) as ConcurrentHashMap<String, CivsMinePlan>
        }
        cache.size shouldBe 0
        val buildings = listOf("nordic", "medievaloak", "caledonia").flatMap { style ->
            (1..3).map { spatialBuilding(style, "miner", it, CivPosition(0, 80, 0)) }
        }
        val target = buildings.first()
        fun bucket(key: String): Int = (key.hashCode() xor (key.hashCode() ushr 16)) and 15
        val outer = "${target.schematicId}:7"
        val inner = "${target.schematicId}:6"
        val warm = buildings.flatMap { building -> (2..6).map { building to it } }.filter { (building, version) ->
            val key = "${building.schematicId}:$version"
            key != inner && bucket(key) != bucket(outer)
        }.take(11)
        warm.size shouldBe 11
        warm.forEach { (building, version) -> requireNotNull(CivsMinePlan.forBuilding(building, version)) }
        cache.size shouldBe 11
        // The nested V6 insertion crosses the default table's twelve-entry resize boundary.
        val plan = requireNotNull(CivsMinePlan.forBuilding(target, 7))
        val previous = requireNotNull(plan.forVersion(6))
        cache.size shouldBe 13
        (CivsMinePlan.forBuilding(target, 7) === plan) shouldBe true
        (previous.forVersion(7) === plan) shouldBe true
        (CivsMinePlan.forBuilding(target, 6) === previous) shouldBe true
        plan.steps.take(previous.steps.size) shouldBe previous.steps
    }

    test("invalid resident snapshots retain their rejection reason and original runtime cause") {
        val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "test",
            CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
        val city = Settlement(UUID(0, 2), UUID(0, 3), "Validation", UUID(0, 4), CivPosition(0, 64, 0), 96,
            "nordic", Stock(), listOf(hall), listOf(CivWorker(UUID(0, 5), "Resident", null, health = 0.0)))
        val error = shouldThrow<CivsRejection> { CivsRules.validate(city) }
        error.reason shouldBe "invalid-state"
        error.cause?.javaClass shouldBe IllegalArgumentException::class.java
        error.cause?.stackTrace?.any { it.className == CivsResidents::class.java.name && it.methodName == "validated" } shouldBe true
        val rejected = shouldThrow<CivsRejection> { CivsRules.validate(city.copy(radius = 0)) }
        rejected.reason shouldBe "invalid-state"
        rejected.cause shouldBe null
    }
})
