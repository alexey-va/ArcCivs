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
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("arccivs.use")) {
            sender.sendMessage(text.error(sender as? Player, "reason.not-authorized"))
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
            if (!use || args.size != 1) return emptyList()
            return (listOf("preview") + if (admin) listOf("status", "reload") else emptyList()).filter { it.startsWith(args[0], true) }
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
