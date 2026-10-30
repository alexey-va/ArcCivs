package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CivsResearchExpansionTest : FunSpec({
    test("all old discoveries keep their original native and legacy accepted terms") {
        val sessions = listOf(3, 5, 5, 8)
        val native = listOf(mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L),
            mapOf("BOOK" to 1L, "RAW_IRON" to 8L, "CHARCOAL" to 2L), mapOf("BOOK" to 1L, "SAND" to 8L, "CHARCOAL" to 2L),
            mapOf("BOOK" to 2L, "IRON_INGOT" to 8L, "GLASS" to 8L))
        CivResearchId.entries.take(4).forEachIndexed { index, id ->
            CivsResearch.sessions(id) shouldBe sessions[index]
            CivsResearch.cost(expansionCity(id), id).materials shouldBe native[index]
            for (legacy in listOf(false, true)) {
                val started = CivsResearch.begin(expansionCity(id, legacy), id, UUID.randomUUID(), 1_000)
                started.research!!.project!!.termsVersion shouldBe 1
                expansionRoundtrip(started) shouldBe started
                if (legacy) {
                    val migrated = CivsAutoStorageMigration.migrate(started)
                    migrated.research!!.project!!.termsVersion shouldBe 2
                    migrated.research!!.project!!.workRequired shouldBe sessions[index]
                    expansionRoundtrip(migrated) shouldBe migrated
                }
            }
        }
    }

    test("new catalogue matches the reviewed item costs and three observed durations") {
        val approved = JsonParser.parseString(Files.readString(Path.of("docs/research-expansion-134-assessment.json")))
            .asJsonObject.getAsJsonObject("proposed_new_projects")
        CivResearchId.entries.size shouldBe 16
        CivResearchId.entries.drop(4).forEach { id ->
            val expected = approved.getAsJsonObject(id.name).entrySet().associate { it.key to it.value.asLong }
            CivsResearch.cost(expansionCity(id), id).materials shouldBe expected
            CivsResearch.sessions(id) shouldBe listOf(30, 60, 120)[CivsResearch.tier(id) - 1]
            CivsResearch.universityLevel(id) shouldBe CivsResearch.tier(id)
            (CivsResearch.buildingRequirements(id).isNotEmpty()) shouldBe true
            CivsResearch.cost(expansionCity(id), id).materials.keys.none { it in setOf("BOOK", "PAPER", "LEATHER") } shouldBe true
        }
        CivResearchId.entries.sumOf(CivsResearch::sessions) shouldBe 861
        val started = CivsResearch.begin(expansionCity(CivResearchId.BUILDING_METHODS_III),
            CivResearchId.BUILDING_METHODS_III, UUID.randomUUID(), 1_000)
        val state = requireNotNull(started.research)
        val project = requireNotNull(state.project)
        listOf(project.copy(termsVersion = 1), project.copy(workRequired = 119),
            project.copy(cost = project.cost.copy(materials = project.cost.materials + ("COBBLESTONE" to 255L))))
            .forEach { invalid -> shouldThrow<CivsRejection> {
                expansionRoundtrip(started.copy(research = state.copy(project = invalid)))
            }.reason shouldBe "invalid-state" }
    }

    test("new legacy terms migrate to version four without changing funded progress pause or identity") {
        for (funded in listOf(false, true)) {
            val id = CivResearchId.BUILDING_METHODS_III
            var city = CivsResearch.begin(expansionCity(id, legacy = true), id, UUID.randomUUID(), 1_000)
            city.research!!.project!!.termsVersion shouldBe 3
            if (funded) city = CivsResearch.fund(city, city.research!!.project!!.id, 2_000)
            val project = city.research!!.project!!.copy(workDone = if (funded) 65 else 0, paused = true)
            city = expansionRoundtrip(city.copy(research = city.research!!.copy(project = project)))
            val migrated = CivsAutoStorageMigration.migrate(city)
            val expected = project.copy(cost = CivsAutoStorageMigration.nativeCost(project.cost), termsVersion = 4)
            migrated.research!!.project shouldBe expected
            expansionRoundtrip(migrated).research!!.project shouldBe expected
            val resumed = CivsResearch.setPaused(migrated, project.id, false, 3_000)
            if (funded) CivsResearch.fund(resumed, project.id, 4_000, nativePaid = true) shouldBe resumed
            shouldThrow<CivsRejection> { expansionRoundtrip(migrated.copy(research = migrated.research!!.copy(
                project = expected.copy(termsVersion = 2)))) }.reason shouldBe "invalid-state"
        }
    }

    test("native new research is funded once and progresses beyond sixty four through pause and reload") {
        val id = CivResearchId.BUILDING_METHODS_III
        var city = CivsResearch.begin(expansionCity(id), id, UUID.randomUUID(), 1_000)
        val projectId = city.research!!.project!!.id
        shouldThrow<CivsRejection> { CivsResearch.fund(city, projectId, 2_000) }.reason shouldBe "materials-required"
        city = CivsResearch.fund(city, projectId, 2_000, nativePaid = true)
        CivsResearch.fund(city, projectId, 3_000, nativePaid = true) shouldBe city
        repeat(65) {
            val worker = city.workers.single()
            city = CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, projectId, worker.nextDeliveryAt)
        }
        city.research!!.project!!.workDone shouldBe 65
        city = expansionRoundtrip(CivsResearch.setPaused(city, projectId, true, 10_000_000))
        city = CivsResearch.setPaused(city, projectId, false, 10_000_001)
        repeat(55) {
            val worker = city.workers.single()
            city = CivsResearch.complete(city, worker.id, worker.completedCycles, worker.nextDeliveryAt, projectId, worker.nextDeliveryAt)
        }
        (id in city.research!!.completed) shouldBe true
        city.research!!.project shouldBe null
    }

    test("effect tiers replace one another and leave unrelated work unchanged") {
        for ((index, id) in listOf(CivResearchId.BUILDING_METHODS_I, CivResearchId.BUILDING_METHODS_II, CivResearchId.BUILDING_METHODS_III).withIndex())
            CivsResearch.builderSessionRows(expansionCompleted(id)) shouldBe listOf(80, 96, 128)[index]
        for ((index, id) in listOf(CivResearchId.FURNACE_PRACTICE_I, CivResearchId.FURNACE_PRACTICE_II, CivResearchId.FURNACE_PRACTICE_III).withIndex()) {
            val city = expansionCompleted(id)
            CivsRules.deliveryInterval(city, city.buildings.first { it.type == BuildingType.SMELTERY }) shouldBe listOf(54_000L, 48_000L, 42_000L)[index]
            CivsRules.deliveryInterval(city, city.buildings.first { it.type == BuildingType.QUARRY }) shouldBe 60_000L
            val worker = city.workers.single().copy(assignment = city.buildings.first { it.type == BuildingType.SMELTERY }.id)
            citizenPlan(city, worker)!!.workMillis shouldBe listOf(54_000L, 48_000L, 42_000L)[index]
        }
        for ((index, id) in listOf(CivResearchId.CITY_WATCH_I, CivResearchId.CITY_WATCH_II, CivResearchId.CITY_WATCH_III).withIndex())
            CivsResearch.patrolRadius(expansionCompleted(id)) shouldBe listOf(40, 48, 64)[index]
    }

    test("an accepted ordinary meal keeps its nutrition when a meal discovery completes") {
        val plain = expansionCity(CivResearchId.COMMUNAL_MEALS_I)
        val worker = plain.workers.single().copy(hunger = 10, life = CivResidentLife(), items = List(CivsEquipment.SLOTS) {
            if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 4) else null })
        val before = plain.copy(workers = listOf(worker))
        val accepted = requireNotNull(CivsResidentLife.mealPlan(before, worker))
        accepted.nutrition shouldBe 6
        val upgraded = before.copy(research = CivResearchState(expansionCompleted(CivResearchId.COMMUNAL_MEALS_II).research!!.completed))
        val finished = CivsResidentLife.finishMeal(upgraded, accepted)
        finished.workers.single().hunger shouldBe 16
        CivsEquipment.plainCount(finished.workers.single(), "BREAD") shouldBe 3
        CivsResidentLife.mealPlan(upgraded, worker)!!.nutrition shouldBe 8
        val injured = worker.copy(hunger = 16, health = 19.0)
        CivsResidents.canRegenerate(injured, expansionCompleted(CivResearchId.COMMUNAL_MEALS_III)) shouldBe true
        CivsResidents.canRegenerate(injured) shouldBe false
        shouldThrow<CivsRejection> { CivsResidentLife.finishMeal(finished, accepted) }.reason shouldBe "stale-meal"
    }
})

private fun expansionPrerequisites(id: CivResearchId): Set<CivResearchId> = CivsResearch.prerequisites(id).flatMapTo(linkedSetOf()) {
    expansionPrerequisites(it) + it
}

private fun expansionCompleted(id: CivResearchId): Settlement = expansionCity(id).copy(research = CivResearchState(expansionPrerequisites(id) + id))

private fun expansionCity(id: CivResearchId, legacy: Boolean = false): Settlement {
    val types = listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY,
        BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.TAVERN, BuildingType.GUARD_TOWER,
        BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH, BuildingType.GATE)
    val buildings = types.mapIndexed { index, type ->
        val x = index * 10
        val gate = type == BuildingType.GATE
        CivBuilding(UUID.randomUUID(), type, minOf(3, type.maxLevel), CivPosition(x, 64, 0), 0, "fixture/$type",
            CivBox(CivPosition(if (gate) x else x - 2, 63, if (gate) 0 else -2), CivPosition(if (gate) x else x + 2, 69, if (gate) 0 else 2)),
            CivPosition(x, 64, 3), BuildingPhase.ACTIVE, control = if (gate) null else CivPosition(x, 64, 0),
            transit = if (gate) listOf(CivPosition(x, 64, 0)) else null)
    }
    val university = buildings.first { it.type == BuildingType.UNIVERSITY }
    return CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Expansion", UUID.randomUUID(), CivPosition(0, 64, 0),
        256, "fixture", if (legacy) Stock(512, 1024, 128) else Stock(), buildings,
        listOf(CivWorker(UUID.randomUUID(), "Researcher", university.id, nextDeliveryAt = 1_000)),
        inventory = if (legacy) null else CivInventory(CivStorageMode.VIRTUAL),
        autonomy = if (legacy) CivAutonomy(products = CivIndustryProduct.entries.associateWith { 64L }) else null,
        research = CivResearchState(expansionPrerequisites(id))))
}

private fun expansionRoundtrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
