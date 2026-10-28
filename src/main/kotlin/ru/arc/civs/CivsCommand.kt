package ru.arc.civs

import com.google.gson.Gson
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The command is a small router; every mutation is re-authorized by the service. */
class CivsCommand(
    private val host: CivsUiHost,
    private val ui: CivsUi,
    private val text: CivsText,
    private val tasks: LifecycleTaskScope,
    private val debugState: (String, UUID?, UUID?) -> Map<String, Any?> = { _, _, _ -> mapOf("available" to false) },
    private val configureSetting: (UUID, CivSettlementSetting, Boolean) -> java.util.concurrent.CompletableFuture<Unit> = { _, _, _ ->
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("not-authorized")) },
    private val repairStorageRacks: (UUID, UUID) -> java.util.concurrent.CompletableFuture<Map<String, Int>> = { _, _ ->
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("not-authorized")) },
    private val retryConstruction: (UUID) -> java.util.concurrent.CompletableFuture<Unit> = {
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("not-authorized")) },
    private val debugLayout: (UUID, Boolean) -> java.util.concurrent.CompletableFuture<Map<String, Any?>> = { _, _ ->
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("layout-export-unavailable")) },
    private val prepareHotload: () -> Map<String, Any?> = { mapOf("state" to "failed", "failure" to "hotload-unavailable") },
    private val hotloadStatus: () -> Map<String, Any?> = { mapOf("state" to "active") },
    private val debugTerrainAt: (UUID, UUID, CivPosition) -> Map<String, Any?> = { _, _, _ ->
        mapOf("error" to "terrain-capture-unavailable") },
    private val saveTerrainCapture: (() -> Map<String, Any?>) -> CompletableFuture<Path> = {
        CompletableFuture.failedFuture(CivsRejection("terrain-file-unavailable")) },
    private val saveSettlementCapture: (UUID, () -> Settlement) -> CompletableFuture<Path> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("settlement-file-unavailable")) },
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull() == "hotload") {
            val error = when {
                sender !is ConsoleCommandSender || !sender.hasPermission("arccivs.use") || !sender.hasPermission("arccivs.admin") -> "not-authorized"
                args.size != 2 || args[1] !in setOf("prepare", "status") -> "invalid-hotload-arguments"
                else -> null
            }
            val receipt = if (error != null) mapOf("schema" to 1, "state" to "failed", "error" to error)
                else if (args[1] == "prepare") prepareHotload() else hotloadStatus()
            sender.sendMessage(hotloadLine(receipt))
            return true
        }
        if (args.firstOrNull() == "debug-settlement-file") {
            val city = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val error = when {
                sender !is ConsoleCommandSender || !sender.hasPermission("arccivs.use") || !sender.hasPermission("arccivs.admin") -> "not-authorized"
                args.size != 2 -> "invalid-settlement-arguments"
                city == null -> "invalid-city-id"
                hotloadStatus()["state"] != "active" || !host.ready() -> "not-ready"
                else -> null
            }
            if (error != null) sender.sendMessage(settlementFileLine(failure = CivsRejection(error)))
            else runCatching {
                saveSettlementCapture(requireNotNull(city)) {
                    host.settlements().firstOrNull { it.id == city } ?: throw CivsRejection("city-not-found")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { path, failure ->
                sender.sendMessage(settlementFileLine(path, failure))
            }
            return true
        }
        if (args.firstOrNull() in setOf("debug-terrain-at", "debug-terrain-file-at")) {
            val toFile = args.firstOrNull() == "debug-terrain-file-at"
            val city = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val worker = args.getOrNull(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val coordinates = args.drop(3).map { it.toIntOrNull() }
            val error = when {
                sender !is ConsoleCommandSender || !sender.hasPermission("arccivs.use") || !sender.hasPermission("arccivs.admin") -> "not-authorized"
                args.size != 6 -> "invalid-terrain-arguments"
                city == null -> "invalid-city-id"
                worker == null -> "invalid-worker-id"
                coordinates.any { it == null } -> "invalid-terrain-position"
                hotloadStatus()["state"] != "active" -> "not-ready"
                else -> null
            }
            if (toFile) {
                if (error != null) sender.sendMessage(terrainFileLine(failure = CivsRejection(error)))
                else runCatching {
                    saveTerrainCapture { debugTerrainAt(requireNotNull(city), requireNotNull(worker), CivPosition(
                        requireNotNull(coordinates[0]), requireNotNull(coordinates[1]), requireNotNull(coordinates[2]))) }
                }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { path, failure ->
                    CivsDiagnostics.event("terrain_file_capture", "city" to city, "worker" to worker,
                        "center" to CivPosition(requireNotNull(coordinates[0]), requireNotNull(coordinates[1]), requireNotNull(coordinates[2])),
                        "status" to if (failure == null && path != null) "saved" else "failed",
                        "reason" to if (failure == null && path != null) null else
                            (failure?.let { CivsDiagnostics.rejection(it)?.reason } ?: "terrain-file-write-failed"))
                    sender.sendMessage(terrainFileLine(path, failure))
                }
                return true
            }
            val receipt = if (error != null) mapOf("error" to error) else runCatching {
                debugTerrainAt(requireNotNull(city), requireNotNull(worker), CivPosition(
                    requireNotNull(coordinates[0]), requireNotNull(coordinates[1]), requireNotNull(coordinates[2])))
            }.getOrElse { mapOf("error" to (CivsDiagnostics.rejection(it)?.reason ?: "terrain-capture-failed")) }
            sender.sendMessage("ARCCIVS_TERRAIN_CAPTURE " + Gson().toJson(
                mapOf("schema" to 1, "source" to "fresh-native-command", "focus_origin" to "operator-request") + receipt +
                    ("state" to if (receipt["error"] == null) "captured" else "failed")))
            return true
        }
        val layoutExport = args.getOrNull(0) == "debug" && args.getOrNull(1) == "layout"
        if (!sender.hasPermission("arccivs.use")) {
            if (layoutExport) sender.sendMessage(CivsLayoutDebug.line(failure = CivsRejection("not-authorized")))
            else sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
            return true
        }
        if (hotloadStatus()["state"] != "active") {
            sender.sendMessage(text.error(sender as? Player, "reason.not-ready"))
            return true
        }
        if (args.firstOrNull() == "debug") {
            if (layoutExport) {
                if (!sender.hasPermission("arccivs.admin")) sender.sendMessage(CivsLayoutDebug.line(failure = CivsRejection("not-authorized")))
                else CivsLayoutDebug.request(args.drop(2), debugLayout).whenCompleteSync(tasks) { receipt, failure ->
                    sender.sendMessage(CivsLayoutDebug.line(receipt, failure))
                }
            }
            else if (!sender.hasPermission("arccivs.admin")) sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
            else CivsDebug.lines(args.drop(1), debugState).forEach(sender::sendMessage)
            return true
        }
        if (args.firstOrNull() == "retry-construction") {
            if (sender is Player || !sender.hasPermission("arccivs.admin")) {
                sender.sendMessage(text.error(sender as? Player, "reason.not-authorized")); return true
            }
            val city = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (args.size != 2 || city == null) sender.sendMessage("Usage: civs retry-construction <city UUID>")
            else retryConstruction(city).whenCompleteSync(tasks) { _, failure ->
                sender.sendMessage("ARCCIVS_CONSTRUCTION_RETRY " + Gson().toJson(mapOf("city" to city,
                    "committed" to (failure == null), "failure" to failure?.let {
                        CivsDiagnostics.rejection(it)?.reason ?: it.javaClass.simpleName })))
            }
            return true
        }
        if (args.firstOrNull() in setOf("repair-warehouse", "repair-storage-racks")) {
            if (sender is Player || !sender.hasPermission("arccivs.admin")) {
                sender.sendMessage(text.error(sender as? Player, "reason.not-authorized")); return true
            }
            val city = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val building = args.getOrNull(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (args.size != 3 || city == null || building == null) sender.sendMessage("Usage: civs repair-storage-racks <city UUID> <building UUID>")
            else repairStorageRacks(city, building).whenCompleteSync(tasks) { counts, failure ->
                sender.sendMessage("ARCCIVS_STORAGE_REPAIR " + Gson().toJson(mapOf("city" to city, "building" to building,
                    "completed" to (failure == null), "counts" to counts,
                    "failure" to failure?.let { CivsDiagnostics.rejection(it)?.reason ?: it.javaClass.simpleName })))
            }
            return true
        }
        if (args.firstOrNull() == "settings") {
            if (sender is Player || !sender.hasPermission("arccivs.admin")) {
                sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
                return true
            }
            val cityId = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val setting = args.getOrNull(2)?.let { name -> CivSettlementSetting.entries.firstOrNull {
                it.name.lowercase().replace('_', '-') == name
            } }
            val enabled = when (args.getOrNull(3)) { "on" -> true; "off" -> false; else -> null }
            if (args.size != 4 || cityId == null || setting == null || enabled == null) {
                sender.sendMessage("Usage: civs settings <city UUID> <chunk-loading|immortal-residents|auto-construction-materials> <on|off>")
            } else configureSetting(cityId, setting, enabled).whenCompleteSync(tasks) { _, failure ->
                sender.sendMessage("ARCCIVS_SETTING " + Gson().toJson(mapOf("city" to cityId.toString(), "setting" to setting.name,
                    "enabled" to enabled, "committed" to (failure == null), "failure" to failure?.let { CivsDiagnostics.rejection(it)?.reason ?: it.javaClass.simpleName })))
            }
            return true
        }
        if (args.firstOrNull() == "status" && args.size <= 2 && sender.hasPermission("arccivs.admin")) {
            val id = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val city = if (args.size == 2) host.settlements().find { it.id == id } else (sender as? Player)?.let(host::city)
            sender.sendMessage(statusLine(host, city))
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(text.warning(null as Player?, "common.player-only"))
            return true
        }
        if (args.firstOrNull() == "give-resurrection-scroll") {
            if (args.size != 1 || !player.hasPermission("arccivs.admin")) {
                player.sendMessage(text.error(player, "reason.not-authorized"))
            } else {
                val item = CivsResurrectionScroll.create(text, player)
                val free = (0..35).firstOrNull { player.inventory.getItem(it)?.let { stack -> stack.type.isAir || stack.amount <= 0 ||
                    stack.isSimilar(item) && stack.amount < stack.maxStackSize } != false }
                if (free == null) player.sendMessage(text.error(player, "reason.storage-full"))
                else {
                    val existing = player.inventory.getItem(free)
                    player.inventory.setItem(free, if (existing != null && existing.isSimilar(item)) existing.clone().apply { amount++ } else item)
                    player.sendMessage(text.info(player, "resurrection.given"))
                }
            }
            return true
        }
        when {
            args.isEmpty() -> ui.open(player)
            args.firstOrNull() == "autonomous" && player.hasPermission("arccivs.admin") -> {
                if (args.size == 1) ui.openAutonomous(player)
                else if (args.size >= 3) host.beginAutonomousSelection(player, args.drop(2).joinToString(" "), args[1])
                    .whenCompleteSync(tasks) { _, failure ->
                        if (!player.isOnline) return@whenCompleteSync
                        if (failure != null) player.sendMessage(text.failure(player, failure)) else ui.resumePlacement(player)
                    }
                else player.sendMessage(text.info(player, "autonomy.command-help"))
            }
            args.contentEquals(arrayOf("preview")) -> host.movePreview(player).whenCompleteSync(tasks) { _, failure ->
                if (!player.isOnline) return@whenCompleteSync
                if (failure != null) player.sendMessage(text.failure(player, failure)) else ui.resumePlacement(player)
            }
            args.contentEquals(arrayOf("reload")) && player.hasPermission("arccivs.admin") ->
                host.reload(player).whenCompleteSync(tasks) { _, failure ->
                    if (!player.isOnline) return@whenCompleteSync
                    if (failure != null) player.sendMessage(text.failure(player, failure)) else ui.open(player)
                }
            else -> player.sendMessage(text.info(player, "common.command-help"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        completions(sender.hasPermission("arccivs.use"), sender.hasPermission("arccivs.admin"), args,
            sender is ConsoleCommandSender).let { choices ->
            if (hotloadStatus()["state"] == "active" || args.firstOrNull() == "hotload") choices
            else choices.filter { it == "hotload" }
        }

    companion object {
        internal fun completions(use: Boolean, admin: Boolean, args: Array<out String>, console: Boolean = false): List<String> {
            if (!use) return emptyList()
            if (console && admin && args.size == 2 && args[0] == "hotload")
                return listOf("prepare", "status").filter { it.startsWith(args[1], true) }
            if (admin && args.size == 2 && args[0] == "debug")
                return (listOf("all") + CivsDebug.systems + "layout").filter { it.startsWith(args[1], true) }
            if (admin && args.size == 4 && args[0] == "debug" && args[1] == "layout")
                return listOf("terrain").filter { it.startsWith(args[3], true) }
            if (admin && args.size == 2 && args[0] == "autonomous")
                return CivsBlueprints.STYLES.filter { it.startsWith(args[1], true) }
            if (args.size != 1) return emptyList()
            return (listOf("preview") + if (admin) listOf("status", "debug", "reload", "autonomous", "give-resurrection-scroll") +
                (if (console) listOf("hotload", "debug-terrain-at", "debug-terrain-file-at", "debug-settlement-file") else emptyList()) else emptyList()).filter { it.startsWith(args[0], true) }
        }

        private fun settlementFileLine(path: Path? = null, failure: Throwable? = null): String =
            "ARCCIVS_SETTLEMENT_FILE " + Gson().toJson(if (failure == null && path != null)
                mapOf("schema" to 1, "state" to "saved", "path" to path.toString())
            else mapOf("schema" to 1, "state" to "failed", "error" to
                (failure?.let { CivsDiagnostics.rejection(it)?.reason } ?: "settlement-file-write-failed")))

        private fun terrainFileLine(path: Path? = null, failure: Throwable? = null): String =
            "ARCCIVS_TERRAIN_FILE " + Gson().toJson(if (failure == null && path != null)
                mapOf("schema" to 1, "state" to "saved", "path" to path.toString())
            else mapOf("schema" to 1, "state" to "failed", "error" to
                (failure?.let { CivsDiagnostics.rejection(it)?.reason } ?: "terrain-file-write-failed")))

        private fun hotloadLine(receipt: Map<String, Any?>): String = "ARCCIVS_HOTLOAD " + Gson().toJson(receipt)

        internal fun statusLine(host: CivsUiHost, city: Settlement?): String {
            val payload = linkedMapOf<String, Any?>(
                "ready" to host.ready(), "settlements" to host.settlements().size, "activeNpcs" to host.activeNpcCount(),
                "city" to city,
                "workerStatus" to city?.workers?.associate { it.id.toString() to host.workerStatus(it.id) },
            )
            return "ARCCIVS_STATUS " + Gson().toJson(payload)
        }
    }
}
