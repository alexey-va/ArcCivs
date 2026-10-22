package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
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

    "legacy bread farms reload without rewriting history and select crop harvesting" {
        val base = settlement()
        val hall = base.buildings.single()
        val farm = hall.copy(id = UUID.randomUUID(), type = BuildingType.FARM, production = "BREAD",
            anchor = CivPosition(8, 64, 0), bounds = CivBox(CivPosition(6, 60, -2), CivPosition(10, 68, 2)),
            entrance = CivPosition(8, 64, -3), control = CivPosition(8, 64, 0), phase = BuildingPhase.ACTIVE)
        val city = base.copy(buildings = listOf(hall, farm))
        val store = CivsStore(root, directExecutor)
        store.save(city).join()
        val restored = CivsStore(root, directExecutor).load().join().single().buildings.last()
        restored.production shouldBe "BREAD"
        CivsProduction.options(BuildingType.FARM) shouldBe listOf("WHEAT", "CARROT", "POTATO")
        CivsProduction.material(restored) shouldBe "WHEAT"
        CivsProduction.inputs(restored) shouldBe emptyMap()
        val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
            city.copy(revision = city.revision + 1), listOf(CivInventorySlotChange(requireNotNull(farm.control),
                "BARREL", 0, CivPlainItem("WHEAT", 3), CivPlainItem("BREAD", 1))))
        store.saveInventoryTransaction(receipt).join()
        CivsStore(root, directExecutor).loadInventoryTransactions().join() shouldBe listOf(receipt)
    }

    "legacy mine ordinals and versioned continuation roundtrip without aliasing or accepting unknown geometry" {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val old = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
            .copy(mineProgress = mapOf(mine.id.toString() to 180L))
        val json = Gson().toJson(old)
        json.contains("minePlans") shouldBe false
        CivsRules.validate(Gson().fromJson(json, Settlement::class.java)).minePlans shouldBe null
        val store = CivsStore(root, directExecutor)
        store.save(old).join()
        CivsStore(root, directExecutor).load().join().single() shouldBe old

        val states = mutableMapOf(mine.id.toString() to CivMinePlanState(2, 3, 180))
        val current = old.copy(revision = old.revision + 1, minePlans = states)
        val executor = QueuedExecutor()
        val saving = CivsStore(root, executor).save(current)
        states.clear()
        executor.runNext()
        val saved = saving.join()
        val reopened = CivsStore(root, directExecutor).load().join().single()
        reopened shouldBe saved
        reopened.minePlans shouldBe mapOf(mine.id.toString() to CivMinePlanState(2, 3, 180))
        reopened.mineProgress shouldBe old.mineProgress
        val size = requireNotNull(CivsMinePlan.forBuilding(mine, 2)).steps.size.toLong()
        for (state in listOf(CivMinePlanState(1, 3, 180), CivMinePlanState(4, 3, 180),
            CivMinePlanState(2, -1, 180), CivMinePlanState(2, size + 1, 180), CivMinePlanState(2, 3, 179))) {
            shouldThrow<CivsRejection> { CivsRules.validate(reopened.copy(minePlans = mapOf(mine.id.toString() to state))) }
                .reason shouldBe "invalid-state"
        }
        val removed = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(reopened, reopened.owner, mine.id), reopened.owner)
        removed.minePlans.orEmpty() shouldBe emptyMap()
        removed.mineProgress.orEmpty() shouldBe emptyMap()
    }

    "queue session credit and deletion intent roundtrip through the existing city journal without aliasing" {
        val base = settlement()
        val hall = base.buildings.single().copy(phase = BuildingPhase.CONSTRUCTING)
        val site = hall.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
            anchor = CivPosition(8, 64, 0), bounds = CivBox(CivPosition(7, 63, 0), CivPosition(9, 67, 2)), entrance = CivPosition(8, 64, -1))
        val queue = mutableListOf(site.id)
        val order = CivConstruction(UUID.randomUUID(), hall.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS, Stock(),
            plannedBuilding = hall, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 2L),
            payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L)), workCredit = 2)
        val city = CivsRules.validate(base.copy(buildings = listOf(hall, site), construction = order, constructionQueue = queue))
        val executor = QueuedExecutor()
        val store = CivsStore(root, executor)
        val saving = store.save(city)
        queue.clear()
        executor.runNext()
        val saved = saving.join()
        saved.constructionQueue shouldBe listOf(site.id)
        saved.construction!!.workCredit shouldBe 2
        val reopened = CivsStore(root, directExecutor)
        reopened.load().join().single() shouldBe saved
        val pending = CivsRules.requestDeletion(saved, saved.owner, site.id)
        reopened.save(pending).join() shouldBe pending
        CivsStore(root, directExecutor).load().join().single() shouldBe pending
        val old = settlement()
        val oldStore = CivsStore(root.resolve("legacy"), directExecutor)
        oldStore.save(old).join()
        oldStore.load().join().single().let {
            it.constructionQueue shouldBe null
            it.pendingDeletion shouldBe null
            it.construction shouldBe null
        }
    }

    "member names survive reconnect and restart while UUIDs retain authority" {
        val member = UUID.randomUUID()
        val original = settlement().copy(members = setOf(member))
        val names = mutableMapOf(original.owner.toString() to "GrocerMC", member.toString() to "OldName")
        val store = CivsStore(root, directExecutor)
        store.save(original.copy(memberNames = names)).join()
        names.clear()
        val loaded = CivsStore(root, directExecutor).load().join().single()
        loaded.memberNames shouldBe mapOf(original.owner.toString() to "GrocerMC", member.toString() to "OldName")
        val renamed = CivsMembership.remember(loaded, mapOf(member to "NewName", UUID.randomUUID() to "Stranger"))
        renamed.memberNames shouldBe mapOf(original.owner.toString() to "GrocerMC", member.toString() to "NewName")
        renamed.canManage(member) shouldBe true
        val removed = CivsMembership.remember(renamed.copy(members = emptySet()), emptyMap())
        removed.memberNames shouldBe mapOf(original.owner.toString() to "GrocerMC")
        removed.canManage(member) shouldBe false
        store.save(renamed.copy(revision = renamed.revision + 1)).join()
        CivsStore(root, directExecutor).load().join().single().memberNames shouldBe renamed.memberNames
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

    "player source slots are captured immutably and survive reopening the inventory journal" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val base = inventoryTransaction(settlement())
        val source = mutableListOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 10), CivPlainItem("OAK_LOG", 6)))
        val transaction = base.copy(changes = listOf(base.changes.single().copy(before = null, after = CivPlainItem("OAK_LOG", 4))),
            playerDebit = CivPlayerDebit(base.after.owner, source))
        val expected = transaction.copy(playerDebit = transaction.playerDebit!!.copy(changes = source.toList()))
        val saved = store.saveInventoryTransaction(transaction)
        source.clear()
        queued.runNext()
        saved.join() shouldBe expected
        val reopened = CivsStore(root, directExecutor)
        reopened.loadInventoryTransactions().join() shouldBe listOf(expected)
        reopened.saveInventoryTransaction(expected).join() shouldBe expected
        val otherCity = inventoryTransaction(settlement(owner = expected.after.owner))
        val second = otherCity.copy(changes = listOf(otherCity.changes.single().copy(before = null, after = CivPlainItem("OAK_LOG", 4))),
            playerDebit = expected.playerDebit)
        shouldThrow<CompletionException> { reopened.saveInventoryTransaction(second).join() }
        Files.exists(root.resolve("inventory-transactions/${second.id}.json")) shouldBe false
        reopened.deleteInventoryTransaction(expected.id).join() shouldBe true
        reopened.saveInventoryTransaction(second).join() shouldBe second
    }

    "resident and world receipt fields are frozen once and share player custody with legacy debits" {
        val queued = QueuedExecutor()
        val store = CivsStore(root, queued)
        val base = inventoryTransaction(settlement())
        val workerId = UUID.randomUUID()
        val before = MutableList<CivResidentItem?>(33) { null }
        val items = before.toMutableList().apply { this[6] = CivResidentItem("OAK_LOG", 4) }
        val playerSlots = mutableListOf(CivNativePlayerSlot(0, CivResidentItem("OAK_LOG", 4), null))
        val world = mutableListOf(CivPlanBlock(CivPosition(2, 64, 1), "minecraft:stone", "minecraft:air"))
        val transaction = base.copy(changes = emptyList(), after = base.after.copy(workers = listOf(
            CivWorker(workerId, "Resident", null, items = items))),
            residentTransfer = CivResidentTransfer(base.after.owner, workerId, before, playerSlots), worldChanges = world)
        val expected = transaction.copy(after = transaction.after.copy(workers = transaction.after.workers.map { it.copy(items = it.items!!.toList()) }),
            residentTransfer = transaction.residentTransfer!!.copy(before = before.toList(), changes = playerSlots.toList()), worldChanges = world.toList())
        val saved = store.saveInventoryTransaction(transaction)
        before.clear(); items.clear(); playerSlots.clear(); world.clear()
        queued.runNext()
        saved.join() shouldBe expected
        val reopened = CivsStore(root, directExecutor)
        reopened.loadInventoryTransactions().join() shouldBe listOf(expected)
        val other = inventoryTransaction(settlement(owner = expected.after.owner))
        val debit = other.copy(changes = listOf(other.changes.single().copy(before = null, after = CivPlainItem("OAK_LOG", 4))),
            playerDebit = CivPlayerDebit(expected.after.owner, listOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 4), null))))
        shouldThrow<CompletionException> { reopened.saveInventoryTransaction(debit).join() }
    }

    "legacy receipts without a player endpoint still load and malformed player endpoints stay preserved" {
        val legacy = inventoryTransaction(settlement())
        val path = root.resolve("inventory-transactions/${legacy.id}.json")
        Files.createDirectories(path.parent)
        val json = Gson().toJson(mapOf("schemaVersion" to 1, "transaction" to legacy))
        json.contains("playerDebit") shouldBe false
        Files.writeString(path, json)
        val store = CivsStore(root, directExecutor)
        store.loadInventoryTransactions().join() shouldBe listOf(legacy)
        val invalid = legacy.copy(playerDebit = CivPlayerDebit(legacy.after.owner,
            listOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 10), CivPlainItem("OAK_LOG", 6)))))
        val invalidJson = Gson().toJson(mapOf("schemaVersion" to 1, "transaction" to invalid))
        Files.writeString(path, invalidJson)
        shouldThrow<CompletionException> { store.loadInventoryTransactions().join() }
        Files.readString(path) shouldBe invalidJson
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

    "inventory admission rejects an uncommittable city payload before writing its receipt" {
        val base = inventoryTransaction(settlement())
        val item = CivResidentItem("IRON_AXE", 1, Base64.getEncoder().encodeToString(ByteArray(22_500) { 7 }))
        val before = List<CivResidentItem?>(33) { if (it in 6..13) item else null }
        val worker = CivWorker(UUID.randomUUID(), "Resident", null, items = before)
        val initial = base.after.copy(revision = 0, workers = listOf(worker))
        val store = CivsStore(root, directExecutor)
        store.save(initial).join() shouldBe initial
        val after = before.toMutableList().apply { this[14] = item }
        val transaction = base.copy(changes = emptyList(), after = initial.copy(revision = 1,
            workers = listOf(worker.copy(items = after))), residentTransfer = CivResidentTransfer(
            initial.owner, worker.id, before, listOf(CivNativePlayerSlot(0, item, null))))
        transaction.validated() shouldBe transaction

        val failure = shouldThrow<CompletionException> { store.saveInventoryTransaction(transaction).join() }
        (failure.cause as CivsRejection).reason shouldBe "storage-transaction-too-large"
        Files.exists(root.resolve("inventory-transactions/${transaction.id}.json")) shouldBe false
        store.loadInventoryTransactions().join() shouldBe emptyList()
        store.load().join() shouldBe listOf(initial)
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
