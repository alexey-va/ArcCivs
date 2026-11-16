package ru.arc.civs

import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
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
import java.sql.SQLException
import java.util.Base64
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
    val roots = mutableListOf<Path>()
    lateinit var spawn: Home
    lateinit var survival: Home
    fun home(server: String, root: Path = Files.createTempDirectory("arc-civs-$server-").also(roots::add)): Home {
        val network = openStore(requireNotNull(mysql).endpoint, server).also(opened::add)
        return Home(root, network, CivsStore(root, directExecutor, network = network))
    }

    beforeSpec { mysql = MySqlTestService.start(mysqlSettings) }
    beforeEach {
        spawn = home("spawn")
        survival = home("survival")
        spawn.store.load().await() shouldBe emptyList()
        survival.store.load().await() shouldBe emptyList()
    }
    afterEach {
        try { opened.asReversed().forEach { it.closeAsync().await() } }
        finally {
            opened.clear()
            roots.forEach { it.toFile().deleteRecursively() }; roots.clear()
            requireNotNull(mysql).endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    listOf("arc_civs_member", "arc_civs_city", "arc_civs_deleted", "arc_civs_autonomous_deleted").forEach {
                        statement.executeUpdate("DELETE FROM $it")
                    }
                }
            }
        }
    }
    afterSpec { mysql?.close() }

    "ordinary city revisions commit locally without any SQL admission or directory changes" {
        var city = spawn.store.save(settlement()).await()
        val directory = directoryRow(requireNotNull(mysql).endpoint, city.id)
        directory["pending_snapshot"] shouldBe null
        spawn.network.closeAsync().await() // Any accidental SQL read or write now fails.
        repeat(20) { step ->
            city = spawn.store.save(city.copy(revision = city.revision + 1,
                settings = CivSettlementSettings(chunkLoading = step % 2 == 0))).await()
        }
        directoryRow(requireNotNull(mysql).endpoint, city.id) shouldBe directory
        CivsStore(spawn.root, directExecutor).load().await() shouldBe listOf(city)
        home("spawn", spawn.root).store.load().await() shouldBe listOf(city)
    }

    "global founding admits one owner on exactly one home without a losing local city" {
        val owner = UUID.randomUUID()
        val cities = listOf(settlement(owner), settlement(owner)).map { it.copy(name = "N".repeat(48)) }
        val homes = listOf(spawn, survival)
        val barrier = CyclicBarrier(2)
        val results = Executors.newFixedThreadPool(2).use { executor ->
            homes.zip(cities).map { (target, city) -> CompletableFuture.supplyAsync({
                barrier.await(10, TimeUnit.SECONDS); runCatching { target.store.save(city).await() }
            }, executor) }.map { it.await() }
        }
        results.count { it.isSuccess } shouldBe 1
        val loser = results.indexOfFirst { it.isFailure }
        results[loser].exceptionOrNull()!!.unwrapped().shouldBeInstanceOf<CivsRejection>().reason shouldBe "already-member"
        Files.exists(homes[loser].root.resolve("cities/${cities[loser].id}.json")) shouldBe false
        val winner = results.single { it.isSuccess }.getOrThrow()
        spawn.store.lookup(owner).await()?.id shouldBe winner.id
        survival.store.lookup(owner).await()?.id shouldBe winner.id
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 1
    }

    "membership updates remain globally unique after many local revisions" {
        val first = spawn.store.save(settlement()).await()
        val second = survival.store.save(settlement()).await()
        val member = UUID.randomUUID()
        var local = first
        repeat(7) { local = spawn.store.save(local.copy(revision = local.revision + 1)).await() }
        val accepted = spawn.store.save(local.copy(members = setOf(member), revision = local.revision + 1)).await()
        failure { survival.store.save(second.copy(members = setOf(member), revision = 1)).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "already-member"
        home("survival", survival.root).store.load().await() shouldBe listOf(second)
        survival.store.lookup(member).await() shouldBe CivsNetworkCity(BackendServerId.of("spawn"), accepted.id,
            accepted.owner, accepted.name, requireNotNull(spawn.store.lookup(member).await()).updatedAt)
        val removed = spawn.store.save(accepted.copy(members = emptySet(), revision = accepted.revision + 1)).await()
        survival.store.lookup(member).await() shouldBe null
        spawn.store.load().await() shouldBe listOf(removed)
        directoryRow(requireNotNull(mysql).endpoint, first.id)["pending_snapshot"] shouldBe null
    }

    "home owner mode revision and exact retry fences preserve acknowledged files" {
        val initial = spawn.store.save(settlement()).await()
        failure { survival.store.save(initial).await() }
        failure { spawn.store.save(initial.copy(owner = UUID.randomUUID(), revision = 1)).await() }
        failure { spawn.store.save(initial.copy(autonomy = CivAutonomy(), inventory = null, revision = 1)).await() }
        val next = spawn.store.save(initial.copy(name = "Renamed settlement", revision = 1)).await()
        val bytes = Files.readAllBytes(spawn.root.resolve("cities/${next.id}.json")).toList()
        spawn.store.save(next).await() shouldBe next
        listOf(initial, next.copy(name = "Conflicting retry"), next.copy(revision = 3)).forEach {
            failure { spawn.store.save(it).await() }
        }
        Files.readAllBytes(spawn.root.resolve("cities/${next.id}.json")).toList() shouldBe bytes
        survival.store.load().await() shouldBe emptyList()
        survival.store.lookup(next.owner).await()?.name shouldBe next.name
    }

    "pending founding and membership recover their exact accepted city before clearing SQL" {
        val city = settlement()
        failure { spawn.network.save(city, null) { CompletableFuture.failedFuture(IllegalStateException("lost before local ACK")) }.await() }
        Files.exists(spawn.root.resolve("cities/${city.id}.json")) shouldBe false
        val reopened = home("spawn", spawn.root)
        reopened.store.load().await() shouldBe listOf(city)
        directoryRow(requireNotNull(mysql).endpoint, city.id)["pending_snapshot"] shouldBe null
        val next = city.copy(members = setOf(UUID.randomUUID()), revision = 1)
        failure { reopened.network.save(next, city) { CompletableFuture.failedFuture(IllegalStateException("disk failure")) }.await() }
        CivsStore(spawn.root, directExecutor).load().await() shouldBe listOf(city)
        home("spawn", spawn.root).store.load().await() shouldBe listOf(next)
        directoryRow(requireNotNull(mysql).endpoint, city.id)["pending_snapshot"] shouldBe null
    }

    "lost local acknowledgement is replayable but a newer acknowledged local revision is never overwritten" {
        val city = spawn.store.save(settlement()).await()
        val next = city.copy(name = "Accepted next", revision = 1)
        failure { spawn.network.save(next, city) { accepted ->
            CivsStore(spawn.root, directExecutor).save(accepted).thenCompose<Settlement> {
                CompletableFuture.failedFuture(IllegalStateException("lost local acknowledgement"))
            }
        }.await() }
        home("spawn", spawn.root).store.load().await() shouldBe listOf(next)
        val pending = next.copy(name = "Pending later", revision = 2)
        failure { spawn.network.save(pending, next) { CompletableFuture.failedFuture(IllegalStateException("disk unavailable")) }.await() }
        val newer = pending.copy(revision = 3)
        writeCity(spawn.root.resolve("cities/${city.id}.json"), newer)
        failure { home("spawn", spawn.root).store.load().await() }.message.orEmpty() shouldContain "overwrite"
        CivsStore(spawn.root, directExecutor).load().await() shouldBe listOf(newer)
        directoryRow(requireNotNull(mysql).endpoint, city.id)["pending_snapshot"] shouldBe Gson().toJson(pending)
    }

    "missing acknowledged files and unregistered local files fail closed" {
        val city = spawn.store.save(settlement()).await()
        Files.delete(spawn.root.resolve("cities/${city.id}.json"))
        failure { home("spawn", spawn.root).store.load().await() }.message.orEmpty() shouldContain "missing"
        writeCity(spawn.root.resolve("cities/${city.id}.json"), city)
        val orphan = settlement()
        writeCity(spawn.root.resolve("cities/${orphan.id}.json"), orphan)
        failure { home("spawn", spawn.root).store.load().await() }.message.orEmpty() shouldContain "re-registration"
        spawn.store.lookup(orphan.owner).await() shouldBe null
        Files.exists(spawn.root.resolve("cities/${orphan.id}.json")) shouldBe true
    }

    "SQL tombstones precede stale files and preserve personal versus autonomous founding history" {
        val personal = spawn.store.save(settlement()).await()
        val village = spawn.store.save(settlement(personal.owner).copy(inventory = null,
            stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())).await()
        spawn.store.lookup(personal.owner).await()?.id shouldBe personal.id
        failure { spawn.network.delete(personal) { CompletableFuture.failedFuture(IllegalStateException("local cleanup unavailable")) }.await() }
        Files.exists(spawn.root.resolve("cities/${personal.id}.json")) shouldBe true
        home("spawn", spawn.root).store.load().await() shouldBe listOf(village)
        Files.exists(spawn.root.resolve("deleted-cities/${personal.id}.json")) shouldBe true
        survival.store.lookup(personal.owner).await() shouldBe null
        val proposal = settlement(personal.owner)
        survival.store.save(proposal).await() shouldBe emptyInventory(proposal)
        val autoOwner = UUID.randomUUID()
        val auto = spawn.store.save(settlement(autoOwner).copy(inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())).await()
        spawn.store.delete(auto).await()
        val firstPersonal = settlement(autoOwner)
        survival.store.save(firstPersonal).await() shouldBe firstPersonal
        failure { spawn.store.save(auto).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
    }

    "deletion validates the complete local snapshot and retains plans and captures" {
        val city = spawn.store.save(settlement().copy(members = setOf(UUID.randomUUID()))).await()
        val plan = placementPlan(city)
        spawn.store.savePlan(plan).await()
        spawn.store.saveSchematic(plan.id, byteArrayOf(1, 2, 3)).await()
        failure { spawn.store.delete(city.copy(name = "Different same revision")).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "stale-city"
        failure { spawn.store.delete(city.copy(owner = UUID.randomUUID())).await() }
            .shouldBeInstanceOf<CivsRejection>().reason shouldBe "owner-required"
        spawn.store.delete(city).await()
        spawn.store.delete(city).await()
        (city.members + city.owner).forEach { survival.store.lookup(it).await() shouldBe null }
        val cold = home("spawn", spawn.root).store
        cold.load().await() shouldBe emptyList()
        cold.loadPlan(plan.id).await() shouldBe plan
        cold.loadSchematic(plan.id).await()?.toList() shouldBe listOf<Byte>(1, 2, 3)
        Files.exists(spawn.root.resolve("cities/${city.id}.json")) shouldBe false
        failure { cold.save(city).await() }.shouldBeInstanceOf<CivsRejection>().reason shouldBe "city-deleted"
    }

    "an admitted SQL chain drains its worker-local acknowledgement and rejects new admissions" {
        val city = settlement()
        val caller = Thread.currentThread()
        val entered = CompletableFuture<Thread>()
        val acknowledged = CompletableFuture<Settlement>()
        val saving = spawn.network.save(city, null) {
            entered.complete(Thread.currentThread())
            acknowledged
        }
        (entered.await() == caller) shouldBe false
        val closing = spawn.network.closeAsync()
        closing.isDone shouldBe false
        failure { spawn.network.lookup(city.owner).await() }
        acknowledged.complete(CivsStore(spawn.root, directExecutor).save(city).await())
        saving.await() shouldBe city
        closing.await() shouldBe Unit
        directoryRow(requireNotNull(mysql).endpoint, city.id)["pending_snapshot"] shouldBe null
    }

    "migration copies exact SQL city and retains an unfinished item receipt before dropping its SQL payload" {
        opened.forEach { it.closeAsync().await() }
        val canonical = atPayloadLimit()
        Gson().toJson(canonical).toByteArray(Charsets.UTF_8).size.toLong() shouldBe CivsStore.MAX_CITY_BYTES
        val old = canonical.copy(workers = emptyList(), revision = 0)
        val after = canonical.copy(revision = 18)
        val control = old.center
        val receipt = CivInventoryTransaction(UUID.randomUUID(), canonical.id, canonical.revision, after,
            listOf(CivInventorySlotChange(control, "BARREL", 0, null, CivPlainItem("COAL", 1))))
        writeCity(spawn.root.resolve("cities/${old.id}.json"), old)
        CivsStore(spawn.root, directExecutor).saveInventoryTransaction(receipt).await()
        val endpoint = requireNotNull(mysql).endpoint
        endpoint.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE arc_civs_city CHANGE COLUMN pending_snapshot snapshot MEDIUMTEXT NOT NULL, DROP COLUMN city_name, DROP COLUMN autonomous")
                statement.executeUpdate("DELETE FROM arc_civs_schema_history WHERE version = 4")
            }
            connection.prepareStatement("INSERT INTO arc_civs_city(city_id,server_id,owner_id,revision,snapshot,updated_at_ms) VALUES (?,?,?,?,?,?)").use {
                it.setString(1, canonical.id.toString()); it.setString(2, "spawn"); it.setString(3, canonical.owner.toString())
                it.setLong(4, canonical.revision); it.setString(5, Gson().toJson(canonical)); it.setLong(6, 1_000); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO arc_civs_member(player_id,city_id) VALUES (?,?)").use {
                it.setString(1, canonical.owner.toString()); it.setString(2, canonical.id.toString()); it.executeUpdate()
            }
        }
        val migrated = home("spawn", spawn.root)
        migrated.store.load().await() shouldBe listOf(canonical)
        migrated.store.loadInventoryTransactions().await() shouldBe listOf(receipt)
        directoryRow(endpoint, canonical.id)["pending_snapshot"] shouldBe null
        migrated.store.save(after).await() shouldBe after
        migrated.store.deleteInventoryTransaction(receipt.id).await()
        home("spawn", spawn.root).store.load().await() shouldBe listOf(after)
        endpoint.connect().use { connection -> connection.createStatement().use { statement ->
            shouldThrow<SQLException> { statement.executeQuery("SELECT snapshot FROM arc_civs_city") }
            shouldThrow<SQLException> { statement.executeUpdate("INSERT INTO arc_civs_city(city_id,server_id,owner_id,revision,snapshot,updated_at_ms) VALUES ('x','spawn','x',0,'{}',1)") }
        } }
    }

    "an oversized valid city is refused before any new SQL reservation" {
        val atLimit = atPayloadLimit().copy(revision = 0)
        // The revision lost one digit; two extra name bytes put this fresh city one byte over the payload limit.
        val oversized = CivsRules.validate(atLimit.copy(workers = atLimit.workers.map { it.copy(name = it.name + "xx") }))
        Gson().toJson(oversized).toByteArray(Charsets.UTF_8).size.toLong() shouldBe CivsStore.MAX_CITY_BYTES + 1
        spawn.network.closeAsync().await()
        failure { spawn.store.save(oversized).await() }.message.orEmpty() shouldContain "exceeds 256 KiB"
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_city") shouldBe 0
        tableCount(requireNotNull(mysql).endpoint, "arc_civs_member") shouldBe 0
        Files.exists(spawn.root.resolve("cities/${oversized.id}.json")) shouldBe false
    }

    "home-local state commits only the exact held construction receipt and can recover from a lost write acknowledgement" {
        val root = Files.createTempDirectory("civs-sql-buffer")
        try {
            val seed = settlement()
            val old = seed.buildings.single().copy(phase = BuildingPhase.ACTIVE, control = seed.center)
            val target = old.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
            val city = seed.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(old), construction = null, revision = 0)
            val point = seed.center.copy(x = seed.center.x + 1)
            val cell = CivPlanBlock(point, "minecraft:barrel[facing=east,open=false]", "minecraft:air")
            val owner = CivConstructionStorageOwner(city.id, city.worldId, old, target, "e".repeat(64))
            val slots = MutableList<CivResidentItem?>(STORAGE_SLOTS) { null }.also { it[14] = CivResidentItem("COAL", 8) }
            val snapshot = CivConstructionStorageSnapshot(UUID.randomUUID(), owner, cell, slots, UUID.randomUUID())
            val store = CivsStore(root, directExecutor, network = spawn.network)
            store.save(city).await()
            store.savePlan(CivPlacementPlan(snapshot.planId, city.id, city.worldId, target, listOf(cell), owner.schematicSha256)).await()
            val part = store.saveConstructionStorage(snapshot).await()
            val move = CivContainerRelocation(old, listOf(CivRelocatedContainer(point, "BARREL", cell.before, slots,
                List(STORAGE_SLOTS) { null })), buffer = CivConstructionStorageMove(owner, null, part, cell))
            val after = move.after(city)
            val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), containerRelocation = move)
            failure { store.save(after).await() }
            val forged = tx.copy(after = after.copy(name = "Invented before city"))
            store.saveInventoryTransaction(forged).await()
            failure { store.save(forged.after).await() }
            store.deleteInventoryTransaction(forged.id).await()
            store.saveInventoryTransaction(tx).await()
            store.save(after).await()
            val cold = CivsStore(root, directExecutor, network = spawn.network)
            cold.load().await() shouldBe listOf(after)
            cold.loadInventoryTransactions().await() shouldBe listOf(tx)
            cold.save(after).await() shouldBe after
            val blob = root.resolve("construction-buffers/${part.id}.json")
            val bytes = Files.readAllBytes(blob)
            Files.delete(blob)
            failure { cold.load().await() }
            failure { cold.loadInventoryTransactions().await() }
            Files.write(blob, bytes)
            cold.verifyConstructionReceipt(tx).await()
            cold.deleteInventoryTransaction(tx.id).await()
            val free = after.copy(revision = after.revision + 1, inventory = after.inventory!!.copy(constructionBuffers = null))
            failure { cold.save(free).await() }
            cold.load().await() shouldBe listOf(after)
        } finally { root.toFile().deleteRecursively() }
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
                seed.lookup(UUID.randomUUID()).await() shouldBe null
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
    private data class Home(val root: Path, val network: CivsNetworkStore, val store: CivsStore)

    companion object {
        private fun directoryRow(endpoint: MySqlTestEndpoint, id: UUID): Map<String, String?> = endpoint.connect().use { connection ->
            connection.prepareStatement("SELECT * FROM arc_civs_city WHERE city_id = ?").use { statement ->
                statement.setString(1, id.toString())
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    (1..rows.metaData.columnCount).associate { rows.metaData.getColumnName(it) to rows.getString(it) }
                }
            }
        }

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

        /** Valid carried item bytes exercise the old SQL limit without a fake codec or a relaxed domain rule. */
        private fun atPayloadLimit(): Settlement {
            val base = settlement().copy(name = "N".repeat(48), revision = 17)
            val large = CivResidentItem("IRON_AXE", 1, Base64.getEncoder().encodeToString(ByteArray(22_500) { 7 }))
            val workerId = UUID.randomUUID()
            fun city(triples: Int): Settlement {
                val items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { if (it in 6..13) large else null }
                items[14] = CivResidentItem("IRON_AXE", 1, Base64.getEncoder().encodeToString(ByteArray(triples * 3) { 7 }))
                return base.copy(workers = listOf(CivWorker(workerId, "Resident", null, items = items)))
            }
            fun size(city: Settlement) = Gson().toJson(city).toByteArray(Charsets.UTF_8).size.toLong()
            var low = 1
            var high = 7_500
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (size(city(middle)) <= CivsStore.MAX_CITY_BYTES) low = middle else high = middle - 1
            }
            val fitted = city(low)
            val padding = (CivsStore.MAX_CITY_BYTES - size(fitted)).toInt()
            require(padding in 0..3)
            return CivsRules.validate(fitted.copy(workers = fitted.workers.map { it.copy(name = it.name + "x".repeat(padding)) }))
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
