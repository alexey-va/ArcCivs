package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

class CivsPhysicalFoodNearArrivalTest : FunSpec({
    test("a hungry worker inside the stance tolerance still walks into native container reach then returns to work") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            val quarry = h.assignQuarry()
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(control = container)
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val food = (h.world.getBlockAt(0, 64, 0).state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 4))
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                workers = h.city.workers.map { it.copy(hunger = 14) }))
            val workPoint = CivPosition(21, 64, 0)
            val workControl = requireNotNull(quarry.control)
            h.work = CivWorldWork(h.workerId, 0, quarry.id, workPoint, emptyList(),
                outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 60_000, status = "digging-mine")
            h.workReady = false
            val source = CivResidentFoodSource(hall.id, container, CivPosition(3, 64, 2))
            h.physicalFoodSource = source
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 3.75, 64.0, 2.5))
            val before = npc.location
            citizenAtSupportedEndpoint(h.world, before.point(), source.standing) shouldBe true
            civsCanReachContainer(before, container) shouldBe false
            civsCanReachContainer(source.standing.location(h.world), container) shouldBe true
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                selected shouldBe source
                civsCanReachContainer(npc.location, container) shouldBe true
                pickups++
                pickups shouldBe 1
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                val worker = h.city.workers.single()
                CivsEquipment.usableCount(worker, "BREAD") shouldBe 0
                val supplied = CivsEquipment.collectSupplies(worker, mapOf("BREAD" to 4L))
                food.setItem(0, null)
                h.city = h.city.copy(revision = h.city.revision + 1, workers = listOf(supplied))
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            pickups shouldBe 0
            requireNotNull(h.routeIdentity()) // The coarse stance tolerance must not suppress the final native approach.
            for (tick in 1..1_000) {
                h.navigate()
                if (citizenAtStorage(h.world, npc.location.point(), workControl) &&
                    citizenCanReachBlock(npc, workControl)) h.workReady = true
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (pickups == 0) {
                    food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                    CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 0
                }
                if (pickups == 1 && h.workReady && h.phase() == CitizenPhase.WORKING) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                pickups shouldBe 1
                h.workReady shouldBe true
                h.phase() shouldBe CitizenPhase.WORKING
            }
            (npc.location.distance(before) > 2.0) shouldBe true
            citizenAtStorage(h.world, npc.location.point(), workControl) shouldBe true
            citizenCanReachBlock(npc, workControl) shouldBe true
            food.isEmpty shouldBe true
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
            h.deliveries shouldBe 0
            h.supplyRequests shouldBe 0
        }
    }
})
