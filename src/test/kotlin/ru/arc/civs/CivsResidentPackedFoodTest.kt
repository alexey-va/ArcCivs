package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.verifyOrder
import org.bukkit.inventory.EquipmentSlot
import java.util.concurrent.CompletableFuture

class CivsResidentPackedFoodTest : FunSpec({
    test("Auto consumes carried bread instead of debiting abstract stock at a distant tavern") {
        residentRuntime { h ->
            val worker = h.city.workers.single().copy(hunger = 14, inventory = mapOf("BREAD" to 4L))
            h.city = h.city.copy(workers = listOf(worker), inventory = null, autonomy = CivAutonomy(), stock = Stock(food = 90))
            val plan = requireNotNull(CivsResidentLife.mealPlan(h.city, worker))
            plan.source shouldBe CivResidentMealSource.CARRIED_BREAD
            val fed = CivsResidentLife.finishMeal(h.city, plan)
            fed.stock shouldBe h.city.stock
            CivsEquipment.usableCount(fed.workers.single(), "BREAD") shouldBe 3
        }
    }

    test("a resident carrying food eats at the supported current position without requesting a route") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14, inventory = mapOf("BREAD" to 4L)) })
            h.owner.residentLifeEnabled = true
            var meals = 0
            h.owner.finishResidentMeal = { id, plan ->
                h.owner.validateResidentMeal(id, plan)
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                meals++
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            h.routeTarget() shouldBe null
            h.usingMainHand shouldBe true
            for (now in 3_000L..6_000L step 1_000) {
                h.owner.pulse(now)
                h.routeTarget() shouldBe null
            }
            meals shouldBe 1
            h.city.workers.single().hunger shouldBe 20
            h.owner.pulse(7_000)
            h.usingMainHand shouldBe false
            h.displayedHand shouldBe org.bukkit.Material.AIR
        }
    }

    test("severe hunger uses one session and exactly the three existing bread needed to resume work") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 0, inventory = mapOf("BREAD" to 4L)) })
            h.owner.residentLifeEnabled = true
            var sessions = 0
            h.owner.finishResidentMeal = { id, plan ->
                h.owner.validateResidentMeal(id, plan)
                plan.amount shouldBe 3
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                sessions++
                CompletableFuture.completedFuture(h.city)
            }
            for (now in 2_000L..9_000L step 1_000) h.owner.pulse(now)
            sessions shouldBe 1
            val worker = h.city.workers.single()
            worker.hunger shouldBe 18
            worker.life!!.meals shouldBe 3L
            CivsEquipment.usableCount(worker, "BREAD") shouldBe 1
        }
    }

    test("night interrupts eating without consuming bread and restores the original hand") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14, inventory = mapOf("BREAD" to 4L, "IRON_AXE" to 1L)) })
            h.owner.residentLifeEnabled = true
            h.owner.pulse(2_000)
            h.displayedHand shouldBe org.bukkit.Material.BREAD
            h.usingMainHand shouldBe true
            val npc = h.owner.collisionActors().single()
            verifyOrder {
                npc.setEquipment(EquipmentSlot.HAND, match { it.type == org.bukkit.Material.BREAD })
                npc.isUsingMainHand = true
            }
            h.world.time = 13_000
            h.owner.pulse(3_000)
            h.displayedHand shouldBe org.bukkit.Material.IRON_AXE
            h.usingMainHand shouldBe false
            verifyOrder {
                npc.isUsingMainHand = false
                npc.setEquipment(EquipmentSlot.HAND, match { it.type == org.bukkit.Material.IRON_AXE })
            }
            h.city.workers.single().hunger shouldBe 14
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
        }
    }

    test("runtime retirement ends item use and restores the original hand before retiring its actors") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14,
                inventory = mapOf("BREAD" to 4L, "IRON_AXE" to 1L)) })
            h.owner.residentLifeEnabled = true
            h.owner.pulse(2_000)
            val npc = h.owner.collisionActors().single()
            h.usingMainHand shouldBe true
            h.owner.closeAsync().join()
            h.usingMainHand shouldBe false
            verifyOrder {
                npc.isUsingMainHand = false
                npc.setEquipment(EquipmentSlot.HAND, match { it.type == org.bukkit.Material.IRON_AXE })
            }
            h.owner.activeCount() shouldBe 0
            h.city.workers.single().hunger shouldBe 14
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
        }
    }

    test("changing a resident plan ends the old meal and restores a cached real hand") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14,
                inventory = mapOf("BREAD" to 4L, "IRON_AXE" to 1L)) })
            h.owner.residentLifeEnabled = true
            h.owner.pulse(2_000)
            h.usingMainHand shouldBe true
            val hall = h.city.buildings.single()
            val warehouse = hall.copy(id = java.util.UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)), control = CivPosition(10, 64, 0))
            h.world.getBlockAt(10, 64, 0).type = org.bukkit.Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings + warehouse, workers = h.city.workers.map {
                it.copy(hunger = 20, assignment = warehouse.id, nextDeliveryAt = 1)
            }, revision = h.city.revision + 1)
            h.owner.pulse(3_000)
            h.usingMainHand shouldBe false
            h.displayedHand shouldBe org.bukkit.Material.IRON_AXE
            h.mealState()["target"] shouldBe null
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
        }
    }

    test("a changed unsupported meal position ends item use without spending the carried food") {
        residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14,
                inventory = mapOf("BREAD" to 4L, "IRON_AXE" to 1L)) })
            h.owner.residentLifeEnabled = true
            h.owner.pulse(2_000)
            h.usingMainHand shouldBe true
            val at = h.owner.collisionActors().single().location
            h.world.getBlockAt(at.blockX, at.blockY, at.blockZ).type = org.bukkit.Material.STONE
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 0) }, revision = h.city.revision + 1)
            h.owner.pulse(3_000)
            h.usingMainHand shouldBe false
            h.displayedHand shouldBe org.bukkit.Material.IRON_AXE
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
        }
    }
})
