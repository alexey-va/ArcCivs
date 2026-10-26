package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsResearchIndustryTest : FunSpec({
    test("old snapshots grant no research or products and supported funded terms survive the codec") {
        val old = industryCity().let { city -> city.copy(buildings = city.buildings.filter { it.type in CivsAutonomy.baseProfile }, workers = emptyList()) }
        researchRoundtrip(old) shouldBe old
        researchRoundtrip(old).research shouldBe null
        researchRoundtrip(old).autonomy!!.products shouldBe null
        val started = CivsResearch.begin(industryCity(), CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val project = started.research!!.project!!
        project.termsVersion shouldBe 1
        project.workRequired shouldBe 3
        project.cost shouldBe CivResearchCost(stock = Stock(24, 16, 4))
        val funded = CivsResearch.fund(started, project.id, 2_000)
        researchRoundtrip(funded) shouldBe funded
        CivsResearch.fund(researchRoundtrip(funded), project.id, 9_000) shouldBe funded
    }

    test("decoded projects reject unknown cyclic oversize zero-price and shortened unsupported terms") {
        val started = CivsResearch.begin(industryCity(), CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val state = started.research!!
        val project = state.project!!
        listOf(
            started.copy(research = state.copy(completed = setOf(CivResearchId.ENGINEERING))),
            started.copy(research = state.copy(project = project.copy(cost = CivResearchCost()))),
            started.copy(research = state.copy(project = project.copy(workRequired = 1))),
            started.copy(research = state.copy(project = project.copy(termsVersion = 99))),
            started.copy(research = state.copy(project = project.copy(workDone = 1))),
            started.copy(autonomy = started.autonomy!!.copy(products = mapOf(CivIndustryProduct.SAND to 65L))),
            started.copy(autonomy = started.autonomy!!.copy(products = mapOf(CivIndustryProduct.SAND to -1L))),
        ).forEach { invalid -> researchDenied("invalid-state") { researchRoundtrip(invalid) } }
        val unknown = Gson().toJson(started.copy(research = CivResearchState(setOf(CivResearchId.MATERIALS))))
            .replace("MATERIALS", "NOT_A_DISCOVERY")
        researchDenied("invalid-state") { CivsRules.validate(Gson().fromJson(unknown, Settlement::class.java)) }
        val unknownProduct = Gson().toJson(started.copy(autonomy = started.autonomy!!.copy(products = mapOf(CivIndustryProduct.SAND to 1L))))
            .replace("SAND", "NOT_A_PRODUCT")
        researchDenied("invalid-state") { CivsRules.validate(Gson().fromJson(unknownProduct, Settlement::class.java)) }
        val native = CivsResearch.begin(industryCity(false), CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        researchDenied("invalid-state") { researchRoundtrip(native.copy(research = native.research!!.copy(
            project = native.research!!.project!!.copy(cost = CivResearchCost(materials = mapOf("BOOK" to 1L)))))) }
    }

    test("research funds once and accepts only the funded current project worker cycle across pause restart and death") {
        var city = CivsResearch.begin(industryCity(), CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val project = city.research!!.project!!
        var worker = city.workers.single()
        researchDenied("research-unfunded") { CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, project.id, worker.nextDeliveryAt) }
        val before = city.stock
        city = CivsResearch.fund(city, project.id, 2_000)
        city.stock shouldBe before - project.cost.stock
        CivsResearch.fund(city, project.id, 3_000) shouldBe city
        worker = city.workers.single()
        val oldWorker = worker
        city = CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, project.id, worker.nextDeliveryAt)
        city.research!!.project!!.workDone shouldBe 1
        city.workers.single().skills shouldBe mapOf("UNIVERSITY" to 1L)
        researchDenied("stale-delivery") { CivsResearch.complete(city, worker.id, oldWorker.completedCycles, oldWorker.nextDeliveryAt, project.id, worker.nextDeliveryAt) }
        val paid = city.stock
        city = researchRoundtrip(CivsResearch.setPaused(city, project.id, true, worker.nextDeliveryAt))
        worker = city.workers.single()
        researchDenied("research-paused") { CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, project.id, worker.nextDeliveryAt) }
        val withoutWorker = CivsRules.validate(city.copy(workers = emptyList()))
        withoutWorker.research!!.project!!.workDone shouldBe 1
        withoutWorker.stock shouldBe paid
        city = researchRoundtrip(CivsResearch.setPaused(city, project.id, false, worker.nextDeliveryAt))
        worker = city.workers.single()
        researchDenied("stale-research") { CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, UUID.randomUUID(), worker.nextDeliveryAt) }
        repeat(2) {
            worker = city.workers.single()
            city = researchRoundtrip(CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, project.id, worker.nextDeliveryAt))
        }
        city.research shouldBe CivResearchState(setOf(CivResearchId.MATERIALS))
        city.workers.single().completedCycles shouldBe 3L
        city.workers.single().skills shouldBe mapOf("UNIVERSITY" to 3L)
        city.stock shouldBe paid
        CivsResearch.sessions(CivResearchId.METALLURGY) shouldBe 5
        CivsResearch.sessions(CivResearchId.GLASSWORK) shouldBe 5
        CivsResearch.sessions(CivResearchId.ENGINEERING) shouldBe 8
    }

    test("native research bill reaches the university before staffing and funding never uses abstract products") {
        val base = industryCity(false).copy(workers = emptyList())
        val city = CivsResearch.begin(base, CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val university = CivsResearch.university(city)!!
        val bill = mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L)
        CivsLogistics.desiredStock(city, university) shouldBe bill
        CivsProduction.inputs(city, university) shouldBe bill
        (civSupplyInputs(city) == civSupplyInputs(base)) shouldBe false
        researchDenied("materials-required") { CivsResearch.fund(city, city.research!!.project!!.id, 2_000) }
        val funded = CivsResearch.fund(city, city.research!!.project!!.id, 2_000, nativePaid = true)
        funded.stock shouldBe city.stock
        funded.autonomy shouldBe null
        CivsLogistics.desiredStock(funded, university) shouldBe emptyMap()
        (civSupplyInputs(funded) == civSupplyInputs(city)) shouldBe false
        CivsResearch.fund(funded, city.research!!.project!!.id, 4_000, nativePaid = true) shouldBe funded
    }

    test("one bound university keeps the paid city project when its researcher moves to a replacement") {
        // Reserve one of the fixed sixteen plots for this replacement-university scenario.
        val base = industryCity().let { it.copy(buildings = it.buildings.filterNot { b -> b.type == BuildingType.PLAN_BOARD }) }
        val original = CivsResearch.university(base)!!
        val replacement = industryBuilding(BuildingType.UNIVERSITY, 16).copy(id = UUID(0, 1))
        var city = CivsRules.validate(base.copy(buildings = base.buildings + replacement))
        CivsResearch.university(city) shouldBe original
        CivsRules.slots(city, original) shouldBe 1
        CivsRules.slots(city, replacement) shouldBe 0
        researchDenied("invalid-state") { CivsRules.validate(city.copy(workers = city.workers +
            city.workers.single().copy(id = UUID.randomUUID(), assignment = replacement.id))) }
        city = CivsResearch.begin(city, CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val projectId = city.research!!.project!!.id
        city = CivsResearch.fund(city, projectId, 2_000)
        val first = city.workers.single()
        city = CivsResearch.complete(city, first.id, first.completedCycles, first.nextDeliveryAt, projectId, first.nextDeliveryAt)
        val paid = city.stock
        city = researchRoundtrip(city.copy(buildings = city.buildings.filterNot { it.id == original.id },
            workers = city.workers.map { it.copy(assignment = replacement.id, nextDeliveryAt = it.nextDeliveryAt + 1) }))
        CivsResearch.university(city) shouldBe replacement
        city.research!!.project!!.workDone shouldBe 1
        CivsResearch.fund(city, projectId, 200_000) shouldBe city
        repeat(2) {
            val worker = city.workers.single()
            city = CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, projectId, worker.nextDeliveryAt)
        }
        city.research shouldBe CivResearchState(setOf(CivResearchId.MATERIALS))
        city.workers.single().completedCycles shouldBe 3L
        city.stock shouldBe paid
    }

    test("Auto research product samples and stock debit together with one immutable funding revision") {
        val base = industryCity().copy(research = CivResearchState(setOf(CivResearchId.MATERIALS)),
            autonomy = CivAutonomy(products = mapOf(CivIndustryProduct.RAW_IRON to 8L, CivIndustryProduct.CHARCOAL to 2L)))
        val city = CivsResearch.begin(base, CivResearchId.METALLURGY, UUID.randomUUID(), 1_000)
        val project = city.research!!.project!!
        val paid = CivsResearch.fund(city, project.id, 2_000)
        paid.stock shouldBe city.stock - Stock(8, 0, 2)
        paid.autonomy!!.products shouldBe null
        paid.inventory shouldBe null
        paid.revision shouldBe city.revision + 1
        CivsResearch.fund(researchRoundtrip(paid), project.id, 8_000) shouldBe paid
        val missing = city.copy(autonomy = city.autonomy!!.copy(products = mapOf(CivIndustryProduct.RAW_IRON to 7L, CivIndustryProduct.CHARCOAL to 2L)))
        researchDenied("research-unfunded") { CivsResearch.fund(missing, project.id, 2_000) }
        missing.stock shouldBe city.stock
    }

    test("fixed Auto charcoal recipe retains its accepted identity when the director's next demand changes") {
        val base = industryCity().copy(research = CivResearchState(setOf(CivResearchId.MATERIALS)),
            autonomy = CivAutonomy(products = mapOf(CivIndustryProduct.RAW_IRON to 8L)))
        val smeltery = base.buildings.first { it.type == BuildingType.SMELTERY }
        val worker = base.workers.single().copy(assignment = smeltery.id, nextDeliveryAt = 1_000)
        val city = base.copy(workers = listOf(worker))
        val work = requireNotNull(CivsIndustry.autonomousWork(city, smeltery))
        work.recipe.id shouldBe "CHARCOAL"
        val previous = requireNotNull(citizenPlan(city, worker))
        val project = CivResearchProject(UUID.randomUUID(), CivResearchId.METALLURGY,
            CivsResearch.cost(city, CivResearchId.METALLURGY), 5, funded = true)
        val demandChanged = CivsRules.validate(city.copy(research = city.research!!.copy(project = project),
            autonomy = city.autonomy!!.copy(products = mapOf(CivIndustryProduct.CHARCOAL to 1L))))
        CivsIndustry.autonomousWork(demandChanged, smeltery) shouldBe null // The next stage is quarry ore.
        citizenCurrentPlan(previous, CitizenPhase.RETURNING) { citizenPlan(demandChanged, worker) } shouldBe previous
        val completed = CivsIndustry.complete(demandChanged, worker.id, 0, 1_000, work, 61_000)
        completed.stock shouldBe demandChanged.stock - Stock(wood = 10)
        completed.autonomy!!.products shouldBe mapOf(CivIndustryProduct.CHARCOAL to 9L)
        completed.workers.single().skills shouldBe mapOf("SMELTERY" to 1L)
        completed.inventory shouldBe null
        researchDenied("stale-delivery") { CivsIndustry.complete(completed, worker.id, 0, 1_000, work, 62_000) }
        researchDenied("materials-missing") { CivsIndustry.complete(city.copy(stock = city.stock.copy(wood = 9)), worker.id, 0, 1_000, work, 61_000) }
        researchDenied("storage-full") { CivsIndustry.complete(city.copy(autonomy = city.autonomy!!.copy(
            products = mapOf(CivIndustryProduct.RAW_IRON to 8L, CivIndustryProduct.CHARCOAL to 60L))), worker.id, 0, 1_000, work, 61_000) }
        researchDenied("stale-delivery") { CivsIndustry.complete(city.copy(buildings = city.buildings.map {
            if (it.id == smeltery.id) it.copy(production = "STONE") else it }), worker.id, 0, 1_000, work, 61_000) }
    }

    test("quarry ore replaces stone and fixed native recipes preserve old forge choices while gating new ones") {
        val base = industryCity().copy(research = CivResearchState(setOf(CivResearchId.MATERIALS)))
        val quarry = base.buildings.first { it.type == BuildingType.QUARRY }
        val worker = base.workers.single().copy(assignment = quarry.id, nextDeliveryAt = 1_000)
        val city = base.copy(workers = listOf(worker))
        val work = requireNotNull(CivsIndustry.autonomousWork(city, quarry))
        val after = CivsIndustry.complete(city, worker.id, 0, 1_000, work, 61_000)
        after.stock.stone shouldBe city.stock.stone
        after.autonomy!!.products shouldBe mapOf(CivIndustryProduct.RAW_IRON to 1L)
        val personal = industryCity(false)
        val forge = personal.buildings.first { it.type == BuildingType.BLACKSMITH }
        CivsProduction.options(personal, forge) shouldBe listOf("IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE")
        val engineering = personal.copy(research = CivResearchState(CivResearchId.entries.toSet()))
        CivsProduction.options(engineering, forge).takeLast(2) shouldBe listOf("BUCKET", "SHEARS")
        CivsProduction.inputs(forge.copy(production = "BUCKET")) shouldBe mapOf("IRON_INGOT" to 3L)
        CivsProduction.inputs(forge.copy(production = "SHEARS")) shouldBe mapOf("IRON_INGOT" to 2L)
        val crusher = personal.buildings.first { it.type == BuildingType.CRUSHER }
        CivsProduction.options(personal, crusher.copy(level = 1)) shouldBe listOf("GRAVEL")
        val materials = personal.copy(research = CivResearchState(setOf(CivResearchId.MATERIALS)))
        CivsProduction.options(materials, crusher) shouldBe listOf("GRAVEL", "SAND")
        CivsProduction.inputs(crusher.copy(production = "SAND")) shouldBe mapOf("GRAVEL" to 2L)
        val smeltery = materials.buildings.first { it.type == BuildingType.SMELTERY }
        CivsProduction.inputs(smeltery.copy(production = "CHARCOAL")) shouldBe mapOf("OAK_LOG" to 8L, "OAK_PLANKS" to 6L)
        CivsProduction.amount(smeltery.copy(production = "CHARCOAL")) shouldBe 8L
        researchDenied("invalid-production") { CivsRules.setProduction(personal, personal.owner, forge.id, "BUCKET", 2_000) }
        val assigned = engineering.copy(workers = listOf(engineering.workers.single().copy(assignment = forge.id)))
        val first = CivsRules.setProduction(assigned, assigned.owner, forge.id, "BUCKET", 2_000)
        val second = CivsRules.setProduction(first, first.owner, forge.id, "SHEARS", 2_000)
        val reselected = CivsRules.setProduction(second, second.owner, forge.id, "BUCKET", 2_000)
        (reselected.workers.single().nextDeliveryAt > first.workers.single().nextDeliveryAt) shouldBe true
        reselected.workers.single().completedCycles shouldBe assigned.workers.single().completedCycles
        reselected.workers.single().skills shouldBe assigned.workers.single().skills
    }

    test("busy extra quarry finishes once then staffs the missing warehouse before research while sole producers remain") {
        val base = industryCity().copy(workers = emptyList())
        fun assigned(type: BuildingType) = CivWorker(UUID.randomUUID(), type.name, base.buildings.first { it.type == type }.id, nextDeliveryAt = 1_000)
        val food = assigned(BuildingType.FARM); val wood = assigned(BuildingType.FORESTER); val stone = assigned(BuildingType.QUARRY)
        val extra = assigned(BuildingType.QUARRY)
        val before = CivsRules.validate(base.copy(workers = listOf(food, wood, stone, extra)))
        val previous = requireNotNull(citizenPlan(before, extra))
        val project = CivResearchProject(UUID.randomUUID(), CivResearchId.MATERIALS, CivsResearch.cost(before, CivResearchId.MATERIALS), 3, funded = true)
        val city = before.copy(research = CivResearchState(project = project))
        CivsAutonomy.waitingReassignment(city, extra) shouldBe true
        listOf(food, wood, stone).forEach { CivsAutonomy.waitingReassignment(city, it) shouldBe false }
        CivsRules.autoAssign(city, city.owner, 1_000) { false } shouldBe city
        val deferred = requireNotNull(citizenPlan(city, extra))
        deferred.idleReason shouldBe "waiting-reassignment"
        citizenCurrentPlan(previous, CitizenPhase.WORKING) { deferred } shouldBe previous
        val cycle = previous.cycle()
        cycle.start(); cycle.arrive(0); cycle.arrive(0)
        for (at in 1_000L..60_000L step 1_000L) cycle.work(at)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.arrive(60_001); cycle.beginDelivery(60_001)
        val completed = CivsRules.deliver(city, city.owner, extra.id, extra.assignment!!, 0, 1_000, 60_001)
        val nextWorker = completed.workers.first { it.id == extra.id }
        val next = requireNotNull(citizenPlan(completed, nextWorker))
        citizenCurrentPlan(previous, CitizenPhase.DELIVERING) { next } shouldBe next
        next.cycle().phase shouldBe CitizenPhase.IDLE
        next.autonomousWork shouldBe null
        val reassigned = CivsRules.autoAssign(completed, completed.owner, 60_002) { it == extra.id }
        reassigned.workers.first { it.id == extra.id }.assignment shouldBe reassigned.buildings.first { it.type == BuildingType.WAREHOUSE }.id
        reassigned.workers.first { it.id == extra.id }.completedCycles shouldBe 1L
        listOf(food, wood, stone).forEach { expected -> reassigned.workers.first { it.id == expected.id }.assignment shouldBe expected.assignment }
        val paused = deferred.copy(idleReason = "autonomy-paused")
        citizenCurrentPlan(previous, CitizenPhase.WORKING) { paused } shouldBe paused
        citizenCurrentPlan(previous, CitizenPhase.PAUSED) { deferred } shouldBe deferred
        val changed = deferred.copy(token = deferred.token.copy(nextDeliveryAt = 2_000))
        citizenCurrentPlan(previous, CitizenPhase.WORKING) { changed } shouldBe changed
    }

    for (courierPresent in listOf(false, true)) test("a stationary guard fills ${if (courierPresent) "research" else "the missing warehouse"} without a fake completed cycle") {
        val base = industryCity().copy(workers = emptyList())
        fun assigned(type: BuildingType) = CivWorker(UUID.randomUUID(), type.name,
            base.buildings.first { it.type == type }.id, nextDeliveryAt = 1_000)
        val food = assigned(BuildingType.FARM); val wood = assigned(BuildingType.FORESTER); val stone = assigned(BuildingType.QUARRY)
        val guard = assigned(BuildingType.GUARD_TOWER)
        val courier = if (courierPresent) assigned(BuildingType.WAREHOUSE) else null
        val before = CivsRules.validate(base.copy(workers = listOfNotNull(food, wood, stone, courier, guard)))
        val previous = requireNotNull(citizenPlan(before, guard))
        val cycle = previous.cycle()
        cycle.start(); cycle.arrive(0); cycle.arrive(0)
        cycle.phase shouldBe CitizenPhase.WORKING
        previous.workMillis shouldBe Long.MAX_VALUE
        val project = CivResearchProject(UUID.randomUUID(), CivResearchId.MATERIALS,
            CivsResearch.cost(before, CivResearchId.MATERIALS), 3, funded = true)
        val city = before.copy(research = CivResearchState(project = project))
        val next = requireNotNull(citizenPlan(city, guard))
        next.idleReason shouldBe "waiting-reassignment"
        citizenCurrentPlan(previous, cycle.phase) { next } shouldBe next
        next.cycle().phase shouldBe CitizenPhase.IDLE
        next.autonomousWork shouldBe null
        next.courier shouldBe null
        next.worldWork shouldBe null
        val assigned = CivsRules.autoAssign(city, city.owner, 2_000) { it == guard.id }
        assigned.workers.last().assignment shouldBe if (courierPresent) CivsResearch.university(assigned)!!.id
            else assigned.buildings.first { it.type == BuildingType.WAREHOUSE }.id
        assigned.workers.last().completedCycles shouldBe guard.completedCycles
        assigned.workers.last().skills shouldBe guard.skills
        assigned.stock shouldBe city.stock
        listOf(food, wood, stone).forEach { expected -> assigned.workers.first { it.id == expected.id } shouldBe expected }
        courier?.let { assigned.workers.first { worker -> worker.id == it.id } shouldBe it }
    }

    test("full sixteen-plot village keeps its board and advances only researched hall and new industry upgrades") {
        val base = industryCity().copy(research = CivResearchState(CivResearchId.entries.toSet()))
        val board = base.buildings.single { it.type == BuildingType.PLAN_BOARD }
        val city = CivsRules.validate(base)
        city.buildings.size shouldBe 16
        CivsRules.buildingCount(city) shouldBe 16
        val hallGoal = CivsAutonomy.nextGoal(city)!!
        hallGoal.type shouldBe BuildingType.TOWN_HALL
        hallGoal.targetLevel shouldBe 3
        val hallThree = city.copy(buildings = city.buildings.map { if (it.type == BuildingType.TOWN_HALL) it.copy(level = 3) else it })
        CivsAutonomy.nextGoal(hallThree)!!.type shouldBe BuildingType.UNIVERSITY
        var finished = hallThree
        for (type in listOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER))
            finished = finished.copy(buildings = finished.buildings.map { if (it.type == type) it.copy(level = 3) else it })
        CivsAutonomy.nextGoal(finished) shouldBe null
        finished.buildings.first { it.type == BuildingType.PLAN_BOARD } shouldBe board
        finished.buildings.filter { it.type !in setOf(BuildingType.TOWN_HALL, BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.PLAN_BOARD) }
            .all { it.level == 2 } shouldBe true
        CivsResearch.required(BuildingType.SMELTERY, 2) shouldBe CivResearchId.MATERIALS
        CivsResearch.required(BuildingType.CRUSHER, 3) shouldBe CivResearchId.ENGINEERING
        CivsResearch.required(BuildingType.BLACKSMITH, 3) shouldBe null
    }
})

private fun industryBuilding(type: BuildingType, index: Int): CivBuilding {
    val x = index * 10
    val level = minOf(2, type.maxLevel)
    return CivBuilding(UUID.randomUUID(), type, level, CivPosition(x, 64, 0), 0, "fixture/${type.name}/$level",
        CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 69, 2)), CivPosition(x, 64, 3), BuildingPhase.ACTIVE,
        control = CivPosition(x, 64, 0))
}

private fun industryCity(autonomous: Boolean = true): Settlement {
    val buildings = (CivsAutonomy.baseProfile + listOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER,
        BuildingType.BLACKSMITH, BuildingType.HOUSE, BuildingType.HOUSE)).mapIndexed { index, type -> industryBuilding(type, index) }
    val university = buildings.first { it.type == BuildingType.UNIVERSITY }
    return CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Research", UUID.randomUUID(), CivPosition(0, 64, 0),
        256, "nordic", if (autonomous) Stock(128, 128, 64) else Stock(), buildings,
        listOf(CivWorker(UUID.randomUUID(), "Researcher", university.id, nextDeliveryAt = 1_000)),
        inventory = if (autonomous) null else CivInventory(CivStorageMode.VIRTUAL),
        autonomy = if (autonomous) CivAutonomy() else null))
}

private fun researchRoundtrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
private fun researchDenied(reason: String, action: () -> Unit) { shouldThrow<CivsRejection>(action).reason shouldBe reason }
