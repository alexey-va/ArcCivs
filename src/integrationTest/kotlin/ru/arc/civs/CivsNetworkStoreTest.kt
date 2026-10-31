package ru.arc.civs

import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.network.BackendServerId
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestEndpoint
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CivsNetworkStoreTest : FreeSpec({
    var mysql: MySqlTestService? = null
    val mysqlSettings = MySqlTestSettings(image = "mysql:8.0.46", database = "arc_civs_test")
    val opened = mutableListOf<CivsNetworkStore>()
    lateinit var spawn: CivsNetworkStore
    lateinit var survival: CivsNetworkStore

    beforeSpec {
        mysql = MySqlTestService.start(mysqlSettings)
    }

    beforeEach {
        val endpoint = requireNotNull(mysql).endpoint
        spawn = openStore(endpoint, "spawn").also(opened::add)
        survival = openStore(endpoint, "survival").also(opened::add)
        spawn.loadOwned(emptyList()).await()
        survival.loadOwned(emptyList()).await()
        endpoint.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM arc_civs_member")
                statement.executeUpdate("DELETE FROM arc_civs_city")
                statement.executeUpdate("DELETE FROM arc_civs_deleted")
                statement.executeUpdate("DELETE FROM arc_civs_autonomous_deleted")
            }
        }
    }

    afterEach {
        opened.asReversed().forEach { it.closeAsync().await() }
        opened.clear()
    }

    afterSpec {
        mysql?.close()
    }

    "personal membership and multiple autonomous villages share a real administrator without competing" {
        val personal = settlement()
        fun village() = settlement(owner = personal.owner).copy(inventory = null,
            stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        val local = village()
        val foreign = village()
        spawn.save(local).await() shouldBe local
        survival.save(foreign).await() shouldBe foreign
        spawn.lookup(personal.owner).await() shouldBe null
        spawn.save(personal).await() shouldBe personal
        spawn.lookup(personal.owner).await()?.city shouldBe personal
        survival.lookup(personal.owner).await()?.city shouldBe personal
        spawn.loadOwned(emptyList()).await().shouldContainExactlyInAnyOrder(local, personal)
        survival.loadOwned(emptyList()).await() shouldBe listOf(foreign)
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 3
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 1
        failure { spawn.save(local.copy(autonomy = null, revision = 1)).await() }.shouldBeInstanceOf<IllegalStateException>()
        failure { spawn.save(personal.copy(autonomy = CivAutonomy(), inventory = null,
            stock = CivsRules.STARTING_STOCK, revision = 1)).await() }.shouldBeInstanceOf<IllegalStateException>()
    }

    "historical three-miner snapshot remains canonical until one durable staffing revision" {
        val base = settlement()
        val hall = base.buildings.single().copy(level = 3, phase = BuildingPhase.ACTIVE)
        val quarry = hall.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY,
            anchor = CivPosition(10, 64, 0), schematicId = "fixture/quarry3",
            bounds = CivBox(CivPosition(9, 63, -1), CivPosition(11, 68, 1)),
            entrance = CivPosition(10, 64, 2), control = CivPosition(10, 64, 0))
        val workers = (0..2).map { index -> CivWorker(UUID.randomUUID(), "Miner $index", quarry.id,
            nextDeliveryAt = 100L + index, completedCycles = index.toLong(), skills = mapOf("QUARRY" to index.toLong()),
            items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem("IRON_PICKAXE", 1) else null }) }
        val historical = CivsRules.validate(base.copy(buildings = listOf(hall, quarry), workers = workers,
            construction = null, immigration = null, stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
        spawn.save(historical).await() shouldBe historical
        val loaded = spawn.loadOwned(emptyList()).await().single()
        loaded shouldBe historical
        loaded.workers.count { it.assignment == quarry.id } shouldBe 3
        val normalized = CivsRules.reconcileQuarryStaffing(loaded, 10_000)
        normalized.revision shouldBe historical.revision + 1
        spawn.save(normalized).await() shouldBe normalized
        spawn.loadOwned(emptyList()).await() shouldBe listOf(normalized)
        spawn.lookup(historical.owner).await()?.city shouldBe normalized
        CivsRules.reconcileQuarryStaffing(normalized, 20_000) shouldBe normalized
        spawn.save(normalized).await() shouldBe normalized
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
        survival.loadOwned(emptyList()).await() shouldBe emptyList()
        normalized.workers.drop(1).zip(workers.drop(1)).forEach { (worker, before) ->
            worker shouldBe before.copy(assignment = null, nextDeliveryAt = 10_000)
        }
    }

    "autonomous tombstones do not consume personal founding reserves and preserve their own identity fence" {
        val removed = settlement().copy(inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        spawn.save(removed).await()
        spawn.delete(removed).await()
        requireNotNull(mysql).endpoint.connect().use { connection ->
            // This is the unchanged 0.1.60 backend query, without knowledge of autonomous mode.
            connection.prepareStatement("SELECT 1 FROM arc_civs_deleted WHERE owner_id = ? LIMIT 1").use { statement ->
                statement.setString(1, removed.owner.toString())
                statement.executeQuery().use { rows -> rows.next() shouldBe false }
            }
        }
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_autonomous_deleted") shouldBe 1
        failure { spawn.save(removed.copy(autonomy = null)).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
        val personal = settlement(owner = removed.owner)
        spawn.save(personal).await() shouldBe personal
        spawn.delete(personal).await()
        failure { spawn.save(personal.copy(inventory = null, stock = CivsRules.STARTING_STOCK,
            autonomy = CivAutonomy())).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
        val next = settlement(owner = removed.owner).copy(inventory = null,
            stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        spawn.save(next).await().stock shouldBe CivsRules.STARTING_STOCK
        spawn.lookup(removed.owner).await() shouldBe null
        failure { spawn.save(removed).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
        requireNotNull(mysql).endpoint.connect().use { connection ->
            connection.prepareStatement("SELECT 1 FROM arc_civs_deleted WHERE owner_id = ? LIMIT 1").use { statement ->
                statement.setString(1, removed.owner.toString())
                statement.executeQuery().use { rows -> rows.next() shouldBe true }
            }
        }
        val recreated = spawn.save(settlement(owner = removed.owner)).await()
        recreated.inventory!!.materials shouldBe emptyMap()
    }

    "a UUID present in both deletion histories is rejected instead of choosing one mode" {
        val removed = settlement().copy(inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        spawn.save(removed).await()
        spawn.delete(removed).await()
        requireNotNull(mysql).endpoint.connect().use { connection ->
            connection.prepareStatement("""INSERT INTO arc_civs_deleted(city_id, owner_id, server_id, revision, deleted_at_ms)
                SELECT city_id, owner_id, server_id, revision, deleted_at_ms FROM arc_civs_autonomous_deleted WHERE city_id = ?""").use { statement ->
                statement.setString(1, removed.id.toString())
                statement.executeUpdate() shouldBe 1
            }
        }
        failure { spawn.save(removed).await() }.shouldBeInstanceOf<IllegalStateException>().message.orEmpty() shouldContain "both deletion histories"
        failure { spawn.delete(removed).await() }.shouldBeInstanceOf<IllegalStateException>()
    }

    "two backends concurrently founding for one owner commit exactly one city" {
        val owner = UUID.randomUUID()
        val candidates = listOf(settlement(owner = owner), settlement(owner = owner))
        val ready = CyclicBarrier(2)
        val results = Executors.newFixedThreadPool(2).use { workers ->
            listOf(spawn, survival).zip(candidates).map { (store, city) ->
                CompletableFuture.supplyAsync({
                    ready.await(10, TimeUnit.SECONDS)
                    runCatching { store.save(city).await() }
                }, workers)
            }.map { it.await() }
        }

        results.count { it.isSuccess } shouldBe 1
        results.count { it.isFailure } shouldBe 1
        results.single { it.isFailure }.exceptionOrNull()!!.unwrapped()
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "already-member"
        val winner = results.single { it.isSuccess }.getOrThrow()
        spawn.lookup(owner).await()?.city shouldBe winner
        survival.lookup(owner).await()?.city shouldBe winner
        (spawn.loadOwned(emptyList()).await() + survival.loadOwned(emptyList()).await()) shouldBe listOf(winner)
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 1
    }

    "a conflicting participant rolls back every city and membership in one legacy import" {
        val member = UUID.randomUUID()
        val existing = settlement().copy(members = setOf(member))
        survival.save(existing).await() shouldBe existing
        val firstMember = UUID.randomUUID()
        val first = settlement().copy(members = setOf(firstMember))
        val conflicting = settlement().copy(members = setOf(member))

        failure { spawn.loadOwned(listOf(first, conflicting)).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "already-member"

        spawn.loadOwned(emptyList()).await() shouldBe emptyList()
        listOf(first.owner, firstMember, conflicting.owner).forEach { player ->
            spawn.lookup(player).await() shouldBe null
        }
        survival.lookup(existing.owner).await()?.city shouldBe existing
        spawn.lookup(member).await()?.city shouldBe existing
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 2
        spawn.save(first).await() shouldBe first
    }

    "home owner and revision fences preserve the canonical city and exact retry" {
        val initial = settlement()
        spawn.save(initial).await() shouldBe initial
        val replacementOwner = UUID.randomUUID()
        failure { survival.save(initial.copy(name = "Foreign writer", revision = 1)).await() }
            .shouldBeInstanceOf<IllegalStateException>()
        failure { spawn.save(initial.copy(owner = replacementOwner, revision = 1)).await() }
            .shouldBeInstanceOf<IllegalStateException>()

        val committed = initial.copy(name = "Canonical settlement", revision = 1)
        spawn.save(committed).await() shouldBe committed
        val beforeRetry = spawn.lookup(initial.owner).await()
        spawn.save(committed).await() shouldBe committed
        spawn.lookup(initial.owner).await() shouldBe beforeRetry

        failure { spawn.save(initial).await() }.shouldBeInstanceOf<IllegalStateException>()
        failure { spawn.save(committed.copy(name = "Different same revision")).await() }
            .shouldBeInstanceOf<IllegalStateException>()
        failure { spawn.save(committed.copy(revision = 3)).await() }.shouldBeInstanceOf<IllegalStateException>()
        failure { spawn.save(settlement().copy(revision = 1)).await() }.shouldBeInstanceOf<IllegalStateException>()
        failure { survival.loadOwned(listOf(committed)).await() }.shouldBeInstanceOf<IllegalStateException>()

        survival.lookup(initial.owner).await()?.city shouldBe committed
        spawn.lookup(replacementOwner).await() shouldBe null
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 1
    }

    "owned loads exclude foreign cities while global lookup resolves owners and members" {
        val spawnMember = UUID.randomUUID()
        val survivalMember = UUID.randomUUID()
        val local = settlement().copy(members = setOf(spawnMember))
        val foreign = settlement().copy(members = setOf(survivalMember))
        spawn.save(local).await()
        survival.save(foreign).await()

        spawn.loadOwned(emptyList()).await() shouldBe listOf(local)
        survival.loadOwned(emptyList()).await() shouldBe listOf(foreign)
        listOf(foreign.owner, survivalMember).forEach { player ->
            val found = requireNotNull(spawn.lookup(player).await())
            found.server shouldBe BackendServerId.of("survival")
            found.city shouldBe foreign
        }
        survival.lookup(spawnMember).await()?.city shouldBe local
        spawn.lookup(UUID.randomUUID()).await() shouldBe null
    }

    "legacy import keeps newer SQL snapshots and original files unchanged" {
        val root = Files.createTempDirectory("arc-civs-network-legacy-")
        try {
            val legacy = settlement()
            val canonical = legacy.copy(name = "Newer SQL settlement", revision = 1)
            spawn.save(legacy).await()
            spawn.save(canonical).await()
            val imported = settlement().copy(name = "Unimported legacy", revision = 7)
            val paths = listOf(legacy, imported).associate { city ->
                val path = root.resolve("cities/${city.id}.json")
                writeCity(path, city)
                path to Files.readAllBytes(path).toList()
            }

            CivsStore(root, directExecutor, network = spawn).use { store ->
                store.load().await().shouldContainExactlyInAnyOrder(canonical, imported)
                store.lookup(legacy.owner).await()?.city shouldBe canonical
                store.lookup(imported.owner).await()?.city shouldBe imported
                store.load().await().shouldContainExactlyInAnyOrder(canonical, imported)
            }
            paths.forEach { (path, original) -> Files.readAllBytes(path).toList() shouldBe original }
            survival.loadOwned(emptyList()).await() shouldBe emptyList()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "network city saves leave construction plans and captures local to their home" {
        val root = Files.createTempDirectory("arc-civs-network-plans-")
        try {
            val city = settlement()
            val plan = placementPlan(city)
            val bytes = byteArrayOf(1, 2, 3, 4)
            CivsStore(root, directExecutor, network = spawn).use { store ->
                store.save(city).await() shouldBe city
                store.savePlan(plan).await() shouldBe plan
                store.saveSchematic(plan.id, bytes).await() shouldBe Unit
                store.save(city.copy(name = "SQL updated city", revision = 1)).await()
                Files.exists(root.resolve("cities/${city.id}.json")) shouldBe false
            }

            CivsStore(root, directExecutor, network = spawn).use { reopened ->
                reopened.loadPlan(plan.id).await() shouldBe plan
                Files.readAllBytes(reopened.schematicRoot().resolve("${plan.id}.schem")).toList() shouldBe bytes.toList()
                reopened.lookup(city.owner).await()?.city?.revision shouldBe 1
            }
            survival.lookup(city.owner).await()?.server shouldBe BackendServerId.of("spawn")
            survival.loadOwned(emptyList()).await() shouldBe emptyList()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "deletion atomically releases all membership and permanently fences its UUID under the home owner and snapshot" {
        val member = UUID.randomUUID()
        val initial = settlement().copy(members = setOf(member))
        spawn.save(initial).await()
        val canonical = emptyInventory(initial).copy(revision = 1)
        spawn.save(canonical).await()

        failure { survival.delete(canonical).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "remote-city"
        failure { spawn.delete(canonical.copy(owner = UUID.randomUUID())).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "owner-required"
        listOf(initial, canonical.copy(name = "Different same revision"), canonical.copy(revision = 2)).forEach { stale ->
            failure { spawn.delete(stale).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "stale-city"
        }
        spawn.lookup(member).await()?.city shouldBe canonical
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 0

        spawn.delete(canonical).await() shouldBe Unit
        spawn.delete(canonical).await() shouldBe Unit
        listOf(canonical.owner, member).forEach { survival.lookup(it).await() shouldBe null }
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 0
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 0
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 1
        failure { survival.delete(canonical).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "remote-city"
        failure { spawn.delete(canonical.copy(revision = 0)).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "stale-city"
        listOf(initial, canonical, canonical.copy(revision = 2)).forEach { stale ->
            failure { spawn.save(stale).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
        }
        spawn.loadOwned(listOf(initial)).await() shouldBe emptyList()

        val proposal = settlement(owner = canonical.owner)
        val recreated = emptyInventory(proposal)
        survival.save(proposal).await() shouldBe recreated
        survival.save(proposal).await() shouldBe recreated
        spawn.lookup(canonical.owner).await()?.city shouldBe recreated
        val formerMember = settlement(owner = member)
        spawn.save(formerMember).await() shouldBe formerMember
        val earned = recreated.copy(revision = 1,
            inventory = requireNotNull(recreated.inventory).copy(materials = mapOf("BREAD" to 5L)))
        survival.save(earned).await() shouldBe earned
    }

    "legacy-shaped new founding cannot repeat a grant while legacy import remains lossless" {
        val deleted = emptyInventory(settlement())
        spawn.save(deleted).await()
        spawn.delete(deleted).await()
        val proposal = settlement(owner = deleted.owner).copy(inventory = null, stock = CivsRules.STARTING_STOCK)
        val recreated = proposal.copy(stock = Stock())
        survival.save(proposal).await() shouldBe recreated
        survival.save(proposal).await() shouldBe recreated
        spawn.lookup(deleted.owner).await()?.city shouldBe recreated
        survival.delete(recreated).await()
        val legacy = settlement(owner = deleted.owner).copy(inventory = null, stock = CivsRules.STARTING_STOCK)
        spawn.loadOwned(listOf(legacy)).await() shouldBe listOf(legacy)
        spawn.save(legacy).await() shouldBe legacy
    }

    "a racing deletion and revision update cannot resurrect or partially delete a city" {
        val city = emptyInventory(settlement()).copy(members = setOf(UUID.randomUUID()))
        spawn.save(city).await()
        val secondHome = openStore(requireNotNull(mysql).endpoint, "spawn").also(opened::add)
        secondHome.loadOwned(emptyList()).await()
        val updated = city.copy(name = "Updated before deletion", revision = 1)
        val ready = CyclicBarrier(2)
        val results = Executors.newFixedThreadPool(2).use { workers ->
            listOf<() -> Unit>({ spawn.delete(city).await() }, { secondHome.save(updated).await(); Unit }).map { action ->
                CompletableFuture.supplyAsync({ ready.await(10, TimeUnit.SECONDS); runCatching(action) }, workers)
            }.map { it.await() }
        }
        results.count { it.isSuccess } shouldBe 1
        results.count { it.isFailure } shouldBe 1
        val remaining = spawn.lookup(city.owner).await()?.city
        if (remaining == null) {
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 1
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 0
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 0
            results.single { it.isFailure }.exceptionOrNull()!!.unwrapped()
                .shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
        } else {
            remaining shouldBe updated
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 0
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 2
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
            results.single { it.isFailure }.exceptionOrNull()!!.unwrapped()
                .shouldBeInstanceOf<CivsRejection>().reason shouldBe "stale-city"
        }
    }

    "local deletion history imports before old JSON and preserves both owner history and genuine legacy reserves" {
        val root = Files.createTempDirectory("arc-civs-local-deletion-")
        try {
            val deleted = emptyInventory(settlement())
            CivsStore(root, directExecutor).use { local -> local.save(deleted).await(); local.delete(deleted).await() }
            val oldPath = root.resolve("cities/${deleted.id}.json")
            writeCity(oldPath, deleted)
            val oldBytes = Files.readAllBytes(oldPath).toList()
            // A different legacy UUID at revision zero is imported as-is, rather than treated as a fresh founding.
            val legacy = settlement(owner = deleted.owner)
            val legacyPath = root.resolve("cities/${legacy.id}.json")
            writeCity(legacyPath, legacy)
            val legacyBytes = Files.readAllBytes(legacyPath).toList()
            CivsStore(root, directExecutor, network = spawn).use { store ->
                store.load().await() shouldBe listOf(legacy)
                store.load().await() shouldBe listOf(legacy)
                store.save(legacy).await() shouldBe legacy
            }
            Files.readAllBytes(oldPath).toList() shouldBe oldBytes
            Files.readAllBytes(legacyPath).toList() shouldBe legacyBytes
            tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 1
            failure { spawn.save(deleted).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
            val emptied = emptyInventory(legacy).copy(revision = 1)
            spawn.save(emptied).await()
            spawn.delete(emptied).await()
            val next = settlement(owner = deleted.owner)
            survival.save(next).await() shouldBe emptyInventory(next)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "legacy deletion history cannot remove a canonical SQL city or commit a partial import" {
        val first = CivsDeletedCity(UUID.randomUUID(), UUID.randomUUID(), null, 0, 1_000)
        val live = emptyInventory(settlement())
        spawn.save(live).await()
        val conflicting = CivsDeletedCity(live.id, live.owner, null, live.revision, 1_000)
        failure { spawn.loadOwned(emptyList(), listOf(first, conflicting)).await() }.shouldBeInstanceOf<IllegalStateException>()
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_deleted") shouldBe 0
        spawn.lookup(live.owner).await()?.city shouldBe live
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 1
    }

    "network deletion persists local history before old JSON cleanup and preserves plans and captures" {
        val root = Files.createTempDirectory("arc-civs-network-deletion-")
        try {
            val initial = emptyInventory(settlement())
            val canonical = initial.copy(name = "Canonical before delete", revision = 1)
            val plan = placementPlan(initial)
            CivsStore(root, directExecutor, network = spawn).use { store ->
                store.save(initial).await()
                store.save(canonical).await()
                writeCity(root.resolve("cities/${initial.id}.json"), initial)
                store.savePlan(plan).await()
                store.saveSchematic(plan.id, byteArrayOf(1, 2, 3)).await()
                store.delete(canonical).await() shouldBe Unit
                store.delete(canonical).await() shouldBe Unit
            }
            Files.exists(root.resolve("cities/${initial.id}.json")) shouldBe false
            Files.exists(root.resolve("deleted-cities/${initial.id}.json")) shouldBe true
            CivsStore(root, directExecutor, network = spawn).use { reopened ->
                reopened.load().await() shouldBe emptyList()
                reopened.loadPlan(plan.id).await() shouldBe plan
                reopened.loadSchematic(plan.id).await()?.toList() shouldBe listOf<Byte>(1, 2, 3)
                reopened.delete(canonical).await() shouldBe Unit
            }
            val proposal = settlement(owner = canonical.owner)
            survival.save(proposal).await() shouldBe emptyInventory(proposal)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "failed initialization drains and closes owned SQL without waiting on its own worker" {
        val admin = requireNotNull(mysql).endpoint.copy(username = "root", password = mysqlSettings.rootPassword)
        val schema = "arc_civs_initialization_failure"
        admin.connect().use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE DATABASE $schema") }
        }
        try {
            val endpoint = admin.copy(database = schema)
            val seed = openStore(endpoint, "spawn")
            try {
                seed.loadOwned(emptyList()).await() shouldBe emptyList()
            } finally {
                seed.closeAsync().await()
            }
            endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE arc_civs_schema_history SET checksum = REPEAT('0', 64) WHERE version = 1") shouldBe 1
                }
            }

            val runtime = openRuntime(endpoint, "initialization-failure")
            val store = CivsNetworkStore.create(BackendServerId.of("spawn"), 500, CompletableFuture.completedFuture(runtime))
            try {
                // The old self-close waited five seconds on this same executor; no sleep is needed to expose it.
                val failure = shouldThrow<ExecutionException> { store.lookup(UUID.randomUUID()).get(3, TimeUnit.SECONDS) }
                    .unwrapped().shouldBeInstanceOf<IllegalStateException>()
                failure.message.orEmpty() shouldContain "checksum"
                runtime.dataSource.shouldBeInstanceOf<HikariDataSource>().isClosed shouldBe true
                runtime.executor.closeAsync().get(3, TimeUnit.SECONDS) shouldBe Unit
                shouldThrow<RejectedExecutionException> { runtime.executor.submit { Unit } }
                store.closeAsync().get(3, TimeUnit.SECONDS) shouldBe Unit
            } finally {
                store.closeAsync().await()
                runtime.close()
            }
        } finally {
            admin.connect().use { connection ->
                connection.createStatement().use { it.executeUpdate("DROP DATABASE $schema") }
            }
        }
    }
}) {
    companion object {
        private val directExecutor = Executor { it.run() }

        private fun emptyInventory(city: Settlement): Settlement = city.copy(stock = Stock(),
            inventory = city.inventory?.let { it.copy(materials = emptyMap(),
                buildingMaterials = it.buildingMaterials?.mapValues { emptyMap() }) })

        private fun openStore(endpoint: MySqlTestEndpoint, server: String): CivsNetworkStore =
            CivsNetworkStore.create(BackendServerId.of(server), 500, CompletableFuture.completedFuture(openRuntime(endpoint, server)))

        private fun openRuntime(endpoint: MySqlTestEndpoint, name: String): SqlRuntime =
            SqlRuntime.createOwned(
                SqlConnectionConfig(
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = SqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                    connectionTimeoutMs = 5_000,
                    socketTimeoutMs = 10_000,
                    validationTimeoutMs = 1_000,
                    maxLifetimeMs = 0,
                    failFast = true,
                ),
                "civs-test-$name",
            )

        private fun settlement(owner: UUID = UUID.randomUUID()): Settlement {
            val hall = CivBuilding(
                UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
                "minecolonies/nordic/fundamentals/townhall1",
                CivBox(CivPosition(-2, 60, -2), CivPosition(2, 68, 2)), CivPosition(0, 61, -2),
                BuildingPhase.CONSTRUCTING,
            )
            return CivsRules.found(
                UUID.randomUUID(), owner, "Test settlement", UUID.randomUUID(), CivPosition(0, 64, 0),
                16, "nordic", hall, 1_000,
            )
        }

        private fun placementPlan(city: Settlement) = CivPlacementPlan(
            UUID.randomUUID(), city.id, city.worldId, city.buildings.single(),
            listOf(CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:stone")),
        )

        private fun writeCity(path: Path, city: Settlement) {
            Files.createDirectories(path.parent)
            Files.writeString(path, Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "city" to city)))
        }

        private fun tableCount(endpoint: MySqlTestEndpoint, table: String): Int = endpoint.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
            }
        }

        private fun failure(block: () -> Unit): Throwable = shouldThrow<ExecutionException> { block() }.unwrapped()

        private fun Throwable.unwrapped(): Throwable =
            if ((this is ExecutionException || this is CompletionException) && cause != null) cause!!.unwrapped() else this

        private fun <T> CompletableFuture<T>.await(): T = get(30, TimeUnit.SECONDS)
    }
}
