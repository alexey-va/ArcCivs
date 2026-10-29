package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

class CivsRoadAdmissionTest : FunSpec({
    test("growth preserves one admitted ordinary standing then yields the unrelated backlog until its attempt ends") {
        val city = roadAdmissionCity()
        val network = roadAdmissionNetwork(city)
        val native = mockk<CivsWorld>()
        var alternative = false
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers {
            val work = roadAdmissionWork(firstArg(), thirdArg())
            if (alternative) work.copy(standing = work.standing.copy(z = work.standing.z + 1)) else work
        }
        var tick = 0
        val maintenance = CivsRoadMaintenance(native) { tick }
        maintenance.publish(city, network)
        val first = requireNotNull(maintenance.plan(city, city.workers.single()))
        maintenance.admitDevelopment(city) shouldBe false
        alternative = true
        tick++
        maintenance.plan(city, city.workers.single()) shouldBe first // Same native changes retain the admitted standing.
        val after = CivsRoadMaintenance.complete(city, first, 3_000)
        tick++
        maintenance.plan(after, after.workers.single()) shouldBe null
        maintenance.admitDevelopment(after) shouldBe true
        maintenance.developmentFinished(city.id)
        maintenance.plan(after, after.workers.single())!!.cycle shouldBe after.workers.single().completedCycles
    }

    test("the first route override waits for its fresh network and every remaining repairable grade column") {
        val (city, plans) = roadAdmissionHill()
        val beforeNetwork = CivsRoadMaintenance.prepare(city, plans)
        val firstTarget = beforeNetwork.targets.single { it.routeOverride != null }
        val projected = city.copy(roadRouteOverrides = firstTarget.overrides())
        val projectedNetwork = CivsRoadMaintenance.prepare(projected, plans)
        val original = CivsAutonomousTerraform.roadProfile(firstTarget.routeOverride!!.originalRoute)
        val lowered = CivsAutonomousTerraform.roadProfile(firstTarget.routeOverride!!.route)
            .filter { (key, at) -> original[key]?.let { at.y < it.y } == true }.values.toSet()
        val dirty = projectedNetwork.targets.map(CivRoadUpkeep::floor).filter { it in lowered }.toMutableSet()
        (dirty.size > 1) shouldBe true
        val native = mockk<CivsWorld>()
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers {
            val target = thirdArg<CivRoadUpkeep>()
            if (target.floor in dirty) roadAdmissionWork(firstArg(), target) else null
        }
        var tick = 0
        val maintenance = CivsRoadMaintenance(native) { tick }
        maintenance.publish(city, beforeNetwork)
        val first = requireNotNull(maintenance.plan(city, city.workers.single()))
        first.upkeep shouldBe firstTarget
        maintenance.admitDevelopment(city) shouldBe false
        var current = CivsRoadMaintenance.complete(city, first, 3_000)
        dirty.remove(firstTarget.floor)
        maintenance.awaitGradeNetwork(current)
        maintenance.admitDevelopment(current) shouldBe false
        maintenance.publish(current, CivsRoadMaintenance.prepare(current, plans))
        tick++
        var next = requireNotNull(maintenance.plan(current, current.workers.single()))
        next.upkeep!!.routeOverride shouldBe null
        (next.upkeep!!.floor != firstTarget.floor) shouldBe true
        maintenance.admitDevelopment(current) shouldBe false // Metadata is flat; the next native column is still uncut.
        val remaining = dirty.toSet()
        val worked = mutableSetOf<CivPosition>()
        repeat(128) {
            next.upkeep!!.floor.let { worked += it; dirty.remove(it) }
            current = CivsRoadMaintenance.complete(current, next, 4_000L + it * 4_000L)
            tick++
            val following = maintenance.plan(current, current.workers.single())
            if (following == null) {
                dirty shouldBe emptySet()
                worked shouldBe remaining
                // A null plan may have spent this tick's eight probes on already completed columns.
                repeat(16) {
                    if (maintenance.admitDevelopment(current)) {
                        maintenance.publish(current, CivsRoadMaintenance.prepare(current, plans))
                        maintenance.admitDevelopment(current) shouldBe true // The same network cannot restart a completed pass.
                        return@test
                    }
                    tick++
                    maintenance.backfillSurfaces(current)
                }
                error("The healthy observation tail did not release development")
            }
            maintenance.admitDevelopment(current) shouldBe false
            next = following
        }
        error("The finite lowered grade did not finish")
    }

    test("healthy or unavailable grade captures exhaust one finite pass within the shared budget") {
        val (city, plans) = roadAdmissionHill()
        val native = mockk<CivsWorld>()
        var reads = 0
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers { reads++; null }
        var tick = 0
        val maintenance = CivsRoadMaintenance(native) { tick }
        maintenance.publish(city, CivsRoadMaintenance.prepare(city, plans))
        maintenance.admitDevelopment(city) shouldBe false
        repeat(16) {
            val before = reads
            maintenance.backfillSurfaces(city)
            (reads - before <= 8) shouldBe true
            if (maintenance.admitDevelopment(city)) {
                maintenance.requirements(city) shouldBe emptyMap()
                return@test
            }
            tick++
        }
        error("Uncapturable columns held growth indefinitely")
    }

    test("native unavailability and a failed first-cut network reload release development") {
        val city = roadAdmissionCity()
        val native = mockk<CivsWorld>()
        var available = true
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers {
            if (available) roadAdmissionWork(firstArg(), thirdArg()) else null
        }
        var tick = 0
        val maintenance = CivsRoadMaintenance(native) { tick }
        maintenance.publish(city, roadAdmissionNetwork(city))
        requireNotNull(maintenance.plan(city, city.workers.single()))
        maintenance.admitDevelopment(city) shouldBe false
        available = false
        tick++
        maintenance.plan(city, city.workers.single()) shouldBe null
        maintenance.admitDevelopment(city) shouldBe true
        val load = maintenance.awaitGradeNetwork(city)
        maintenance.admitDevelopment(city) shouldBe false
        maintenance.gradeNetworkCompleted(city.id, load)
        maintenance.admitDevelopment(city) shouldBe true
        maintenance.requirements(city) shouldBe emptyMap()
    }

    test("a stale or unpublished network load cannot retain its wait or erase newer observed grade work") {
        val (city, plans) = roadAdmissionHill()
        val native = mockk<CivsWorld>()
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers { roadAdmissionWork(firstArg(), thirdArg()) }
        val maintenance = CivsRoadMaintenance(native) { 0 }
        val network = CivsRoadMaintenance.prepare(city, plans)
        maintenance.publish(city, network)
        val abandoned = maintenance.awaitGradeNetwork(city)
        maintenance.gradeNetworkCompleted(city.id, abandoned) // Successful stale load: no publish occurred.
        maintenance.admitDevelopment(city) shouldBe true
        val first = maintenance.awaitGradeNetwork(city)
        val second = maintenance.awaitGradeNetwork(city)
        maintenance.gradeNetworkCompleted(city.id, first)
        maintenance.admitDevelopment(city) shouldBe false
        maintenance.publish(city, network.copy(targets = network.targets.reversed()))
        val work = requireNotNull(maintenance.plan(city, city.workers.single()))
        maintenance.gradeNetworkCompleted(city.id, second)
        maintenance.admitDevelopment(city) shouldBe false
        maintenance.requirements(city) shouldBe mapOf(work.workerId to mapOf("COBBLESTONE" to 1L))
    }

    test("an unreachable admitted grade retires from this finite pass without crediting its uncut column") {
        val (city, plans) = roadAdmissionHill()
        val native = mockk<CivsWorld>()
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers { roadAdmissionWork(firstArg(), thirdArg()) }
        val maintenance = CivsRoadMaintenance(native) { 0 }
        maintenance.publish(city, CivsRoadMaintenance.prepare(city, plans))
        val work = requireNotNull(maintenance.plan(city, city.workers.single()))
        maintenance.admitDevelopment(city) shouldBe false
        maintenance.invalidate(work.workerId)
        maintenance.admitDevelopment(city) shouldBe true
        maintenance.requirements(city) shouldBe emptyMap()
        city.workers.single().completedCycles shouldBe work.cycle
        city.roadRouteOverrides shouldBe null
        city.workers.single().cargo shouldBe null
    }

    test("a complete zero payment observation releases the finite grade pass without changing resident items") {
        val (funded, plans) = roadAdmissionHill()
        val city = funded.copy(workers = funded.workers.map { it.copy(items = emptyList()) })
        val native = mockk<CivsWorld>()
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers { roadAdmissionWork(firstArg(), thirdArg()) }
        var observation: CivSupplySnapshot? = null
        val maintenance = CivsRoadMaintenance(native,
            paymentPossible = { CivsRoadMaintenance.paymentPossible(it, observation) }) { 0 }
        maintenance.publish(city, CivsRoadMaintenance.prepare(city, plans))
        val work = requireNotNull(maintenance.plan(city, city.workers.single()))
        maintenance.admitDevelopment(city) shouldBe false // An unfinished observation is not proof of missing payment.
        observation = supplyObservation(city)
        maintenance.admitDevelopment(city) shouldBe true
        maintenance.requirements(city) shouldBe emptyMap()
        city.workers.single().items shouldBe emptyList()
        city.workers.single().completedCycles shouldBe work.cycle
        city.roadRouteOverrides shouldBe null
    }

    test("carried local or durable incoming cobblestone retains the admitted payment priority") {
        val (funded, plans) = roadAdmissionHill()
        val empty = funded.copy(buildings = funded.buildings.mapIndexed { index, building ->
            if (index == 0) building.copy(control = building.anchor.copy(y = 64)) else building
        }, workers = funded.workers.map { it.copy(items = emptyList()) })
        val cargo = CivCargo(UUID(0, 20), empty.buildings.first().id, empty.buildings.first().id,
            empty.buildings[1].id, empty.buildings.first().entrance, "COBBLESTONE", 1, inventoryBacked = true)
        val courier = empty.workers.single().copy(id = UUID(0, 21), assignment = empty.buildings.first().id,
            items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 1) else null },
            cargo = cargo)
        val incoming = empty.copy(workers = empty.workers + courier)
        val cases = listOf(
            funded to supplyObservation(funded),
            empty to supplyObservation(empty, mapOf(empty.buildings.first().id to mapOf("COBBLESTONE" to 1L))),
            incoming to supplyObservation(incoming))
        cases.forEach { (city, observation) ->
            CivsRoadMaintenance.paymentPossible(city, observation) shouldBe true
            val native = mockk<CivsWorld>()
            val network = CivsRoadMaintenance.prepare(city, plans)
            every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers {
                roadAdmissionWork(city.copy(workers = listOf(city.workers.first())), thirdArg())
            }
            val maintenance = CivsRoadMaintenance(native,
                paymentPossible = { CivsRoadMaintenance.paymentPossible(it, observation) }) { 0 }
            maintenance.publish(city, network)
            val work = requireNotNull(maintenance.plan(city, city.workers.first()))
            maintenance.admitDevelopment(city) shouldBe false
            maintenance.requirements(city) shouldBe mapOf(work.workerId to mapOf("COBBLESTONE" to 1L))
            city.workers.mapNotNull(CivWorker::cargo) shouldBe if (city == incoming) listOf(cargo) else emptyList()
        }
        val pendingPickup = incoming.copy(workers = incoming.workers.map { worker ->
            if (worker.id == courier.id) worker.copy(items = emptyList(), cargo = cargo.copy(inventoryBacked = false)) else worker
        })
        CivsRoadMaintenance.paymentPossible(pendingPickup, supplyObservation(pendingPickup)) shouldBe false
        CivsRoadMaintenance.paymentPossible(empty, null) shouldBe null
        CivsRoadMaintenance.paymentPossible(empty, supplyObservation(empty).copy(inputKey = Unit)) shouldBe null
    }

    test("unavailable builders and legacy next-goal funds cannot create a grade admission deadlock") {
        val (city, plans) = roadAdmissionHill()
        val native = mockk<CivsWorld>()
        val maintenance = CivsRoadMaintenance(native) { 0 }
        maintenance.publish(city, CivsRoadMaintenance.prepare(city, plans))
        maintenance.admitDevelopment(city.copy(workers = emptyList())) shouldBe true
        maintenance.admitDevelopment(city.copy(inventory = null, stock = Stock())) shouldBe true
    }
})

private fun roadAdmissionHill(): Pair<Settlement, List<CivPlacementPlan>> {
    val base = roadAdmissionCity()
    val route = listOf(64, 64, 64, 65, 66, 67, 67, 67, 66, 65, 64, 64, 64)
        .mapIndexed { z, y -> CivPosition(0, y, z) }
    val source = base.buildings.last().copy(type = BuildingType.HOUSE, anchor = CivPosition(0, 63, 40),
        bounds = CivBox(CivPosition(0, 63, 40), CivPosition(5, 72, 45)), entrance = route.last(), transit = null)
    val city = base.copy(buildings = base.buildings.dropLast(1) + source)
    val plan = CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(0, 63, 0), CivPosition(0, 68, 12)), emptyList(), route, 63, 0, 0))
    return city to listOf(plan)
}
