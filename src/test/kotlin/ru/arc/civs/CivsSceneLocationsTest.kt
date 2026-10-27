package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSceneLocationsTest : FunSpec({
    fun city(index: Long) = Settlement(UUID(0, index), UUID(1, index), "Village $index", UUID(2, 1),
        CivPosition(index.toInt(), 64, -index.toInt()), 128, "medieval", Stock(), emptyList(), emptyList())

    test("saved public anchors are bounded and deleted settlements disappear from current state") {
        val cities = (1L..40L).map(::city)
        val pending = cities.first().copy(pendingDeletion = CivDeletion(null))
        val locations = civsSceneLocations(listOf(pending) + cities.drop(1), 512)
        locations.size shouldBe 32
        val first = locations.first()
        first.id shouldBe "civs:${cities[1].id}"
        first.name shouldBe cities[1].name
        first.worldId shouldBe cities[1].worldId
        first.x shouldBe 2.0
        first.y shouldBe 64.0
        first.z shouldBe -2.0
        first.radius shouldBe 128.0
        civsSceneLocations(cities, 1).size shouldBe 1
        civsSceneLocations(cities, 0) shouldBe emptyList()
        civsSceneLocations(listOf(cities[0].copy(name = "Renamed", center = CivPosition(50, 70, 40))), 1).single().name shouldBe "Renamed"
        first.name shouldBe "Village 2"
    }
})
