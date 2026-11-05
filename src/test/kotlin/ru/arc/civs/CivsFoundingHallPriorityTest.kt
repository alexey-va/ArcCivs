package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsFoundingHallPriorityTest : FunSpec({
    for ((town, hasFarm) in listOf("Auto1" to true, "Auto3" to false)) {
        test("physical $town selects its existing founding hall before another farm or warehouse plot") {
            val city = foundingHallCity(hasFarm = hasFarm)
            val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
            city.construction shouldBe null
            city.constructionQueue.orEmpty() shouldBe emptyList()

            val goal = requireNotNull(CivsAutonomy.nextGoal(city))
            Triple(goal.type, goal.targetLevel, goal.buildingId) shouldBe Triple(BuildingType.TOWN_HALL, 1, hall.id)

            // Scheduling keeps the existing site and enters the ordinary queue; it pays no native row.
            val queued = CivsRules.enqueue(city, city.owner, requireNotNull(goal.buildingId))
            queued.constructionQueue shouldBe listOf(hall.id)
            queued.buildings shouldBe city.buildings
            queued.inventory shouldBe city.inventory
            queued.stock shouldBe city.stock
            queued.construction shouldBe null
        }

        test("physical $town moves on to the missing ordinary building after the founding hall completes") {
            val before = foundingHallCity(hasFarm = hasFarm)
            val city = CivsRules.validate(before.copy(buildings = before.buildings.map {
                if (it.type == BuildingType.TOWN_HALL) it.copy(phase = BuildingPhase.ACTIVE) else it
            }))
            val goal = requireNotNull(CivsAutonomy.nextGoal(city))
            Triple(goal.type, goal.targetLevel, goal.buildingId) shouldBe
                Triple(if (hasFarm) BuildingType.WAREHOUSE else BuildingType.FARM, 1, null)
        }
    }

    val bootstrap = listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY)
    for ((index, type) in bootstrap.withIndex()) {
        for (sitePresent in listOf(false, true)) {
            test("physical founding still prioritizes ${if (sitePresent) "SITE" else "missing"} $type") {
                val base = foundingHallCity(activeBootstrap = index)
                val site = foundingHallBuilding(type, BuildingPhase.SITE)
                val city = if (sitePresent) CivsRules.validate(base.copy(buildings = base.buildings + site)) else base
                val goal = requireNotNull(CivsAutonomy.nextGoal(city))
                Triple(goal.type, goal.targetLevel, goal.buildingId) shouldBe
                    Triple(type, 1, site.id.takeIf { sitePresent })
            }
        }
    }

    for (hasFarm in listOf(false, true)) {
        test("legacy stock keeps ${if (hasFarm) "warehouse" else "farm"} before the hall at every funding state") {
            val base = foundingHallCity(hasFarm = hasFarm)
            val type = if (hasFarm) BuildingType.WAREHOUSE else BuildingType.FARM
            val price = if (hasFarm) Stock(32, 16) else Stock(24, 8)
            for (stock in listOf(Stock(), Stock(32, 16), Stock(96, 96, 24))) {
                val city = CivsRules.validate(base.copy(inventory = null, stock = stock))
                CivsAutonomy.nextGoal(city) shouldBe CivAutonomyGoal(type, 1, null, price,
                    Stock((price.wood - stock.wood).coerceAtLeast(0),
                        (price.stone - stock.stone).coerceAtLeast(0), 0))
            }
        }
    }

    test("physical founding hall priority preserves an already accepted ordinary site") {
        val base = foundingHallCity(hasFarm = true)
        val warehouse = foundingHallBuilding(BuildingType.WAREHOUSE, BuildingPhase.SITE)
        val accepted = CivsRules.validate(base.copy(buildings = base.buildings + warehouse))
        for (city in listOf(accepted, CivsRules.enqueue(accepted, accepted.owner, warehouse.id))) {
            CivsAutonomy.nextGoal(city)?.buildingId shouldBe warehouse.id
        }
    }

    test("physical queued hall keeps its place before a later accepted site after bootstrap") {
        val base = foundingHallCity()
        val hall = base.buildings.single { it.type == BuildingType.TOWN_HALL }
        val warehouse = foundingHallBuilding(BuildingType.WAREHOUSE, BuildingPhase.SITE)
        val accepted = CivsRules.validate(base.copy(buildings = base.buildings + warehouse))
        val queued = CivsRules.enqueue(CivsRules.enqueue(accepted, accepted.owner, hall.id), accepted.owner, warehouse.id)
        queued.constructionQueue shouldBe listOf(hall.id, warehouse.id)
        CivsAutonomy.nextGoal(queued)?.buildingId shouldBe hall.id

        val legacy = CivsRules.validate(queued.copy(inventory = null, stock = Stock(96, 96, 24)))
        CivsAutonomy.nextGoal(legacy)?.buildingId shouldBe warehouse.id
    }
})

/** Reduced retained Auto1/Auto3 scheduling states: synthetic geometry, no placement or supply proof. */
private fun foundingHallCity(activeBootstrap: Int = 3, hasFarm: Boolean = false): Settlement {
    val hall = foundingHallBuilding(BuildingType.TOWN_HALL, BuildingPhase.SITE)
    val bootstrap = listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY)
        .take(activeBootstrap)
    val active = (bootstrap + if (hasFarm) listOf(BuildingType.FARM) else emptyList())
        .map { foundingHallBuilding(it, BuildingPhase.ACTIVE) }
    val founded = CivsAutonomy.found(UUID(224, 1), UUID(224, 2), "Founding hall priority", UUID(224, 3),
        requireNotNull(hall.control), 96, "nordic", hall, 1_000)
    // PHYSICAL has no closed-stock price. Actual items remain owned by the existing per-row payment path.
    return CivsRules.validate(founded.copy(buildings = listOf(hall) + active,
        inventory = CivInventory(CivStorageMode.PHYSICAL), stock = Stock(), constructionQueue = emptyList()))
}

private fun foundingHallBuilding(type: BuildingType, phase: BuildingPhase): CivBuilding {
    val point = CivPosition(type.ordinal * 12, 64, 0)
    return CivBuilding(UUID(224, 10L + type.ordinal), type, 1, point, 0,
        "test/founding-hall/${type.name.lowercase()}",
        CivBox(point.copy(x = point.x - 2, y = 62, z = -2), point.copy(x = point.x + 2, y = 68, z = 2)),
        point.copy(z = -3), phase, point)
}
