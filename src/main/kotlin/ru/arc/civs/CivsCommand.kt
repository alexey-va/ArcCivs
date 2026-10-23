package ru.arc.civs

import com.google.gson.Gson
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID

/** The command is a small router; every mutation is re-authorized by the service. */
class CivsCommand(
    private val host: CivsUiHost,
    private val ui: CivsUi,
    private val text: CivsText,
    private val tasks: LifecycleTaskScope,
    private val debugState: (String, UUID?, UUID?) -> Map<String, Any?> = { _, _, _ -> mapOf("available" to false) },
    private val configureSetting: (UUID, CivSettlementSetting, Boolean) -> java.util.concurrent.CompletableFuture<Unit> = { _, _, _ ->
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("not-authorized")) },
    private val repairWarehouse: (UUID, UUID) -> java.util.concurrent.CompletableFuture<Map<String, Int>> = { _, _ ->
        java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("not-authorized")) },
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("arccivs.use")) {
            sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
            return true
        }
        if (args.firstOrNull() == "debug") {
            if (!sender.hasPermission("arccivs.admin")) sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
            else CivsDebug.lines(args.drop(1), debugState).forEach(sender::sendMessage)
            return true
        }
        if (args.firstOrNull() == "repair-warehouse") {
            if (sender is Player || !sender.hasPermission("arccivs.admin")) {
                sender.sendMessage(text.error(sender as? Player, "reason.not-authorized")); return true
            }
            val city = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val building = args.getOrNull(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (args.size != 3 || city == null || building == null) sender.sendMessage("Usage: civs repair-warehouse <city UUID> <building UUID>")
            else repairWarehouse(city, building).whenCompleteSync(tasks) { counts, failure ->
                sender.sendMessage("ARCCIVS_WAREHOUSE_REPAIR " + Gson().toJson(mapOf("city" to city, "building" to building,
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
        completions(sender.hasPermission("arccivs.use"), sender.hasPermission("arccivs.admin"), args)

    companion object {
        internal fun completions(use: Boolean, admin: Boolean, args: Array<out String>): List<String> {
            if (!use) return emptyList()
            if (admin && args.size == 2 && args[0] == "debug")
                return (listOf("all") + CivsDebug.systems).filter { it.startsWith(args[1], true) }
            if (admin && args.size == 2 && args[0] == "autonomous")
                return CivsBlueprints.STYLES.filter { it.startsWith(args[1], true) }
            if (args.size != 1) return emptyList()
            return (listOf("preview") + if (admin) listOf("status", "debug", "reload", "autonomous") else emptyList()).filter { it.startsWith(args[0], true) }
        }

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
