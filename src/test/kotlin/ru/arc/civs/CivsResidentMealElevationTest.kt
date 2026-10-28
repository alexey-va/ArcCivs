package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsResidentMealElevationTest : FunSpec({
    for (automatic in listOf(false, true))
    test("a nearby hungry resident climbs three blocks above the entrance to an existing meal Auto=$automatic") {
        residentRuntime(workerId = UUID(0, 37), server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            val hall = h.city.buildings.single().copy(
                bounds = CivBox(CivPosition(-2, 63, -2), CivPosition(9, 71, 2)),
                control = CivPosition(6, 67, 0))
            // A real supported staircase, then a platform around an upstairs barrel.
            for (x in 2..3) for (z in -1..1)
                for (y in 64..x + 62) h.world.getBlockAt(x, y, z).type = Material.STONE
            for (x in 4..8) for (z in -1..1)
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.world.getBlockAt(6, 67, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = listOf(hall),
                inventory = if (automatic) null else CivInventory(CivStorageMode.VIRTUAL),
                autonomy = if (automatic) CivAutonomy() else null,
                stock = if (automatic) Stock(food = 3) else Stock(),
                workers = h.city.workers.map { it.copy(hunger = 14,
                    life = CivResidentLife(awakeMinutes = 6),
                    inventory = if (automatic) null else mapOf("BREAD" to 2L)) })
            h.owner.residentLifeEnabled = true
            var meals = 0
            h.owner.finishResidentMeal = { cityId, plan ->
                h.owner.validateResidentMeal(cityId, plan)
                h.owner.collisionActors().single().location.y shouldBe 67.0
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                meals++
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            val spawns = h.spawns
            val positions = mutableListOf(h.owner.collisionActors().single().location)
            repeat(700) { tick ->
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val at = h.owner.collisionActors().single().location
                val previous = positions.last()
                val horizontal = kotlin.math.hypot(at.x - previous.x, at.z - previous.z)
                (horizontal <= .215001) shouldBe true
                (kotlin.math.abs(at.y - previous.y) <= 1.000001) shouldBe true
                citizenPacketBodyClear(h.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
                positions += at
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { meals shouldBe 1 }
            positions.any { it.y == 67.0 } shouldBe true
            positions.zipWithNext().sumOf { (from, to) -> from.distance(to) }.let { (it >= 6.0) shouldBe true }
            h.spawns shouldBe spawns
            h.city.workers.single().hunger shouldBe 20
            h.city.workers.single().life!!.meals shouldBe 1L
            h.deliveries shouldBe 0
            if (automatic) h.city.stock.food shouldBe 2L
            else CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1L
        }
    }

    test("a meal route includes its upstairs destination while ordinary wandering keeps its original height and radius") {
        val entrance = CivPosition(0, 64, 0)
        val from = CitizenPoint(.5, 64.0, .5)
        val upstairs = CivPosition(6, 67, 0)
        citizenIdleRouteAllowed(entrance, from, upstairs) shouldBe false
        citizenIdleRouteAllowed(entrance, from, upstairs, upstairs) shouldBe true
        citizenIdleRouteAllowed(entrance, from, upstairs.copy(y = 68), upstairs) shouldBe false
        citizenIdleRouteAllowed(entrance, from, upstairs.copy(x = 9), upstairs) shouldBe false
        citizenIdleRouteAllowed(entrance, from, entrance.copy(y = 61), upstairs) shouldBe false
    }

    test("a downstairs meal destination does not admit cells below it or relax ordinary leisure") {
        val entrance = CivPosition(0, 64, 0)
        val from = CitizenPoint(.5, 64.0, .5)
        val downstairs = CivPosition(5, 60, 0)
        citizenIdleRouteAllowed(entrance, from, downstairs) shouldBe false
        citizenIdleRouteAllowed(entrance, from, downstairs, downstairs) shouldBe true
        citizenIdleRouteAllowed(entrance, from, downstairs.copy(y = 59), downstairs) shouldBe false
        citizenIdleRouteAllowed(entrance, from, entrance.copy(y = 67), downstairs) shouldBe false
    }
})
