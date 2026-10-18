package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsStoreTest : FreeSpec({
    lateinit var root: Path

    beforeEach {
        root = Files.createTempDirectory("arc-civs-store-")
    }

    afterEach {
        root.toFile().deleteRecursively()
    }

    "atomically roundtrips validated cities and captured placement plans" {
        val city = settlement()
        val plan = placementPlan(city)
        val first = CivsStore(root, directExecutor)

        first.save(city).join() shouldBe city
        first.savePlan(plan).join() shouldBe plan

        val reopened = CivsStore(root, directExecutor)
        reopened.load().join() shouldBe listOf(city)
        reopened.loadPlan(plan.id).join() shouldBe plan
        reopened.deletePlan(plan.id).join() shouldBe true
        reopened.loadPlan(plan.id).join() shouldBe null
        reopened.deletePlan(plan.id).join() shouldBe false
    }

    "rejects duplicate owners and filename identity mismatches during load" {
        val city = settlement()
        val second = settlement(owner = city.owner)
        val cities = root.resolve("cities")
        Files.createDirectories(cities)
        writeCity(cities.resolve("${city.id}.json"), city)
        writeCity(cities.resolve("${second.id}.json"), second)

        shouldThrow<CompletionException> { CivsStore(root, directExecutor).load().join() }
        Files.exists(cities.resolve("${city.id}.json")) shouldBe true
        Files.exists(cities.resolve("${second.id}.json")) shouldBe true

        val mismatchedRoot = root.resolve("mismatched")
        val mismatchedDir = mismatchedRoot.resolve("cities")
        Files.createDirectories(mismatchedDir)
        writeCity(mismatchedDir.resolve("${city.id}.json"), city)
        writeCity(mismatchedDir.resolve("${UUID.randomUUID()}.json"), city)
        shouldThrow<CompletionException> { CivsStore(mismatchedRoot, directExecutor).load().join() }
    }

    "rejects cross-settlement owner and member overlap during queued saves and load" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val sharedMember = UUID.randomUUID()
        val first = settlement().copy(members = setOf(sharedMember))
        val ownerConflict = settlement(owner = sharedMember)
        val memberConflict = settlement().copy(members = setOf(first.owner))
        val firstSave = store.save(first)
        val ownerSave = store.save(ownerConflict)
        val memberSave = store.save(memberConflict)

        queued.runNext()
        firstSave.join() shouldBe first
        queued.runNext()
        shouldThrow<CompletionException> { ownerSave.join() }
        queued.runNext()
        shouldThrow<CompletionException> { memberSave.join() }
        Files.exists(root.resolve("cities/${ownerConflict.id}.json")) shouldBe false
        Files.exists(root.resolve("cities/${memberConflict.id}.json")) shouldBe false

        val corruptRoot = root.resolve("member-overlap")
        val corruptCities = corruptRoot.resolve("cities")
        Files.createDirectories(corruptCities)
        writeCity(corruptCities.resolve("${first.id}.json"), first)
        writeCity(corruptCities.resolve("${ownerConflict.id}.json"), ownerConflict)
        shouldThrow<CompletionException> { CivsStore(corruptRoot, directExecutor).load().join() }
        Files.exists(corruptCities.resolve("${first.id}.json")) shouldBe true
        Files.exists(corruptCities.resolve("${ownerConflict.id}.json")) shouldBe true
    }

    "enforces monotonic city revisions and allows only exact retry of a committed revision" {
        val store = CivsStore(root, directExecutor)
        val initial = settlement()
        store.save(initial).join() shouldBe initial

        val committed = initial.copy(name = "Updated Settlement", revision = 1)
        store.save(committed).join() shouldBe committed
        store.save(committed).join() shouldBe committed

        shouldThrow<CompletionException> { store.save(initial).join() }
        shouldThrow<CompletionException> { store.save(committed.copy(name = "Conflicting Retry")).join() }
        shouldThrow<CompletionException> { store.save(committed.copy(revision = 3)).join() }
        shouldThrow<CompletionException> { store.save(settlement().copy(revision = 1)).join() }
        store.load().join() shouldBe listOf(committed)
    }

    "deletion fences the full snapshot owner and revision before retaining an idempotent tombstone" {
        val initial = emptyInventory(settlement()).copy(members = setOf(UUID.randomUUID()))
        val store = CivsStore(root, directExecutor)
        store.save(initial).join()
        val committed = initial.copy(name = "Ready to delete", revision = 1)
        store.save(committed).join()
        val plan = placementPlan(committed)
        store.savePlan(plan).join()
        store.saveSchematic(plan.id, byteArrayOf(1, 2, 3)).join()

        shouldThrow<CompletionException> { store.delete(committed.copy(owner = UUID.randomUUID())).join() }
            .cause.let { it as CivsRejection }.reason shouldBe "owner-required"
        listOf(initial, committed.copy(name = "Different same revision"), committed.copy(revision = 2)).forEach { stale ->
            shouldThrow<CompletionException> { store.delete(stale).join() }
                .cause.let { it as CivsRejection }.reason shouldBe "stale-city"
        }
        store.load().join() shouldBe listOf(committed)
        Files.exists(root.resolve("deleted-cities/${committed.id}.json")) shouldBe false

        store.delete(committed).join() shouldBe Unit
        store.delete(committed).join() shouldBe Unit
        Files.exists(root.resolve("cities/${committed.id}.json")) shouldBe false
        Files.exists(root.resolve("deleted-cities/${committed.id}.json")) shouldBe true
        store.load().join() shouldBe emptyList()
        store.loadPlan(plan.id).join() shouldBe plan
        store.loadSchematic(plan.id).join()?.toList() shouldBe listOf<Byte>(1, 2, 3)
        shouldThrow<CompletionException> { store.delete(initial).join() }
            .cause.let { it as CivsRejection }.reason shouldBe "stale-city"
        listOf(initial, committed, committed.copy(revision = 2)).forEach { stale ->
            shouldThrow<CompletionException> { store.save(stale).join() }
                .cause.let { it as CivsRejection }.reason shouldBe "city-deleted"
        }
    }

    "durable deletion ignores a leftover old JSON and releases capacity without granting a second reserve" {
        val initial = settlement()
        val store = CivsStore(root, directExecutor, maximumCities = 1)
        store.save(initial).join() shouldBe initial
        val stocked = initial.copy(revision = 1)
        store.save(stocked).join()
        store.delete(stocked).join()
        // Simulate a crash after the tombstone commit, before old city-file cleanup.
        val oldPath = root.resolve("cities/${initial.id}.json")
        writeCity(oldPath, initial)
        val oldBytes = Files.readAllBytes(oldPath).toList()
        val reopened = CivsStore(root, directExecutor, maximumCities = 1)
        reopened.load().join() shouldBe emptyList()

        val proposal = settlement(owner = initial.owner)
        val recreated = emptyInventory(proposal)
        reopened.save(proposal).join() shouldBe recreated
        reopened.save(proposal).join() shouldBe recreated
        CivsStore(root, directExecutor, maximumCities = 1).load().join() shouldBe listOf(recreated)
        Files.readAllBytes(oldPath).toList() shouldBe oldBytes
        val earned = recreated.copy(revision = 1,
            inventory = requireNotNull(recreated.inventory).copy(materials = mapOf("BREAD" to 5L)))
        reopened.save(earned).join() shouldBe earned
        reopened.save(earned).join() shouldBe earned
    }

    "deletion history does not alter unrelated first grants or exact legacy revision-zero retries" {
        val deleted = emptyInventory(settlement())
        val store = CivsStore(root, directExecutor)
        store.save(deleted).join()
        store.delete(deleted).join()
        val unrelated = settlement()
        store.save(unrelated).join() shouldBe unrelated
        val legacy = settlement(owner = deleted.owner)
        writeCity(root.resolve("cities/${legacy.id}.json"), legacy)
        val reopened = CivsStore(root, directExecutor)
        reopened.load().join().toSet() shouldBe setOf(unrelated, legacy)
        reopened.save(legacy).join() shouldBe legacy
    }

    "a fresh legacy-shaped founding cannot turn owner history back into a migration grant" {
        val city = emptyInventory(settlement())
        val store = CivsStore(root, directExecutor)
        store.save(city).join()
        store.delete(city).join()
        val proposal = settlement(owner = city.owner).copy(inventory = null, stock = CivsRules.STARTING_STOCK)
        val recreated = proposal.copy(stock = Stock())
        store.save(proposal).join() shouldBe recreated
        store.save(proposal).join() shouldBe recreated
        CivsStore(root, directExecutor).load().join() shouldBe listOf(recreated)
        val unrelated = settlement().copy(inventory = null, stock = CivsRules.STARTING_STOCK)
        store.save(unrelated).join() shouldBe unrelated
    }

    "invalid bounded deletion history is preserved and blocks load or a resurrection save" {
        val city = emptyInventory(settlement())
        val store = CivsStore(root, directExecutor)
        store.save(city).join()
        store.delete(city).join()
        val path = root.resolve("deleted-cities/${city.id}.json")
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(CivsStore.MAX_DELETED_CITY_BYTES + 1) }
        shouldThrow<CompletionException> { CivsStore(root, directExecutor).load().join() }
        shouldThrow<CompletionException> { CivsStore(root, directExecutor).save(city).join() }
        Files.size(path) shouldBe CivsStore.MAX_DELETED_CITY_BYTES + 1
        val invalid = Gson().toJson(mapOf("schemaVersion" to 1,
            "deleted" to CivsDeletedCity(UUID.randomUUID(), city.owner, null, city.revision, 1_000)))
        Files.writeString(path, invalid)
        shouldThrow<CompletionException> { CivsStore(root, directExecutor).load().join() }
        Files.readString(path) shouldBe invalid
        Files.exists(root.resolve("cities/${city.id}.json")) shouldBe false
    }

    "queued deletion snapshots caller collections and finishes admitted work after close" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val members = mutableSetOf(UUID.randomUUID())
        val city = emptyInventory(settlement()).copy(members = members)
        val saved = store.save(city)
        queued.runNext()
        saved.join() shouldBe city
        val deletion = store.delete(city)
        members.clear()
        store.close()
        queued.runNext()
        deletion.join() shouldBe Unit
        shouldThrow<CompletionException> { store.delete(city).join() }
        CivsStore(root, directExecutor).load().join() shouldBe emptyList()
    }

    "rejects corrupt, future-schema and oversized city files without deleting them" {
        val corruptRoot = root.resolve("corrupt")
        val corrupt = corruptRoot.resolve("cities/${UUID.randomUUID()}.json")
        Files.createDirectories(corrupt.parent)
        Files.writeString(corrupt, "{broken")
        shouldThrow<CompletionException> { CivsStore(corruptRoot, directExecutor).load().join() }
        Files.exists(corrupt) shouldBe true

        val futureRoot = root.resolve("future")
        val future = futureRoot.resolve("cities/${UUID.randomUUID()}.json")
        Files.createDirectories(future.parent)
        Files.writeString(future, """{"schemaVersion":2,"city":{}}""")
        shouldThrow<CompletionException> { CivsStore(futureRoot, directExecutor).load().join() }
        Files.exists(future) shouldBe true

        val largeRoot = root.resolve("large")
        val large = largeRoot.resolve("cities/${UUID.randomUUID()}.json")
        Files.createDirectories(large.parent)
        Files.write(large, ByteArray(MAX_CITY_BYTES + 1))
        shouldThrow<CompletionException> { CivsStore(largeRoot, directExecutor).load().join() }
        Files.size(large) shouldBe (MAX_CITY_BYTES + 1).toLong()
    }

    "rejects an oversized existing placement plan before decoding it" {
        val id = UUID.randomUUID()
        val path = root.resolve("plans/$id.json")
        Files.createDirectories(path.parent)
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(MAX_PLAN_BYTES + 1) }

        shouldThrow<CompletionException> { CivsStore(root, directExecutor).loadPlan(id).join() }
        Files.size(path) shouldBe MAX_PLAN_BYTES + 1
    }

    "enforces configured city capacity and finishes accepted work after close" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued, maximumCities = 1)
        val city = settlement()
        val mutableBuildings = city.buildings.toMutableList()
        val accepted = store.save(city.copy(buildings = mutableBuildings))
        mutableBuildings.clear()
        store.close()

        shouldThrow<CompletionException> { store.save(settlement()).join() }
        queued.runNext()
        accepted.join() shouldBe city
        Files.exists(root.resolve("cities/${city.id}.json")) shouldBe true

        shouldThrow<CompletionException> {
            CivsStore(root, directExecutor, maximumCities = 1).save(settlement()).join()
        }
    }

    "rejects invalid placement plans before writing them" {
        val city = settlement()
        val store = CivsStore(root, directExecutor)
        val valid = placementPlan(city)
        val block = valid.blocks.single()

        val duplicate = valid.copy(blocks = listOf(block, block))
        shouldThrow<CompletionException> { store.savePlan(duplicate).join() }
        shouldThrow<CompletionException> { store.savePlan(valid.copy(capturedSha256 = "a".repeat(63))).join() }
        val outsideBounds = valid.copy(blocks = listOf(block.copy(position = CivPosition(99, 64, 99))))
        shouldThrow<CompletionException> { store.savePlan(outsideBounds).join() }
        val tooMany = valid.copy(blocks = List(MAX_PLAN_BLOCKS + 1) { block.copy(position = CivPosition(0, 64, 0)) })
        shouldThrow<CompletionException> { store.savePlan(tooMany).join() }
        Files.exists(root.resolve("plans/${valid.id}.json")) shouldBe false
    }

    "captures immutable schematic bytes, allows identical retries and rejects replacement" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val id = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val expected = bytes.copyOf()
        val pending = store.saveSchematic(id, bytes)
        bytes.fill(9)
        queued.runNext()
        pending.join() shouldBe Unit
        Files.readAllBytes(store.schematicRoot().resolve("$id.schem")).toList() shouldBe expected.toList()

        val directStore = CivsStore(root, directExecutor)
        directStore.saveSchematic(id, expected).join() shouldBe Unit
        val loaded = requireNotNull(directStore.loadSchematic(id).join())
        loaded.fill(8)
        directStore.loadSchematic(id).join()?.toList() shouldBe expected.toList()
        shouldThrow<CompletionException> { directStore.saveSchematic(id, byteArrayOf(5)).join() }
        Files.readAllBytes(directStore.schematicRoot().resolve("$id.schem")).toList() shouldBe expected.toList()
        directStore.deleteSchematic(id).join() shouldBe true
        directStore.deleteSchematic(id).join() shouldBe false
        directStore.loadSchematic(id).join() shouldBe null
    }

    "inventory journal allows only an exact retry, retains one pending receipt per city and reloads after reopen" {
        val transaction = inventoryTransaction(settlement())
        val store = CivsStore(root, directExecutor)
        store.saveInventoryTransaction(transaction).join() shouldBe transaction
        store.saveInventoryTransaction(transaction).join() shouldBe transaction
        shouldThrow<CompletionException> {
            store.saveInventoryTransaction(transaction.copy(after = transaction.after.copy(name = "Changed retry"))).join()
        }
        shouldThrow<CompletionException> { store.saveInventoryTransaction(transaction.copy(id = UUID.randomUUID())).join() }
        val reopened = CivsStore(root, directExecutor)
        reopened.loadInventoryTransactions().join() shouldBe listOf(transaction)
        reopened.deleteInventoryTransaction(transaction.id).join() shouldBe true
        reopened.deleteInventoryTransaction(transaction.id).join() shouldBe false
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
    }

    "queued journal captures immutable slots and nested building stock and finishes admitted work after close" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val base = inventoryTransaction(settlement())
        val cargo = mutableMapOf("BREAD" to 2L)
        val warehouses = mutableMapOf(base.after.buildings.single().id.toString() to cargo)
        val changes = base.changes.toMutableList()
        val mutable = base.copy(after = base.after.copy(inventory = requireNotNull(base.after.inventory).copy(
            buildingMaterials = warehouses)), changes = changes)
        val expected = mutable.copy(after = mutable.after.copy(inventory = requireNotNull(mutable.after.inventory).copy(
            buildingMaterials = warehouses.mapValues { it.value.toMap() })), changes = changes.toList())
        val accepted = store.saveInventoryTransaction(mutable)
        cargo["BREAD"] = 99
        warehouses.clear()
        changes.clear()
        store.close()
        shouldThrow<CompletionException> { store.loadInventoryTransactions().join() }
        queued.runNext()
        accepted.join() shouldBe expected
        CivsStore(root, directExecutor).loadInventoryTransactions().join() shouldBe listOf(expected)
    }

    "invalid inventory receipts are rejected before writing and corrupt records are preserved" {
        val transaction = inventoryTransaction(settlement())
        val store = CivsStore(root, directExecutor)
        val change = transaction.changes.single()
        listOf(
            transaction.copy(after = transaction.after.copy(revision = 7)),
            transaction.copy(changes = listOf(change, change)),
            transaction.copy(changes = listOf(change.copy(container = CivPosition(9, 64, 9)))),
            transaction.copy(changes = listOf(change.copy(slot = 27))),
            transaction.copy(changes = listOf(change.copy(after = CivPlainItem("minecraft:oak_log", 1)))),
        ).forEach { invalid -> shouldThrow<CompletionException> { store.saveInventoryTransaction(invalid).join() } }
        Files.exists(root.resolve("inventory-transactions/${transaction.id}.json")) shouldBe false

        val path = root.resolve("inventory-transactions/${transaction.id}.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "{broken")
        shouldThrow<CompletionException> { store.loadInventoryTransactions().join() }
        Files.readString(path) shouldBe "{broken"
    }

    "oversized inventory journals fail before decoding and identity mismatch never deletes the receipt" {
        val transaction = inventoryTransaction(settlement())
        val path = root.resolve("inventory-transactions/${transaction.id}.json")
        Files.createDirectories(path.parent)
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(CivsStore.MAX_INVENTORY_TRANSACTION_BYTES + 1) }
        shouldThrow<CompletionException> { CivsStore(root, directExecutor).loadInventoryTransactions().join() }
        Files.size(path) shouldBe CivsStore.MAX_INVENTORY_TRANSACTION_BYTES + 1
        Files.writeString(path, Gson().toJson(mapOf("schemaVersion" to 1,
            "transaction" to transaction.copy(id = UUID.randomUUID()))))
        shouldThrow<CompletionException> { CivsStore(root, directExecutor).deleteInventoryTransaction(transaction.id).join() }
        Files.exists(path) shouldBe true
    }
}) {
    companion object {
        private const val MAX_CITY_BYTES = 256 * 1024
        private const val MAX_PLAN_BYTES = 32L * 1024 * 1024
        private const val MAX_PLAN_BLOCKS = 150_000
        private val directExecutor = Executor { it.run() }

        private fun emptyInventory(city: Settlement): Settlement = city.copy(stock = Stock(),
            inventory = city.inventory?.let { it.copy(materials = emptyMap(),
                buildingMaterials = it.buildingMaterials?.mapValues { emptyMap() }) })

        private fun settlement(id: UUID = UUID.randomUUID(), owner: UUID = UUID.randomUUID()): Settlement {
            val hallId = UUID.randomUUID()
            val hall = CivBuilding(
                id = hallId,
                type = BuildingType.TOWN_HALL,
                level = 1,
                anchor = CivPosition(0, 64, 0),
                rotation = 0,
                schematicId = "minecolonies/nordic/fundamentals/townhall1",
                bounds = CivBox(CivPosition(-2, 60, -2), CivPosition(2, 68, 2)),
                entrance = CivPosition(0, 61, -2),
                phase = BuildingPhase.CONSTRUCTING,
            )
            return CivsRules.found(
                id = id,
                owner = owner,
                name = "Test Settlement",
                worldId = UUID.randomUUID(),
                center = CivPosition(0, 64, 0),
                radius = 16,
                style = "nordic",
                townHall = hall,
                now = 1_000,
            )
        }

        private fun placementPlan(city: Settlement): CivPlacementPlan {
            val building = requireNotNull(city.buildings.singleOrNull())
            return CivPlacementPlan(
                id = UUID.randomUUID(),
                cityId = city.id,
                worldId = city.worldId,
                building = building,
                blocks = listOf(CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:stone")),
            )
        }

        private fun inventoryTransaction(city: Settlement): CivInventoryTransaction {
            val control = CivPosition(3, 64, 0)
            val after = city.copy(revision = city.revision + 1,
                buildings = city.buildings.map { it.copy(control = control) })
            return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, listOf(
                CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("OAK_LOG", 2), CivPlainItem("OAK_LOG", 1)),
            ))
        }

        private fun writeCity(path: Path, city: Settlement) {
            Files.writeString(path, Gson().toJson(TestCityDocument(1, city)))
        }

        private data class TestCityDocument(val schemaVersion: Int, val city: Settlement)

        private class QueuedExecutor : Executor {
            private val tasks = ArrayDeque<Runnable>()

            override fun execute(command: Runnable) {
                tasks.addLast(command)
            }

            fun runNext() = tasks.removeFirst().run()
        }
    }
}
