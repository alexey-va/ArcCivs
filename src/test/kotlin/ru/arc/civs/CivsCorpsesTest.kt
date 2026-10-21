package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsCorpsesTest : FunSpec({
    test("death transfers exact carried items into one persistent corpse with killer and expiry") {
        val owner = UUID.randomUUID()
        val hallId = UUID.randomUUID()
        val worker = CivWorker(UUID.randomUUID(), "Анна", hallId, nextDeliveryAt = 1000,
            items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_PICKAXE", 1)
                6 -> CivResidentItem("COBBLESTONE", 42)
                else -> null
            } })
        val hall = CivBuilding(hallId, BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "medieval/townhall/1",
            CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -3), BuildingPhase.ACTIVE,
            control = CivPosition(0, 64, 0))
        val city = CivsRules.validate(Settlement(UUID.randomUUID(), owner, "Город", UUID.randomUUID(),
            CivPosition(0, 64, 0), 64, "medieval", Stock(), listOf(hall), listOf(worker)))
        val death = CivDeathContext(CivPosition(3, 62, 4), 10_000, killerType = "ZOMBIE", cause = "ENTITY_ATTACK")
        val dead = CivsRules.hurtResident(city, owner, worker.id, 100.0, death)
        dead.workers shouldBe emptyList()
        val corpse = dead.corpses!!.single()
        corpse.worker.items shouldBe worker.items
        corpse.killerType shouldBe "ZOMBIE"
        corpse.expiresAt shouldBe 310_000L
        shouldThrow<CivsRejection> { CivsRules.hurtResident(dead, owner, worker.id, 1.0, death) }

        val root = Files.createTempDirectory("civs-corpse-")
        try {
            val store = CivsStore(root, Executor(Runnable::run))
            store.save(city).join()
            store.save(dead).join()
            CivsStore(root, Executor(Runnable::run)).load().join().single().corpses shouldBe dead.corpses
        } finally { root.toFile().deleteRecursively() }
    }

    test("one resident cannot be alive and lootable at once") {
        val worker = CivWorker(UUID.randomUUID(), "Иван", null)
        val corpse = CivsCorpses.create(worker, CivDeathContext(CivPosition(0, 64, 0), 1))
        val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Город", UUID.randomUUID(),
            CivPosition(0, 64, 0), 64, "medieval", Stock(), emptyList(), listOf(worker), corpses = listOf(corpse))
        shouldThrow<IllegalArgumentException> { CivsCorpses.validate(city) }
    }
})
