package ru.arc.civs

import com.google.gson.Gson
import ru.arc.network.BackendServerId
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlModuleConfig
import ru.arc.sql.SqlRuntime
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

data class CivsNetworkCity(val server: BackendServerId, val city: Settlement, val updatedAt: Long)

/** A deleted UUID is never reusable; owner history also fences the one-time founding reserve. */
internal data class CivsDeletedCity(
    val cityId: UUID, val owner: UUID, val server: String?, val revision: Long, val deletedAt: Long,
) {
    fun validated(): CivsDeletedCity = apply {
        requireNotNull(cityId); requireNotNull(owner)
        require(revision >= 0 && deletedAt > 0) { "Invalid city deletion record" }
        server?.let { BackendServerId.of(it) }
    }

    fun verify(city: Settlement, home: String?) {
        if (owner != city.owner) throw CivsRejection("owner-required")
        if (server != home) throw CivsRejection("remote-city", mapOf("server" to server.orEmpty()))
        if (cityId != city.id || revision != city.revision) throw CivsRejection("stale-city")
    }
}

/** SQL is the sole city authority; placement plans stay on the immutable home server. */
class CivsNetworkStore internal constructor(
    val server: BackendServerId,
    private val maximumCities: Int,
    private val initialized: CompletableFuture<SqlRuntime>,
) {
    private val gson = Gson()
    private val lifecycle = Any()
    private val pending = mutableSetOf<CompletableFuture<*>>()
    private var closed = false
    private var drain: CompletableFuture<Unit>? = null

    /** Imports old JSON without changing the originals; existing canonical SQL always wins. */
    fun loadOwned(legacy: List<Settlement>): CompletableFuture<List<Settlement>> = loadOwned(legacy, emptyList())

    internal fun loadOwned(legacy: List<Settlement>, deleted: List<CivsDeletedCity>): CompletableFuture<List<Settlement>> =
        admit("load_owned", "legacy_count" to legacy.size, "deleted_count" to deleted.size) { runtime ->
        runtime.executor.transaction { connection ->
            lockRegistry(connection)
            deleted.forEach { tombstone ->
                tombstone.validated()
                check(tombstone.server == null || tombstone.server == server.value) { "Legacy deletion has a different home server" }
                val imported = tombstone.copy(server = server.value)
                val existing = findDeleted(connection, imported.cityId)
                if (existing != null) {
                    check(existing.owner == imported.owner && existing.server == imported.server && existing.revision == imported.revision) {
                        "Legacy deletion conflicts with canonical deletion history"
                    }
                } else {
                    check(findCity(connection, imported.cityId) == null) { "Legacy deletion conflicts with a canonical live city" }
                    insertDeleted(connection, imported)
                }
            }
            legacy.forEach { city ->
                CivsRules.validate(city)
                if (findDeleted(connection, city.id) != null) return@forEach
                val existing = findCity(connection, city.id)
                if (existing == null) save(connection, city, importing = true)
                else check(existing.server == server && existing.city.owner == city.owner) {
                    "Legacy city ${city.id} conflicts with the network home or owner; originals were preserved"
                }
            }
            connection.prepareStatement("SELECT * FROM arc_civs_city WHERE server_id = ? ORDER BY city_id").use { statement ->
                statement.setString(1, server.value)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        check(size < maximumCities) { "Home settlement capacity exceeds maximum-settlements" }
                        add(readCity(rows).city)
                    }
                } }
            }
        }
    }

    fun lookup(playerId: UUID): CompletableFuture<CivsNetworkCity?> = admit("lookup", "player" to playerId) { runtime ->
        runtime.executor.read { connection ->
            connection.prepareStatement("SELECT c.* FROM arc_civs_city c JOIN arc_civs_member m ON m.city_id = c.city_id WHERE m.player_id = ?").use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { rows -> if (rows.next()) readCity(rows).also {
                    check(it.city.canManage(playerId)) { "Network membership and snapshot disagree" }
                } else null }
            }
        }
    }

    fun save(city: Settlement): CompletableFuture<Settlement> {
        val snapshot = snapshotCity(city)
        return admit("save", "city" to snapshot.id, "revision" to snapshot.revision,
            "owner" to snapshot.owner, "members" to snapshot.members.size, "construction" to snapshot.construction?.id) { runtime ->
            runtime.executor.transaction { connection ->
                lockRegistry(connection)
                save(connection, snapshot, importing = false)
            }
        }
    }

    /** Deletes only the complete expected home snapshot; retries retain the durable deletion fence. */
    fun delete(city: Settlement): CompletableFuture<Unit> {
        val snapshot = snapshotCity(city)
        return admit("delete", "city" to snapshot.id, "revision" to snapshot.revision, "owner" to snapshot.owner) { runtime ->
            runtime.executor.transaction { connection ->
                CivsRules.validate(snapshot)
                lockRegistry(connection)
                val deleted = findDeleted(connection, snapshot.id)
                if (deleted != null) {
                    deleted.verify(snapshot, server.value)
                    return@transaction Unit
                }
                val existing = findCity(connection, snapshot.id) ?: throw CivsRejection("stale-city")
                if (existing.city.owner != snapshot.owner) throw CivsRejection("owner-required")
                if (existing.server != server) throw CivsRejection("remote-city", mapOf("server" to existing.server.value))
                if (existing.city != snapshot) throw CivsRejection("stale-city")
                connection.prepareStatement("DELETE FROM arc_civs_member WHERE city_id = ?").use {
                    it.setString(1, snapshot.id.toString()); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM arc_civs_city WHERE city_id = ? AND server_id = ? AND owner_id = ? AND revision = ?").use {
                    it.setString(1, snapshot.id.toString()); it.setString(2, server.value)
                    it.setString(3, snapshot.owner.toString()); it.setLong(4, snapshot.revision)
                    check(it.executeUpdate() == 1) { "Home/revision fence rejected settlement deletion" }
                }
                insertDeleted(connection, CivsDeletedCity(snapshot.id, snapshot.owner, server.value, snapshot.revision, System.currentTimeMillis()))
                Unit
            }
        }
    }

    private fun save(connection: Connection, proposed: Settlement, importing: Boolean): Settlement {
        CivsRules.validate(proposed)
        if (findDeleted(connection, proposed.id) != null) throw CivsRejection("city-deleted")
        val previous = findCity(connection, proposed.id)
        if (previous != null) {
            check(previous.server == server) { "Only the home server may change settlement ${proposed.id}" }
            check(previous.city.owner == proposed.owner) { "City owner cannot change" }
            if (previous.city == proposed) return previous.city
        }
        val city = if (!importing && proposed.revision == 0L && hasDeletedOwner(connection, proposed.owner))
            proposed.copy(stock = Stock(), inventory = proposed.inventory?.copy(materials = emptyMap())) else proposed
        val json = gson.toJson(city)
        require(json.toByteArray(Charsets.UTF_8).size <= CivsStore.MAX_CITY_BYTES) { "City snapshot exceeds 256 KiB" }
        if (previous != null) {
            if (previous.city.revision == city.revision) {
                check(previous.city == city) { "Same-revision retry differs from the committed snapshot" }
                return previous.city
            }
            check(previous.city.revision < Long.MAX_VALUE && city.revision == previous.city.revision + 1) {
                "Settlement revision is stale or skips a committed revision"
            }
        } else {
            check(importing || city.revision == 0L) { "New settlement revision must start at zero" }
            val count = connection.prepareStatement("SELECT COUNT(*) FROM arc_civs_city WHERE server_id = ?").use { statement ->
                statement.setString(1, server.value)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (count >= maximumCities) throw CivsRejection("settlement-limit")
        }
        val participants = (city.members + city.owner).sortedBy(UUID::toString)
        connection.prepareStatement("SELECT city_id FROM arc_civs_member WHERE player_id = ?").use { statement ->
            participants.forEach { player ->
                statement.setString(1, player.toString())
                statement.executeQuery().use { rows ->
                    if (rows.next() && rows.getString(1) != city.id.toString()) throw CivsRejection("already-member")
                }
            }
        }
        val at = System.currentTimeMillis()
        if (previous == null) {
            connection.prepareStatement("INSERT INTO arc_civs_city(city_id, server_id, owner_id, revision, snapshot, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
                statement.setString(1, city.id.toString()); statement.setString(2, server.value)
                statement.setString(3, city.owner.toString()); statement.setLong(4, city.revision)
                statement.setString(5, json); statement.setLong(6, at)
                check(statement.executeUpdate() == 1)
            }
        } else {
            connection.prepareStatement("UPDATE arc_civs_city SET revision = ?, snapshot = ?, updated_at_ms = ? WHERE city_id = ? AND server_id = ? AND revision = ?").use { statement ->
                statement.setLong(1, city.revision); statement.setString(2, json); statement.setLong(3, at)
                statement.setString(4, city.id.toString()); statement.setString(5, server.value)
                statement.setLong(6, previous.city.revision)
                check(statement.executeUpdate() == 1) { "Home/revision fence rejected settlement update" }
            }
        }
        connection.prepareStatement("DELETE FROM arc_civs_member WHERE city_id = ?").use {
            it.setString(1, city.id.toString()); it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO arc_civs_member(player_id, city_id) VALUES (?, ?)").use { statement ->
            participants.forEach { player ->
                statement.setString(1, player.toString()); statement.setString(2, city.id.toString()); statement.addBatch()
            }
            statement.executeBatch()
        }
        return city
    }

    private fun hasDeletedOwner(connection: Connection, owner: UUID): Boolean =
        connection.prepareStatement("SELECT 1 FROM arc_civs_deleted WHERE owner_id = ? LIMIT 1").use {
            it.setString(1, owner.toString())
            it.executeQuery().use(ResultSet::next)
        }

    private fun findDeleted(connection: Connection, id: UUID): CivsDeletedCity? =
        connection.prepareStatement("SELECT * FROM arc_civs_deleted WHERE city_id = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use { rows -> if (rows.next()) CivsDeletedCity(
                UUID.fromString(rows.getString("city_id")), UUID.fromString(rows.getString("owner_id")),
                BackendServerId.of(rows.getString("server_id")).value, rows.getLong("revision"), rows.getLong("deleted_at_ms"),
            ).validated() else null }
        }

    private fun insertDeleted(connection: Connection, deleted: CivsDeletedCity) {
        deleted.validated()
        connection.prepareStatement("INSERT INTO arc_civs_deleted(city_id, owner_id, server_id, revision, deleted_at_ms) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, deleted.cityId.toString()); it.setString(2, deleted.owner.toString()); it.setString(3, requireNotNull(deleted.server))
            it.setLong(4, deleted.revision); it.setLong(5, deleted.deletedAt)
            check(it.executeUpdate() == 1)
        }
    }

    private fun snapshotCity(city: Settlement): Settlement = city.copy(
        buildings = city.buildings.toList(), workers = city.workers.toList(), members = city.members.toSet(),
        inventory = city.inventory?.let { it.copy(materials = it.materials.toMap(), containers = it.containers.toList(),
            buildingMaterials = it.buildingMaterials?.mapValues { entry -> entry.value.toMap() }) },
        construction = city.construction?.let { it.copy(materials = it.materials?.toMap(),
            payment = it.payment?.let { payment -> payment.copy(awaiting = payment.awaiting?.toMap()) }) },
        pendingControls = city.pendingControls?.map { it.copy(approach = it.approach?.toList()) },
        constructionQueue = city.constructionQueue?.toList(),
    )

    private fun findCity(connection: Connection, id: UUID): CivsNetworkCity? =
        connection.prepareStatement("SELECT * FROM arc_civs_city WHERE city_id = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use { if (it.next()) readCity(it) else null }
        }

    private fun readCity(rows: ResultSet): CivsNetworkCity {
        val json = rows.getString("snapshot")
        check(json.toByteArray(Charsets.UTF_8).size <= CivsStore.MAX_CITY_BYTES) { "Network city snapshot exceeds 256 KiB" }
        val city = requireNotNull(gson.fromJson(json, Settlement::class.java))
        CivsRules.validate(city)
        check(city.id.toString() == rows.getString("city_id") && city.owner.toString() == rows.getString("owner_id") &&
            city.revision == rows.getLong("revision")) { "Network city index and snapshot disagree" }
        return CivsNetworkCity(BackendServerId.of(rows.getString("server_id")), city, rows.getLong("updated_at_ms"))
    }

    private fun lockRegistry(connection: Connection) {
        // ponytail: one short transaction lock covers cross-city membership; shard only if contention is measured.
        connection.prepareStatement("SELECT id FROM arc_civs_registry WHERE id = 1 FOR UPDATE").use { statement ->
            statement.executeQuery().use { check(it.next()) { "Network registry lock is missing" } }
        }
    }

    private fun <T> admit(operation: String, vararg fields: Pair<String, Any?>,
        action: (SqlRuntime) -> CompletableFuture<T>): CompletableFuture<T> = synchronized(lifecycle) {
        if (closed) return@synchronized CompletableFuture.failedFuture(IllegalStateException("CivsNetworkStore is closed"))
        val request = UUID.randomUUID()
        val started = System.nanoTime()
        CivsDiagnostics.event("sql_start", "operation" to operation, "request" to request, "server" to server.value, *fields)
        val future = initialized.thenCompose(action)
        pending += future
        future.whenComplete { value, failure ->
            synchronized(lifecycle) { pending -= future }
            val committed = when (value) { is Settlement -> value; is CivsNetworkCity -> value.city; else -> null }
            CivsDiagnostics.event("sql_end", "operation" to operation, "request" to request, "server" to server.value, *fields,
                "outcome" to if (failure == null) if (operation == "save" || operation == "delete") "committed" else "read" else if (CivsDiagnostics.rejection(failure) != null) "rejected" else "failed",
                "owner_server" to if (value is CivsNetworkCity) value.server.value else server.value,
                "result_city" to committed?.id, "result_revision" to committed?.revision,
                "participants" to committed?.let { it.members.size + 1 },
                "wood" to committed?.stock?.wood, "stone" to committed?.stock?.stone, "food" to committed?.stock?.food,
                "home_rows" to (value as? List<*>)?.size,
                "reason" to CivsDiagnostics.rejection(failure)?.reason, "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName,
                "duration_ms" to (System.nanoTime() - started) / 1_000_000)
        }
        future
    }

    /** Seal admissions, drain all accepted chains, then release the Core-owned SQL lease. */
    fun closeAsync(): CompletableFuture<Unit> = synchronized(lifecycle) {
        drain?.let { return@synchronized it }
        closed = true
        CompletableFuture.allOf(*pending.map { it.handle { _, _ -> Unit } }.toTypedArray())
            .thenCompose { initialized.handle { runtime, _ -> runtime }.thenCompose { runtime ->
                if (runtime == null) CompletableFuture.completedFuture(Unit)
                else runtime.executor.closeAsync().whenComplete { _, _ -> runtime.close() }
            } }.also { drain = it }
    }

    companion object {
        fun open(root: Path, server: BackendServerId, maximumCities: Int, executor: Executor): CivsNetworkStore =
            create(server, maximumCities, CompletableFuture.supplyAsync({
                SqlRuntime.create(SqlModuleConfig.load(root).connection(), "arc-civs")
            }, executor))

        internal fun create(server: BackendServerId, maximumCities: Int, runtime: CompletableFuture<SqlRuntime>): CivsNetworkStore {
            require(maximumCities > 0)
            val initialized = runtime.thenCompose { sql ->
                sql.executor.submit { MySqlMigrator(sql.dataSource, "arc_civs").migrate(listOf(MIGRATION, DELETION_MIGRATION)); sql }
                    .handle { result, failure ->
                        if (failure == null) CompletableFuture.completedFuture(result)
                        else sql.executor.closeAsync().thenCompose {
                            sql.close()
                            CompletableFuture.failedFuture<SqlRuntime>(failure)
                        }
                    }.thenCompose { it }
            }
            return CivsNetworkStore(server, maximumCities, initialized)
        }

        private val MIGRATION = SqlMigration(1, "Network settlements and unique membership", listOf(
            "CREATE TABLE IF NOT EXISTS arc_civs_registry (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB",
            "INSERT IGNORE INTO arc_civs_registry(id) VALUES (1)",
            """CREATE TABLE IF NOT EXISTS arc_civs_city (
                city_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                server_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                revision BIGINT NOT NULL, snapshot MEDIUMTEXT NOT NULL, updated_at_ms BIGINT NOT NULL,
                INDEX arc_civs_home(server_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin""".trimIndent(),
            """CREATE TABLE IF NOT EXISTS arc_civs_member (
                player_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                city_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                CONSTRAINT arc_civs_member_city FOREIGN KEY (city_id) REFERENCES arc_civs_city(city_id)
            ) ENGINE=InnoDB""".trimIndent(),
        ))

        private val DELETION_MIGRATION = SqlMigration(2, "Deleted settlement identities and founding grant history", listOf(
            """CREATE TABLE IF NOT EXISTS arc_civs_deleted (
                city_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                server_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                revision BIGINT NOT NULL, deleted_at_ms BIGINT NOT NULL,
                INDEX arc_civs_deleted_owner(owner_id)
            ) ENGINE=InnoDB""".trimIndent(),
        ))
    }
}
