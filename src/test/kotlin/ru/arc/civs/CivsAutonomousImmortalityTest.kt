package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsAutonomousImmortalityTest : FunSpec({
    fun id(n: Int) = UUID(0, n.toLong())
    fun city(): Settlement {
        fun building(n: Int, type: BuildingType) = CivBuilding(id(n), type, 3,
            CivPosition(n * 12, 64, 0), 0, "test",
            CivBox(CivPosition(n * 12 - 2, 63, -2), CivPosition(n * 12 + 2, 69, 2)),
            CivPosition(n * 12, 64, 3), BuildingPhase.ACTIVE)
        return CivsRules.validate(Settlement(id(50), id(51), "Immortal village", id(52), CivPosition(0, 64, 0),
            128, "test", Stock(), listOf(building(1, BuildingType.TOWN_HALL), building(2, BuildingType.HOUSE)),
            (100..104).map { CivWorker(id(it), "Resident $it", null, homeId = id(1),
                sex = if (it % 2 == 0) CivSex.MALE else CivSex.FEMALE) },
            inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy(),
            settings = CivSettlementSettings(immortalResidents = true)))
    }

    test("only the explicitly configured village retains immortality after a real store restart") {
        val root = Files.createTempDirectory("arc-civs-immortality-")
        try {
            val immortal = city()
            val default = immortal.copy(id = id(60), name = "Default village", settings = null)
            val executor = Executor { it.run() }
            CivsStore(root, executor).use { store ->
                store.save(immortal).join() shouldBe immortal
                store.save(default).join() shouldBe default
            }
            CivsStore(root, executor).use { store ->
                val restored = store.load().join().associateBy(Settlement::id)
                restored[immortal.id] shouldBe immortal
                restored[default.id] shouldBe default
                CivsResidentLife.autonomousImmortality(restored.getValue(immortal.id)) shouldBe true
                CivsResidentLife.autonomousImmortality(restored.getValue(default.id)) shouldBe false
            }
            val path = root.resolve("cities/${default.id}.json")
            val document = JsonParser.parseString(Files.readString(path)).asJsonObject
            document.getAsJsonObject("city").add("settings", Gson().toJsonTree(CivSettlementSettings()))
            document.getAsJsonObject("city").getAsJsonObject("settings").remove("immortalResidents")
            Files.writeString(path, Gson().toJson(document))
            CivsStore(root, executor).use { store ->
                val legacy = store.load().join().single { it.id == default.id }
                legacy.settings shouldBe CivSettlementSettings()
                CivsResidentLife.autonomousImmortality(legacy) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("durable village policy prevents lethal damage while defaults and ordinary permission decisions stay unchanged") {
        val protected = city()
        val worker = protected.workers.first()
        val death = CivDeathContext(protected.center, 10_000, cause = "LAVA")
        val injured = CivsRules.hurtResident(protected, protected.owner, worker.id, 100.0, death)
        injured.workers.first() shouldBe worker.copy(health = 1.0)
        injured.corpses shouldBe protected.corpses
        CivsRules.hurtResident(injured, injured.owner, worker.id, 100.0, death) shouldBe injured
        for (mortal in listOf(protected.copy(settings = null),
                protected.copy(settings = CivSettlementSettings()), protected.copy(autonomy = null))) {
            CivsRules.hurtResident(mortal, mortal.owner, worker.id, 100.0, death).workers.any { it.id == worker.id } shouldBe false
        }
        val ordinary = protected.copy(autonomy = null)
        CivsRules.hurtResident(ordinary, ordinary.owner, worker.id, 100.0, death, immortal = true)
            .workers.first().health shouldBe 1.0
    }

    test("an immortal elder avoids both the permanent death transition and the old age idle plan") {
        val base = city()
        val worker = base.workers.first().let { it.copy(life = CivResidentLife(
            awakeMinutes = CivsResidentLife.lifespanMinutes(it), adultMinutes = CivsResidentLife.lifespanMinutes(it))) }
        val immortal = base.copy(workers = listOf(worker))
        val proof = mapOf(worker.id to CivDeathContext(immortal.center, 10_000, cause = "OLD_AGE"))
        CivsPopulation.retireElder(immortal, proof) shouldBe immortal
        val mortal = immortal.copy(settings = null)
        CivsPopulation.retireElder(mortal, proof).workers shouldBe emptyList()
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val runtime = CitizenRuntime(paper.createSimplePlugin("ImmortalPlanning")) { listOf(immortal) }
                val plans = CivsCitizenPlanning(runtime,
                    routesOwner = { error("unused") }, couriersOwner = { error("unused") },
                    leisureOwner = { error("unused") }, workOwner = { error("unused") },
                    logisticsOwner = { error("unused") }, equipmentOwner = { error("unused") }, lifeOwner = { error("unused") },
                    courierPlan = { _, _ -> null }, workPlan = { _, _ -> null }, workIdleReason = { _, _ -> "waiting-growth" },
                    productionReady = { _, _ -> true }, workInputsReady = { _, _ -> true }, craftPlan = { _, _ -> null },
                    workstation = null, remove = { _, _ -> })
                plans.plan(mortal, worker)?.idleReason shouldBe "old-age-retirement"
                plans.plan(immortal, worker)?.idleReason shouldBe "unassigned"
            }
        }
    }

    test("immortality keeps active ageing child maturation education and new births working") {
        val base = city()
        val child = CivWorker(id(200), "Growing child", null, sex = CivSex.MALE, homeId = id(2),
            parentIds = setOf(id(100), id(101)), childMinutes = CivsPopulation.ADULT_MINUTES - 1,
            life = CivResidentLife(awakeMinutes = CivsPopulation.ADULT_MINUTES - 1L, education = mapOf("FARM" to 4L)))
        val family = CivFamily(id(100), id(101), id(2), CivsPopulation.BIRTH_MINUTES - 1)
        val before = CivsRules.validate(base.copy(workers = base.workers.map {
            if (it.id in child.parentIds!!) it.copy(homeId = id(2)) else it
        } + child, families = listOf(family)))
        val after = CivsPopulation.advance(before, before.workers.mapTo(hashSetOf(), CivWorker::id), { _, _, _ -> "New child" }, { id(201) })
        val adult = after.workers.single { it.id == child.id }
        adult.childMinutes shouldBe null
        adult.skills shouldBe mapOf("FARM" to 4L)
        adult.life?.adultMinutes shouldBe 0L
        after.workers.single { it.id == id(201) }.childMinutes shouldBe 0
        after.workers.first().life?.adultMinutes shouldBe 1L
        CivsResidentLife.autonomousImmortality(after) shouldBe true
    }
})
