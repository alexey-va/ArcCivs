package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Actual spawned-actor feet and current observed state, captured by the Citizens owner. */
data class CivsNpcHologramSnapshot(
    val workerId: UUID,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val name: String,
    val status: String,
    val health: Double,
    val maxHealth: Double,
)

/** Nearby public nameplates. No Bukkit entities, block reads, routing or gameplay state changes. */
class CivsHolograms internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val text: CivsText,
    private val snapshots: () -> List<CivsNpcHologramSnapshot>,
    private val displays: PaperPacketDisplays,
    private val nativeWork: BudgetedTaskQueue? = null,
) : AutoCloseable {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, text: CivsText,
                snapshots: () -> List<CivsNpcHologramSnapshot>, nativeWork: BudgetedTaskQueue? = null) :
        this(plugin, tasks, text, snapshots, nativeWork?.let { PaperPacketDisplays(plugin, "civs-residents", it) }
            ?: PaperPacketDisplays(plugin, "civs-residents"), nativeWork)

    private data class Desired(val snapshot: CivsNpcHologramSnapshot, val text: Component)
    private data class Rendered(val handle: PacketTextDisplay, var value: Desired?)
    private val work = nativeWork ?: BudgetedTaskQueue(tasks)
    private val epoch = tasks.token()
    private val rendered = linkedMapOf<UUID, Rendered>()
    private var desired = emptyMap<UUID, Desired>()
    private var pending = false
    private var closed = false
    private var terminal: CompletableFuture<Void>? = null
    private val pulseTask = tasks.runTimer(epoch, 1, 5, ::pulse)

    internal fun pulse() {
        check(Bukkit.isPrimaryThread()) { "Resident nameplates require the server thread" }
        if (closed || !tasks.isCurrent(epoch)) return
        desired = snapshots().asSequence().filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
            it.health.isFinite() && it.maxHealth.isFinite() && it.health >= 0 && it.maxHealth > 0 &&
            it.health <= it.maxHealth && Bukkit.getWorld(it.worldId) != null }
            .associate { it.workerId to Desired(it, civsNpcHologramText(text, it)) }
        if (pending || rendered.size == desired.size && rendered.all { (id, shown) -> shown.value == desired[id] }) return
        pending = true
        work.submit({ !closed && tasks.isCurrent(epoch) }, ::reconcileOne).whenComplete { _, failure ->
            pending = false
            if (failure != null && !closed && failure !is CancellationException) plugin.logger.log(Level.WARNING,
                "ArcCivs resident nameplate rendering failed", failure)
        }
    }

    /** At most one handle create, move/update or removal in each shared native-budget step. */
    private fun reconcileOne(): Boolean {
        rendered.keys.firstOrNull { it !in desired }?.let {
            rendered.remove(it)!!.handle.remove()
            return false
        }
        val changed = desired.entries.firstOrNull { rendered[it.key]?.value != it.value } ?: return true
        val value = changed.value
        val npc = value.snapshot
        val world = Bukkit.getWorld(npc.worldId) ?: run {
            desired = desired - changed.key
            return false
        }
        val location = Location(world, npc.x, npc.y + CIVS_NPC_TEXT_OFFSET, npc.z)
        val shown = rendered[changed.key] ?: Rendered(displays.spawnText(location, value.text), null).also {
            rendered[changed.key] = it
            it.handle.billboard = Display.Billboard.CENTER
            it.handle.alignment = TextDisplay.TextAlignment.CENTER
            it.handle.lineWidth = CIVS_NPC_TEXT_WIDTH
            it.handle.backgroundColor = Color.fromARGB(115, 15, 23, 30)
            it.handle.isShadowed = true
            it.handle.isSeeThrough = false
            it.handle.viewRange = 0.25f
            it.handle.brightness = Display.Brightness(15, 15)
            it.handle.shadowRadius = 0f
            it.handle.shadowStrength = 0f
            it.handle.displayWidth = 4f
            it.handle.displayHeight = 1f
            it.handle.teleportDuration = 5
            it.handle.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(CIVS_NPC_TEXT_SCALE), Quaternionf())
        }
        shown.value?.let { before ->
            val previous = before.snapshot
            if (previous.worldId != npc.worldId || previous.x != npc.x || previous.y != npc.y || previous.z != npc.z) {
                val dx = previous.x - npc.x
                val dy = previous.y - npc.y
                val dz = previous.z - npc.z
                // A recovered actor teleports: its nameplate must not glide across the whole town.
                shown.handle.teleportDuration = if (previous.worldId != npc.worldId || dx * dx + dy * dy + dz * dz > 16) 0 else 5
                shown.handle.teleport(location)
            }
            if (before.text != value.text) shown.handle.text(value.text)
        }
        shown.value = value
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        pulseTask?.cancel()
        desired = emptyMap()
        rendered.clear()
        if (nativeWork == null) work.close()
        terminal = displays.closeAsync()
    }

    fun drained(): CompletableFuture<Void> = terminal?.copy() ?: CompletableFuture.completedFuture(null)
}

internal const val CIVS_NPC_TEXT_SCALE = 0.70f
internal const val CIVS_NPC_TEXT_WIDTH = 220
internal const val CIVS_NPC_TEXT_OFFSET = 2.15

internal fun civsNpcHologramText(text: CivsText, npc: CivsNpcHologramSnapshot): Component {
    fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')
    return text.render("hologram.npc",
        values = arrayOf(
            "name" to Component.text(npc.name, TextColor.color(0xF3EFE5)),
            "action" to text.status(null, npc.status).color(CivsText.INFO),
            "health" to text.render("hologram.health", values = arrayOf("health" to number(npc.health), "max" to number(npc.maxHealth)))
                .color(TextColor.color(0xF28E85)),
        ))
}
