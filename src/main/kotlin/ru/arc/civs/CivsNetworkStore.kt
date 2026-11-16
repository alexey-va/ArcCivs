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

data class CivsNetworkCity(val server: BackendServerId, val id: UUID, val owner: UUID, val name: String, val updatedAt: Long)

/** A deleted UUID is never reusable; owner history also fences the one-time founding reserve. */
internal data class CivsDeletedCity(
    val cityId: UUID, val owner: UUID, val server: String?, val revision: Long, val deletedAt: Long,
    val autonomous: Boolean = false,
) {
    fun validated(): CivsDeletedCity = apply {
        requireNotNull(cityId); requireNotNull(owner)
        require(revision >= 0 && deletedAt > 0) { "Invalid city deletion record" }
        server?.let { BackendServerId.of(it) }
    }

    fun verify(city: Settlement, home: String?) {
        if (owner != city.owner) throw CivsRejection("owner-required")
        if (autonomous != (city.autonomy != null)) throw CivsRejection("stale-city")
        if (server != home) throw CivsRejection("remote-city", mapOf("server" to server.orEmpty()))
        if (cityId != city.id || revision != city.revision) throw CivsRejection("stale-city")
    }
}

/** SQL owns the small global directory; complete cities and world receipts belong to their immutable home. */
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

    private data class DirectoryCity(
        val summary: CivsNetworkCity, val autonomous: Boolean, val revision: Long,
        val participants: Set<UUID>, val pending: Settlement?,
    ) {
        fun matches(city: Settlement): Boolean = summary.id == city.id && summary.owner == city.owner &&
            summary.name == city.name && autonomous == (city.autonomy != null) && participants == CivsMembership.participants(city)
    }

    /** Tombstones reach the file loader before any old city file can become live again. */
    internal fun loadOwned(
        deleted: List<CivsDeletedCity>,
        loadLocal: (List<CivsDeletedCity>) -> CompletableFuture<List<Settlement>>,
        writeLocal: (Settlement) -> CompletableFuture<Settlement>,
    ): CompletableFuture<List<Settlement>> = admit("load_owned", "deleted_count" to deleted.size) { runtime ->
        runtime.executor.transaction { connection ->
            lockRegistry(connection)
            deleted.forEach { tombstone ->
                tombstone.validated()
                check(tombstone.server == null || tombstone.server == server.value) { "Local deletion has a different home server" }
                val imported = tombstone.copy(server = server.value)
                val existing = findDeleted(connection, imported.cityId)
                if (existing != null) {
                    check(existing.owner == imported.owner && existing.server == imported.server && existing.revision == imported.revision &&
                        existing.autonomous == imported.autonomous) { "Local deletion conflicts with network deletion history" }
                } else {
                    check(findCity(connection, imported.cityId) == null) { "Local deletion conflicts with a live network city" }
                    insertDeleted(connection, imported)
                }
            }
            val cities = connection.prepareStatement("SELECT city_id FROM arc_civs_city WHERE server_id = ? ORDER BY city_id").use { statement ->
                statement.setString(1, server.value)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        check(size < maximumCities) { "Home settlement capacity exceeds maximum-settlements" }
                        add(UUID.fromString(rows.getString(1)))
                    }
                } }
            }.map { requireNotNull(findCity(connection, it)) }
            val retired = listOf(false, true).flatMap { autonomous ->
                connection.prepareStatement("SELECT * FROM ${deletionTable(autonomous)} WHERE server_id = ?").use { statement ->
                    statement.setString(1, server.value)
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) {
                            check(size < CivsStore.MAX_DELETED_CITIES) { "City deletion history exceeds its limit" }
                            add(readDeleted(rows, autonomous))
                        }
                    } }
                }
            }
            check(retired.map { it.cityId }.distinct().size == retired.size) { "City appears in both deletion histories" }
            check(retired.size <= CivsStore.MAX_DELETED_CITIES) { "City deletion history exceeds its limit" }
            check(cities.none { city -> retired.any { it.cityId == city.summary.id } }) { "City is both live and deleted" }
            cities to retired
        }.thenCompose { (directory, retired) ->
            runtime.executor.submit { loadLocal(retired) }.thenCompose { it }.thenCompose { locals ->
                val local = locals.associateBy(Settlement::id)
                check(local.keys.all { id -> directory.any { it.summary.id == id } }) {
                    "Local city is missing from the network directory; refusing automatic re-registration"
                }
                var chain = CompletableFuture.completedFuture(emptyList<Settlement>())
                directory.forEach { record ->
                    chain = chain.thenCompose { result ->
                        val saved = local[record.summary.id]
                        val accepted = record.pending
                        if (accepted == null) {
                            check(saved != null) { "Acknowledged local city ${record.summary.id} is missing" }
                            check(record.matches(saved) && saved.revision >= record.revision) {
                                "Local city ${record.summary.id} disagrees with its network directory"
                            }
                            CompletableFuture.completedFuture(result + saved)
                        } else {
                            check(saved == null || saved.revision < accepted.revision || saved == accepted) {
                                "Pending directory state would overwrite acknowledged local city ${record.summary.id}"
                            }
                            runtime.executor.submit { writeLocal(accepted) }.thenCompose { it }.thenCompose { written ->
                                check(written == accepted) { "Local city acknowledgement differs from its accepted directory state" }
                                finishPending(runtime, record, accepted).thenApply { result + written }
                            }
                        }
                    }
                }
                chain
            }
        }
    }

    fun lookup(playerId: UUID): CompletableFuture<CivsNetworkCity?> = admit("lookup", "player" to playerId) { runtime ->
        runtime.executor.read { connection ->
            connection.prepareStatement("""SELECT c.city_id, c.server_id, c.owner_id, c.city_name, c.autonomous, c.updated_at_ms
                FROM arc_civs_city c JOIN arc_civs_member m ON m.city_id = c.city_id WHERE m.player_id = ?""").use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { rows -> if (rows.next()) {
                    check(!rows.getBoolean("autonomous")) { "Autonomous village has personal membership" }
                    readSummary(rows)
                } else null }
            }
        }
    }

    /** One admitted chain includes the local ACK and final SQL clear, including during shutdown. */
    internal fun save(city: Settlement, previous: Settlement?,
        writeLocal: (Settlement) -> CompletableFuture<Settlement>): CompletableFuture<Settlement> =
        admit("directory_save", "city" to city.id, "revision" to city.revision) { runtime ->
            runtime.executor.transaction { connection ->
                lockRegistry(connection)
                reserve(connection, city, previous)
            }.thenCompose { record ->
                val accepted = requireNotNull(record.pending)
                runtime.executor.submit { writeLocal(accepted) }.thenCompose { it }.thenCompose { written ->
                    check(written == accepted) { "Local city acknowledgement differs from its directory reservation" }
                    finishPending(runtime, record, accepted).thenApply { written }
                }
            }
        }

    private fun reserve(connection: Connection, proposed: Settlement, previous: Settlement?): DirectoryCity {
        CivsRules.validate(proposed)
        if (findDeleted(connection, proposed.id) != null) throw CivsRejection("city-deleted")
        val current = findCity(connection, proposed.id)
        if (current != null) {
            check(current.summary.server == server) { "Only the home server may change settlement ${proposed.id}" }
            check(current.summary.owner == proposed.owner) { "City owner cannot change" }
            check(current.autonomous == (proposed.autonomy != null)) { "City autonomy cannot change" }
            current.pending?.let { accepted ->
                check(accepted == proposed || proposed.revision == 0L && previous == null &&
                    proposed.copy(stock = Stock(), inventory = proposed.inventory?.copy(materials = emptyMap())) == accepted) {
                    "Directory retry differs from its accepted state"
                }
                return current
            }
            if (previous == proposed && current.matches(proposed) && proposed.revision >= current.revision)
                return current.copy(pending = proposed)
            check(previous != null && current.matches(previous) && previous.revision >= current.revision &&
                previous.revision < Long.MAX_VALUE && proposed.revision == previous.revision + 1) {
                "Local before-city disagrees with the directory or revision"
            }
        } else {
            check(previous == null && proposed.revision == 0L) { "New settlement revision must start at zero" }
            val count = connection.prepareStatement("SELECT COUNT(*) FROM arc_civs_city WHERE server_id = ?").use { statement ->
                statement.setString(1, server.value)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (count >= maximumCities) throw CivsRejection("settlement-limit")
        }
        val city = if (current == null && proposed.autonomy == null && hasDeletedOwner(connection, proposed.owner))
            proposed.copy(stock = Stock(), inventory = proposed.inventory?.copy(materials = emptyMap())) else proposed
        val json = gson.toJson(city)
        require(json.toByteArray(Charsets.UTF_8).size <= CivsStore.MAX_CITY_BYTES) { "City snapshot exceeds 256 KiB" }
        val participants = CivsMembership.participants(city).sortedBy(UUID::toString)
        connection.prepareStatement("SELECT city_id FROM arc_civs_member WHERE player_id = ?").use { statement ->
            participants.forEach { player ->
                statement.setString(1, player.toString())
                statement.executeQuery().use { rows ->
                    if (rows.next() && rows.getString(1) != city.id.toString()) throw CivsRejection("already-member")
                }
            }
        }
        val at = System.currentTimeMillis()
        if (current == null) {
            connection.prepareStatement("""INSERT INTO arc_civs_city
                (city_id, server_id, owner_id, revision, pending_snapshot, updated_at_ms, city_name, autonomous)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
                statement.setString(1, city.id.toString()); statement.setString(2, server.value)
                statement.setString(3, city.owner.toString()); statement.setLong(4, city.revision)
                statement.setString(5, json); statement.setLong(6, at); statement.setString(7, city.name)
                statement.setBoolean(8, city.autonomy != null)
                check(statement.executeUpdate() == 1)
            }
        } else {
            connection.prepareStatement("""UPDATE arc_civs_city SET revision = ?, pending_snapshot = ?, updated_at_ms = ?, city_name = ?
                WHERE city_id = ? AND server_id = ? AND revision = ? AND pending_snapshot IS NULL""").use { statement ->
                statement.setLong(1, city.revision); statement.setString(2, json); statement.setLong(3, at)
                statement.setString(4, city.name); statement.setString(5, city.id.toString()); statement.setString(6, server.value)
                statement.setLong(7, current.revision)
                check(statement.executeUpdate() == 1) { "Home/directory revision fence rejected settlement update" }
            }
        }
        if (current?.participants != participants.toSet()) {
            connection.prepareStatement("DELETE FROM arc_civs_member WHERE city_id = ?").use {
                it.setString(1, city.id.toString()); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO arc_civs_member(player_id, city_id) VALUES (?, ?)").use { statement ->
                participants.forEach { player ->
                    statement.setString(1, player.toString()); statement.setString(2, city.id.toString()); statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        return DirectoryCity(CivsNetworkCity(server, city.id, city.owner, city.name, at), city.autonomy != null,
            city.revision, participants.toSet(), city)
    }

    private fun finishPending(runtime: SqlRuntime, expected: DirectoryCity, city: Settlement): CompletableFuture<Unit> =
        runtime.executor.transaction { connection ->
            lockRegistry(connection)
            val current = requireNotNull(findCity(connection, city.id)) { "Accepted directory city disappeared" }
            check(current.summary.server == server && current.revision == expected.revision && current.matches(city)) {
                "Accepted directory identity changed before local acknowledgement"
            }
            if (current.pending == null) return@transaction Unit
            check(current.pending == city) { "Accepted directory snapshot changed before local acknowledgement" }
            connection.prepareStatement("UPDATE arc_civs_city SET pending_snapshot = NULL WHERE city_id = ? AND server_id = ? AND revision = ?").use {
                it.setString(1, city.id.toString()); it.setString(2, server.value); it.setLong(3, city.revision)
                check(it.executeUpdate() == 1)
            }
            Unit
        }

    /** Complete local equality is checked by CivsStore before this rare global directory deletion. */
    internal fun delete(city: Settlement, deleteLocal: () -> CompletableFuture<Unit>): CompletableFuture<Unit> =
        admit("delete", "city" to city.id, "revision" to city.revision, "owner" to city.owner) { runtime ->
            runtime.executor.transaction { connection ->
                CivsRules.validate(city)
                require(city.inventory?.constructionBuffers.isNullOrEmpty()) { "Construction storage must be returned before deletion" }
                lockRegistry(connection)
                val deleted = findDeleted(connection, city.id)
                if (deleted != null) {
                    deleted.verify(city, server.value)
                    return@transaction Unit
                }
                val existing = findCity(connection, city.id) ?: throw CivsRejection("stale-city")
                if (existing.summary.owner != city.owner) throw CivsRejection("owner-required")
                if (existing.summary.server != server) throw CivsRejection("remote-city", mapOf("server" to existing.summary.server.value))
                check(existing.pending == null) { "Directory acknowledgement is pending" }
                if (!existing.matches(city) || existing.revision > city.revision) throw CivsRejection("stale-city")
                connection.prepareStatement("DELETE FROM arc_civs_member WHERE city_id = ?").use {
                    it.setString(1, city.id.toString()); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM arc_civs_city WHERE city_id = ? AND server_id = ? AND revision = ?").use {
                    it.setString(1, city.id.toString()); it.setString(2, server.value); it.setLong(3, existing.revision)
                    check(it.executeUpdate() == 1) { "Home/directory fence rejected settlement deletion" }
                }
                insertDeleted(connection, CivsDeletedCity(city.id, city.owner, server.value, city.revision,
                    System.currentTimeMillis(), city.autonomy != null))
                Unit
            }.thenCompose { runtime.executor.submit { deleteLocal() }.thenCompose { it } }
        }

    private fun hasDeletedOwner(connection: Connection, owner: UUID): Boolean =
        connection.prepareStatement("SELECT 1 FROM arc_civs_deleted WHERE owner_id = ? LIMIT 1").use {
            it.setString(1, owner.toString())
            it.executeQuery().use(ResultSet::next)
        }

    private fun findDeleted(connection: Connection, id: UUID): CivsDeletedCity? {
        val matches = listOf(false, true).mapNotNull { autonomous ->
            connection.prepareStatement("SELECT * FROM ${deletionTable(autonomous)} WHERE city_id = ?").use { statement ->
                statement.setString(1, id.toString())
                statement.executeQuery().use { rows -> if (rows.next()) CivsDeletedCity(
                    UUID.fromString(rows.getString("city_id")), UUID.fromString(rows.getString("owner_id")),
                    BackendServerId.of(rows.getString("server_id")).value, rows.getLong("revision"), rows.getLong("deleted_at_ms"),
                    autonomous,
                ).validated() else null }
            }
        }
        check(matches.size <= 1) { "City $id appears in both deletion histories" }
        return matches.singleOrNull()
    }

    private fun deletionTable(autonomous: Boolean): String =
        if (autonomous) "arc_civs_autonomous_deleted" else "arc_civs_deleted"

    private fun insertDeleted(connection: Connection, deleted: CivsDeletedCity) {
        deleted.validated()
        connection.prepareStatement("INSERT INTO ${deletionTable(deleted.autonomous)}(city_id, owner_id, server_id, revision, deleted_at_ms) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, deleted.cityId.toString()); it.setString(2, deleted.owner.toString()); it.setString(3, requireNotNull(deleted.server))
            it.setLong(4, deleted.revision); it.setLong(5, deleted.deletedAt)
            check(it.executeUpdate() == 1)
        }
    }

    private fun readDeleted(rows: ResultSet, autonomous: Boolean): CivsDeletedCity = CivsDeletedCity(
        UUID.fromString(rows.getString("city_id")), UUID.fromString(rows.getString("owner_id")),
        BackendServerId.of(rows.getString("server_id")).value, rows.getLong("revision"), rows.getLong("deleted_at_ms"), autonomous,
    ).validated()

    private fun findCity(connection: Connection, id: UUID): DirectoryCity? {
        val row = connection.prepareStatement("SELECT * FROM arc_civs_city WHERE city_id = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use { rows -> if (rows.next()) {
                val json = rows.getString("pending_snapshot")
                val accepted = json?.let {
                    check(it.toByteArray(Charsets.UTF_8).size <= CivsStore.MAX_CITY_BYTES) { "Pending city exceeds 256 KiB" }
                    CivsRules.validate(requireNotNull(gson.fromJson(it, Settlement::class.java)))
                }
                DirectoryCity(readSummary(rows), rows.getBoolean("autonomous"), rows.getLong("revision"), emptySet(), accepted)
            } else null }
        } ?: return null
        val participants = connection.prepareStatement("SELECT player_id FROM arc_civs_member WHERE city_id = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(UUID.fromString(rows.getString(1))) } }
        }
        return row.copy(participants = participants).also { city ->
            check(city.revision >= 0 && if (city.autonomous) participants.isEmpty() else city.summary.owner in participants) {
                "Network directory membership is invalid"
            }
            city.pending?.let { check(city.matches(it) && city.revision == it.revision) { "Pending city and directory disagree" } }
        }
    }

    private fun readSummary(rows: ResultSet): CivsNetworkCity = CivsNetworkCity(
        BackendServerId.of(rows.getString("server_id")), UUID.fromString(rows.getString("city_id")),
        UUID.fromString(rows.getString("owner_id")), rows.getString("city_name"), rows.getLong("updated_at_ms"),
    ).also { require(it.name.isNotBlank() && it.name.length <= 48 && it.name.none(Char::isISOControl)) { "Invalid directory city name" } }

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
            val committed = value as? Settlement
            CivsDiagnostics.event("sql_end", "operation" to operation, "request" to request, "server" to server.value, *fields,
                "outcome" to if (failure == null) if (operation == "directory_save" || operation == "delete") "committed" else "read" else if (CivsDiagnostics.rejection(failure) != null) "rejected" else "failed",
                "owner_server" to if (value is CivsNetworkCity) value.server.value else server.value,
                "result_city" to committed?.id, "result_revision" to committed?.revision,
                "participants" to committed?.let { CivsMembership.participants(it).size },
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
                sql.executor.submit { MySqlMigrator(sql.dataSource, "arc_civs").migrate(listOf(MIGRATION, DELETION_MIGRATION, AUTONOMY_MIGRATION, LOCAL_CITY_MIGRATION)); sql }
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

        private val AUTONOMY_MIGRATION = SqlMigration(3, "Separate autonomous village founding history", listOf(
            """CREATE TABLE IF NOT EXISTS arc_civs_autonomous_deleted (
                city_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                server_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                revision BIGINT NOT NULL, deleted_at_ms BIGINT NOT NULL,
                INDEX arc_civs_autonomous_deleted_owner(owner_id)
            ) ENGINE=InnoDB""".trimIndent(),
        ))

        // MySQL DDL commits independently. Each conditional statement survives a partially applied migration.
        private fun addColumnIfMissing(column: String, definition: String): List<String> = conditionalDdl(column,
            "ALTER TABLE arc_civs_city ADD COLUMN $column $definition")

        private fun conditionalDdl(column: String, sql: String): List<String> = listOf(
            "SET @arc_civs_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'arc_civs_city' AND COLUMN_NAME = '$column'), 'DO 0', '$sql')",
            "PREPARE arc_civs_local_migration FROM @arc_civs_ddl",
            "EXECUTE arc_civs_local_migration",
            "DEALLOCATE PREPARE arc_civs_local_migration",
        )

        private val LOCAL_CITY_MIGRATION = SqlMigration(4, "Home-local cities and minimal network directory",
            conditionalDdl("pending_snapshot", "ALTER TABLE arc_civs_city CHANGE COLUMN snapshot pending_snapshot MEDIUMTEXT NULL") +
                addColumnIfMissing("city_name", "VARCHAR(48) NULL") +
                addColumnIfMissing("autonomous", "BOOLEAN NULL") + listOf(
                    """UPDATE arc_civs_city SET city_name = JSON_UNQUOTE(JSON_EXTRACT(pending_snapshot, '$.name')),
                        autonomous = COALESCE(JSON_TYPE(JSON_EXTRACT(pending_snapshot, '$.autonomy')) = 'OBJECT', FALSE)
                        WHERE city_name IS NULL OR autonomous IS NULL""".trimIndent(),
                    "ALTER TABLE arc_civs_city MODIFY city_name VARCHAR(48) NOT NULL, MODIFY autonomous BOOLEAN NOT NULL",
                ))
    }
}
