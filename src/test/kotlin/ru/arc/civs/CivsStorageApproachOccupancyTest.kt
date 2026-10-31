package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID

class CivsStorageApproachOccupancyTest : FunSpec({
    for (cached in listOf(true, false)) test("an occupied ${if (cached) "cached" else "first"} barrel side keeps the tool cycle and walks to a free side") {
        residentRuntime(workerId = UUID(144, 679)) { h ->
            val fixture = storageApproachFixture(h, cached, 1)
            val actor = fixture.actor
            val neighbour = fixture.neighbours.single()
            val before = h.city
            val token = actor.plan.token
            val neighbourPosition = neighbour.npc.location
            val work = requireNotNull(actor.plan.worldWork)
            val barrel = h.world.getBlockAt(fixture.control.x, fixture.control.y, fixture.control.z).state as Barrel
            var offers = 0
            h.workerDelivery = { worker, proof ->
                proof shouldBe token
                h.owner.requireStorageArrival(h.city.id, worker, fixture.building.id)
                actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                (actor.cycle.observedWorkMillis >= 1_000) shouldBe true
                h.city shouldBe before
                offers++
                h.pendingDelivery
            }
            fixture.routes.startRoute(actor, h.world, work.standing, 2_000) shouldBe true
            val selected = requireNotNull(actor.route).target
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                (selected.point() != work.standing.point()) shouldBe true
                citizenResidentStepClear(actor.npc, selected, selected,
                    listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale))) shouldBe true
            }
            actor.plan.token shouldBe token
            actor.plan.worldWork shouldBe work
            h.city shouldBe before
            offers shouldBe 0
            var walked = 0.0
            for (tick in 1..400) {
                val previous = actor.npc.location
                h.navigate()
                val actual = actor.npc.location
                withClue("tick=$tick ${h.navigationState()}") {
                    (previous.distance(actual) <= .172001) shouldBe true
                    citizenResidentStepClear(actor.npc, previous, actual,
                        listOf(citizenStandingBounds(neighbour.npc.location, neighbour.npc.scale))) shouldBe true
                }
                walked += previous.distance(actual)
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (offers == 1) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                offers shouldBe 1
                (walked > .1) shouldBe true
            }
            actor.plan.token shouldBe token
            neighbour.npc.location shouldBe neighbourPosition
            neighbour.yieldProgress shouldBe 0.0
            h.city shouldBe before
            barrel.inventory.getItem(0) shouldBe ItemStack(Material.IRON_PICKAXE)
            h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 10_234L
            CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "IRON_PICKAXE") shouldBe 0
            h.deliveries shouldBe 1
        }
    }

    test("all supported barrel sides occupied pause without a receipt and retry the same cycle when a side clears") {
        residentRuntime(workerId = UUID(144, 680)) { h ->
            val fixture = storageApproachFixture(h, true, 4)
            val actor = fixture.actor
            val before = h.city
            val token = actor.plan.token
            val cycle = actor.cycle
            fixture.routes.startRoute(actor, h.world, requireNotNull(actor.plan.workplace), 2_000) shouldBe false
            actor.route shouldBe null
            actor.cycle.phase shouldBe CitizenPhase.PAUSED
            actor.cycle.reason shouldBe "storage-unavailable"
            actor.targets shouldBe emptyMap()
            h.owner.pulse(4_000)
            actor.cycle.phase shouldBe CitizenPhase.PAUSED
            for ((at, attempts) in listOf(7_000L to 2, 22_000L to 3)) {
                h.navigationNow = at
                h.owner.pulse(at)
                (actor.cycle === cycle) shouldBe true
                actor.cycle.phase shouldBe CitizenPhase.PAUSED
                actor.cycle.reason shouldBe "storage-unavailable"
                actor.cycle.failedAttempts shouldBe attempts
                actor.route shouldBe null
            }
            // Even after the ordinary retry allowance is exhausted, availability has a bounded next retry.
            cycle.retry(36_999)
            cycle.phase shouldBe CitizenPhase.PAUSED
            cycle.retry(37_000)
            cycle.phase shouldBe CitizenPhase.TO_WORK
            cycle.failedAttempts shouldBe 3
            h.city shouldBe before
            h.deliveries shouldBe 0
            fixture.neighbours.forEachIndexed { index, neighbour ->
                fixture.routes.cancelRoute(neighbour)
                neighbour.npc.teleport(Location(h.world, 8.5 + index * 2, 64.0, 8.5))
            }
            h.navigationNow = 40_000
            h.owner.pulse(40_000)
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                actor.cycle.phase shouldBe CitizenPhase.TO_WORK
                (actor.route != null) shouldBe true
            }
            (actor.cycle === cycle) shouldBe true
            actor.plan.token shouldBe token
            actor.cycle.observedWorkMillis shouldBe 0L
            h.city shouldBe before
            h.deliveries shouldBe 0
        }
    }
})

private data class StorageApproachFixture(val building: CivBuilding, val control: CivPosition,
    val actor: CitizenActor, val neighbours: List<CitizenActor>, val routes: CivsCitizenRoutes)

private fun storageApproachFixture(h: ResidentRuntime, cached: Boolean, neighbours: Int): StorageApproachFixture {
    for (x in -1..2) for (z in -2..1) h.world.loadChunk(x, z)
    val quarry = h.assignQuarry().copy(level = 2)
    val control = requireNotNull(quarry.control)
    // Translate the observed 679/150 pair to the fixture barrel. Rotate the uncached case to its first (+X) side.
    fun point(x: Double, z: Double) = if (cached) Location(h.world, control.x + x, 64.0, control.z + z)
        else Location(h.world, control.x + 1 - z, 64.0, control.z + x)
    val preferred = if (cached) control.copy(z = control.z - 1) else control.copy(x = control.x + 1)
    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
        if (it.id == quarry.id) quarry else if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it
    }, workers = h.city.workers.map { it.copy(completedCycles = 10_234,
        items = List(CivsEquipment.SLOTS) { null }) } + (0 until neighbours).map { index ->
        CivWorker(UUID(144, 150 + index.toLong()), "Storage neighbour", if (neighbours == 1) quarry.id else null, nextDeliveryAt = 1)
    }))
    val worker = h.city.workers.first { it.id == h.workerId }
    val equipment = CivsEquipment.slots(worker).toMutableList().also { it[4] = CivResidentItem("IRON_PICKAXE", 1) }
    val work = CivWorldWork(worker.id, worker.completedCycles, quarry.id, preferred, emptyList(),
        inputs = mapOf("IRON_PICKAXE" to 1L), durationMillis = 1_000, status = "waiting-tool", equipmentAfter = equipment)
    h.workProvider = { _, current -> work.takeIf { current.id == worker.id } }
    h.workIdleProvider = { _, _ -> "searching-work" }
    h.navigationNow = 2_000
    h.owner.collisionActors().single().teleport(point(.7692, -1.5615))
    (h.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory.setItem(0, ItemStack(Material.IRON_PICKAXE))
    for (attempt in 0 until 4) {
        h.owner.pulse(2_000)
        if (h.owner.activeCount() == h.city.workers.size) break
    }
    val actors = storageActors(h)
    actors.keys shouldBe h.city.workers.mapTo(hashSetOf()) { it.id }
    val actor = actors.getValue(h.workerId)
    val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
    actors.values.forEach { routes.cancelRoute(it); it.targets.clear(); it.yieldProgress = 0.0 }
    val others = actors.values.filter { it !== actor }
    others.size shouldBe neighbours
    if (neighbours == 1) others.single().npc.teleport(point(1.0613, -.9592))
    else others.zip(listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)).forEach { (other, offset) ->
        other.npc.teleport(control.copy(x = control.x + offset.first, z = control.z + offset.second).location(h.world))
    }
    actor.cycle.phase shouldBe CitizenPhase.TO_WORK
    citizenAtStorage(h.world, actor.npc.location.point(), control) shouldBe false
    if (cached) actor.targets[preferred] = preferred
    return StorageApproachFixture(quarry, control, actor, others, routes)
}

private fun storageActors(h: ResidentRuntime): Map<UUID, CitizenActor> = h.owner.javaClass.getDeclaredField("actors").run {
    isAccessible = true
    @Suppress("UNCHECKED_CAST")
    get(h.owner) as Map<UUID, CitizenActor>
}
