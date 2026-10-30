package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import ru.arc.paper.npc.PacketPlayerNpc
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Actual spawned-actor feet and current observed state, captured by the resident simulation. */
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
    val actor: PacketPlayerNpc? = null,
    val profession: String = "",
    val courierCart: Boolean = false,
    val cargo: Map<String, Int> = emptyMap(),
    val yaw: Float = 0f,
    val nativeHeight: Double = 1.8,
    val hunger: Int = 20,
    val child: Boolean = false,
    val statusInputs: List<String> = emptyList(),
)

/** Nearby public nameplates. No Bukkit entities, block reads, routing or gameplay state changes. */
class CivsHolograms internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val text: CivsText,
    private val snapshots: () -> List<CivsNpcHologramSnapshot>,
    private val displays: PaperPacketDisplays,
    private val nativeWork: BudgetedTaskQueue? = null,
    private val workplaces: () -> List<CivsWorkplaceSnapshot> = { emptyList() },
) : AutoCloseable {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, text: CivsText,
                snapshots: () -> List<CivsNpcHologramSnapshot>, nativeWork: BudgetedTaskQueue? = null,
                workplaces: () -> List<CivsWorkplaceSnapshot> = { emptyList() }) :
        this(plugin, tasks, text, snapshots, nativeWork?.let { PaperPacketDisplays(plugin, "civs-residents", it) }
            ?: PaperPacketDisplays(plugin, "civs-residents"), nativeWork, workplaces)

    private data class Desired(val snapshot: CivsNpcHologramSnapshot, val text: Component)
    private data class Rendered(val handle: PacketTextDisplay, var value: Desired?)
    private val work = nativeWork ?: BudgetedTaskQueue(tasks)
    private val epoch = tasks.token()
    private val rendered = linkedMapOf<UUID, Rendered>()
    private val carts = CivsCourierCart(displays)
    private val decor = CivsWorkplaceDecor(displays)
    private var desired = emptyMap<UUID, Desired>()
    private var pending = false
    private var closed = false
    private var terminal: CompletableFuture<Void>? = null
    private val pulseTask = tasks.runTimer(epoch, 1, 5, ::pulse)
    private val poseTask = tasks.runTimer(epoch, 1, 1) { carts.updatePoses() }

    internal fun pulse() {
        check(Bukkit.isPrimaryThread()) { "Resident nameplates require the server thread" }
        if (closed || !tasks.isCurrent(epoch)) return
        desired = snapshots().asSequence().filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
            it.health.isFinite() && it.maxHealth.isFinite() && it.health >= 0 && it.maxHealth > 0 &&
            it.health <= it.maxHealth && it.hunger in 0..20 && it.nativeHeight.isFinite() && it.nativeHeight > 0 &&
            Bukkit.getWorld(it.worldId) != null && (it.actor == null || it.actor.isValid) }
            .associate { it.workerId to Desired(it, civsNpcHologramText(text, it)) }
        decor.capture(workplaces())
        if (pending || decor.current() && rendered.size == desired.size && rendered.all { (id, shown) -> samePresentation(shown.value, desired[id]) }) return
        pending = true
        work.submit({ !closed && tasks.isCurrent(epoch) }, ::reconcileOne).whenComplete { _, failure ->
            pending = false
            if (failure != null && !closed && failure !is CancellationException) plugin.logger.log(Level.WARNING,
                "ArcCivs resident nameplate rendering failed", failure)
        }
    }

    /** One actor's bounded presentation per shared step; movement belongs to its carrier. */
    private fun reconcileOne(): Boolean {
        rendered.keys.firstOrNull { it !in desired }?.let {
            rendered.remove(it)!!.handle.remove()
            carts.remove(it)
            return false
        }
        val changed = desired.entries.firstOrNull { !samePresentation(rendered[it.key]?.value, it.value) } ?: return decor.reconcileOne()
        val value = changed.value
        val npc = value.snapshot
        val world = Bukkit.getWorld(npc.worldId) ?: run {
            desired = desired - changed.key
            return false
        }
        val location = Location(world, npc.x,
            npc.y + npc.nativeHeight + if (npc.actor == null) CIVS_NPC_TEXT_GAP else 0.0, npc.z)
        val shown = rendered[changed.key] ?: Rendered(displays.spawnText(location, value.text), null).also {
            rendered[changed.key] = it
            it.handle.billboard = Display.Billboard.CENTER
            it.handle.alignment = TextDisplay.TextAlignment.CENTER
            it.handle.lineWidth = CIVS_NPC_TEXT_WIDTH
            it.handle.backgroundColor = Color.fromARGB(105, 28, 24, 19)
            it.handle.isShadowed = true
            it.handle.isSeeThrough = false
            it.handle.viewRange = 1f
            it.handle.brightness = Display.Brightness(15, 15)
            it.handle.shadowRadius = 0f
            it.handle.shadowStrength = 0f
            it.handle.displayWidth = 4f
            it.handle.displayHeight = 1f
            it.handle.teleportDuration = 0
        }
        val before = shown.value
        if (npc.actor != null) {
            shown.handle.attachTo(npc.actor)
        } else {
            // Static export/fixture support. Spawned residents always supply the packet carrier.
            shown.handle.detach()
            if (before != null) shown.handle.teleport(location)
        }
        shown.handle.transformation = Transformation(
            Vector3f(0f, if (npc.actor == null) 0f else CIVS_NPC_TEXT_GAP.toFloat(), 0f),
            Quaternionf(), Vector3f(CIVS_NPC_TEXT_SCALE), Quaternionf())
        if (before != null && before.text != value.text) shown.handle.text(value.text)
        carts.sync(npc)
        shown.value = value
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        pulseTask?.cancel()
        poseTask?.cancel()
        carts.close()
        decor.close()
        desired = emptyMap()
        rendered.clear()
        if (nativeWork == null) work.close()
        terminal = displays.closeAsync()
    }

    fun drained(): CompletableFuture<Void> = terminal?.copy() ?: CompletableFuture.completedFuture(null)

    private fun samePresentation(first: Desired?, second: Desired?): Boolean {
        if (first == null || second == null) return first == second
        val a = first.snapshot
        val b = second.snapshot
        return first.text == second.text && a.actor === b.actor && a.nativeHeight == b.nativeHeight &&
            a.courierCart == b.courierCart && a.cargo == b.cargo &&
            (a.actor != null || a.worldId == b.worldId && a.x == b.x && a.y == b.y && a.z == b.z)
    }
}

internal const val CIVS_NPC_TEXT_SCALE = 0.55f
internal const val CIVS_NPC_TEXT_WIDTH = 220
internal const val CIVS_NPC_TEXT_GAP = .20
internal const val CIVS_NPC_TEXT_OFFSET = 1.8 + CIVS_NPC_TEXT_GAP

internal fun civsNpcHologramText(text: CivsText, npc: CivsNpcHologramSnapshot): Component {
    val action = if (npc.status.removePrefix("workers.status.") == "waiting-tool") {
        when (npc.profession) {
            "farmer", "forester", "miner" -> text.render("hologram.tool-${npc.profession}")
            else -> text.status(null, npc.status)
        }
    } else text.status(null, npc.status, npc.statusInputs)
    val profession = when (val key = if (npc.child) "child" else npc.profession) {
        "" , "unassigned" -> text.render("hologram.resident")
        "courier" -> text.render("hologram.courier")
        else -> text.render("profession.$key")
    }
    return text.render("hologram.npc",
        values = arrayOf(
            "name" to Component.text(npc.name, TextColor.color(0xF3EFE5)),
            "profession" to profession.color(TextColor.color(0xE1C58D)),
            "action" to action.color(TextColor.color(0xCDC7BC)),
            "health" to text.residentVitals(null, npc.health, npc.maxHealth, npc.hunger),
        ))
}
