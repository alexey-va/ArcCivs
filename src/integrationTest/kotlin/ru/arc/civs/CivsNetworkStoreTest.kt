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
                16, "nordic", hall, (0..3).map { CivWorker(UUID.randomUUID(), "Worker$it", null) },
                UUID.randomUUID(), 1_000,
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
