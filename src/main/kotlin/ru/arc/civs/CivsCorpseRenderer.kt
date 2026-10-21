package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketNpcInteraction
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialNativeTarget
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level
import kotlin.math.floor

/** Presentation only: saved corpses own the lifetime and inventory; the UI owns loot authority. */
class CivsCorpseRenderer internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val text: CivsText,
    private val cities: () -> Collection<Settlement>,
    private val open: (Player, UUID, UUID) -> Unit,
    private val npcs: PaperPacketNpcs,
    private val displays: PaperPacketDisplays,
    private val now: () -> Long = System::currentTimeMillis,
    private val skins: List<CitizenSkin> = loadCitizenSkins(),
    private val space: PaperSpatialSpace = PaperSpatialSpace(),
) : Listener, AutoCloseable {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, text: CivsText,
                cities: () -> Collection<Settlement>, open: (Player, UUID, UUID) -> Unit) :
        this(plugin, tasks, text, cities, open, PaperPacketNpcs(plugin, "civs-corpses"),
            PaperPacketDisplays(plugin, "civs-corpses"))

    private data class Desired(val cityId: UUID, val worldId: UUID, val corpse: CivCorpse)
    private data class Rendered(val npc: PacketPlayerNpc, val label: PacketDisplayScene<String>,
        val geometry: CivsCorpseGeometry, var value: Desired, var items: List<CivResidentItem?>? = null)

    private val epoch = tasks.token()
    private val work = BudgetedTaskQueue(tasks)
    private val rendered = linkedMapOf<CivsCorpseIdentity, Rendered>()
    private var desired = emptyMap<CivsCorpseIdentity, Desired>()
    private var remaining = ArrayDeque<CivsCorpseIdentity>()
    private var pending = false
    private var closed = false
    private var retryAfter = 0L
    private var terminal: CompletableFuture<Void>? = null
    private val controls = PaperSpatialControls(plugin, displays,
        object : SpatialControlController<CivsCorpseIdentity, Unit>() {
            override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<CivsCorpseIdentity, Unit>? {
                val entry = rendered.entries.firstOrNull { it.key.scene == scene } ?: return null
                if (!mayOpen(player, entry.key)) return null
                return controlFrame(entry.key, entry.value.geometry)
            }
            override fun activate(player: Player, scene: SpatialSceneKey, context: CivsCorpseIdentity, action: Unit) {
                openIfCurrent(player, context)
            }
            override fun inputFailed(player: Player, scene: SpatialSceneKey, context: CivsCorpseIdentity, failure: Exception) {
                remove(context)
            }
        })
    private val pulseTask = tasks.runTimer(epoch, 1, 5, ::pulse)

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        npcs.onInteract = { player, npc, action ->
            // Core has already fenced this packet generation. Attacks on a corpse have no gameplay effect.
            if (action == PacketNpcInteraction.INTERACT) rendered.entries.firstOrNull { it.value.npc === npc }?.let {
                controls.intercept(it.key.scene, player)
            }
        }
    }

    internal fun pulse() {
        check(Bukkit.isPrimaryThread()) { "Resident corpses require the server thread" }
        if (closed || !tasks.isCurrent(epoch)) return
        val at = now()
        desired = cities().flatMap { city -> city.corpses.orEmpty().filter {
            it.diedAt <= at && it.expiresAt > at && city.workers.none { live -> live.id == it.worker.id }
        }.map { corpse -> identity(city.id, corpse) to Desired(city.id, city.worldId, corpse) } }.toMap()
        if (pending || at < retryAfter) return
        remaining = ArrayDeque((rendered.keys + desired.keys).distinct())
        pending = true
        work.submit({ !closed && tasks.isCurrent(epoch) }, ::reconcileOne).whenComplete { _, failure ->
            pending = false
            if (failure != null && !closed && failure !is CancellationException) {
                retryAfter = now() + 5_000
                plugin.logger.log(Level.WARNING, "ArcCivs corpse rendering failed", failure)
            }
        }
    }

    /** World admission, visual updates and two native controls are bounded to one corpse per step. */
    private fun reconcileOne(): Boolean {
        val id = remaining.removeFirstOrNull() ?: return true
        val value = desired[id] ?: run { remove(id); return false }
        val world = Bukkit.getWorld(value.worldId)
        if (world == null || value.corpse.expiresAt <= now()) { remove(id); return false }
        val geometry = civsCorpseGeometry(world, value.corpse)
        if (!space.known(world, geometry.bounds)) { remove(id); return false }
        var shown = rendered[id]
        if (shown != null && (shown.value.worldId != value.worldId || shown.value.corpse.position != value.corpse.position ||
                shown.value.corpse.feetY != value.corpse.feetY ||
                citizenScale(shown.value.corpse.worker) != citizenScale(value.corpse.worker))) {
            remove(id)
            shown = null
        }
        if (shown == null) {
            val skin = citizenSkin(skins, value.corpse.worker)
            val visualId = UUID.nameUUIDFromBytes("civs-corpse:$id".toByteArray(Charsets.UTF_8))
            val npc = npcs.spawnPlayer(geometry.feet, PacketNpcProfile(visualId,
                "corpse_" + visualId.toString().replace("-", "").take(9), skin.texture, skin.signature))
            val label = PacketDisplayScene<String>(displays)
            shown = Rendered(npc, label, geometry, value).also { rendered[id] = it }
            npc.scale = citizenScale(value.corpse.worker)
            npc.sleepingBed = null // No fake bed/block; native sleeping rotation uses bodyYaw.
            npc.bodyYaw = 0f
            npc.headYaw = 0f
            npc.pitch = 0f
            npc.pose = PacketNpcPose.SLEEPING
            npc.viewRange = 32.0
        }
        shown.label.apply(SceneFrame(listOf(ScenePart.Text("nameplate",
            ScenePose(geometry.label, Transformation(Vector3f(), Quaternionf(), Vector3f(.5f), Quaternionf())),
            SceneDisplayStyle(billboard = Display.Billboard.CENTER, brightness = Display.Brightness(15, 15),
                viewRange = .5f, displayWidth = 4f, displayHeight = 1f), civsCorpseText(text, value.corpse),
            SceneTextStyle(lineWidth = 240, background = Color.fromARGB(105, 28, 24, 19), shadowed = true,
                alignment = TextDisplay.TextAlignment.CENTER))), SceneAudience.Public()))
        val items = CivsEquipment.slots(value.corpse.worker)
        if (shown.items != items) {
            listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND).forEachIndexed { index, slot ->
                shown.npc.setEquipment(slot, items[index]?.stack() ?: ItemStack(Material.AIR))
            }
            shown.items = items
        }
        shown.value = value
        controls.apply(id.scene, controlFrame(id, geometry))
        return false
    }

    private fun controlFrame(id: CivsCorpseIdentity, geometry: CivsCorpseGeometry): SpatialControlFrame<CivsCorpseIdentity, Unit> =
        SpatialControlFrame(id, SceneAudience.Public(), SpatialSceneScope.World,
            geometry.targets.filter { space.clearBounds(geometry.world, it.bounds) }.mapIndexed { index, target ->
                SpatialControl("body-$index", Unit, target.planes, target.native)
            }, SpatialInputProfile.ENTITY_RIGHT, SpatialReach.NativeInteraction)

    internal fun openIfCurrent(player: Player, id: CivsCorpseIdentity) {
        if (mayOpen(player, id)) open(player, id.cityId, id.workerId)
    }

    private fun mayOpen(player: Player, id: CivsCorpseIdentity): Boolean {
        if (closed || !tasks.isCurrent(epoch) || !player.isOnline || player.isDead) return false
        val shown = rendered[id] ?: return false
        val city = cities().firstOrNull { it.id == id.cityId && it.worldId == player.world.uid && it.canManage(player.uniqueId) } ?: return false
        val corpse = city.corpses.orEmpty().firstOrNull { identity(city.id, it) == id } ?: return false
        return corpse.diedAt <= now() && corpse.expiresAt > now() && corpse.position == shown.value.corpse.position &&
            corpse.feetY == shown.value.corpse.feetY && city.workers.none { it.id == id.workerId } &&
            player.location.distanceSquared(Location(player.world, corpse.position.x + .5,
                corpse.feetY ?: corpse.position.y.toDouble(), corpse.position.z + .5)) <= 36.0 &&
            space.known(shown.geometry.world, shown.geometry.bounds, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        rendered.filterValues { it.geometry.world.uid == event.world.uid &&
            it.geometry.touches(event.chunk.x, event.chunk.z) }.keys.toList().forEach(::remove)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        rendered.filterValues { it.geometry.world.uid == event.world.uid }.keys.toList().forEach(::remove)
    }

    private fun remove(id: CivsCorpseIdentity) {
        val shown = rendered.remove(id)
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        attempt { controls.clear(id.scene) }
        shown?.let { attempt { it.npc.remove() }; attempt { it.label.close() } }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        val completion = CompletableFuture<Void>()
        terminal = completion
        val drains = mutableListOf<CompletableFuture<Void>>()
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) { drains += CompletableFuture.failedFuture(error) }
        }
        fun drain(action: () -> CompletableFuture<Void>) {
            drains += try { action() } catch (error: Throwable) { CompletableFuture.failedFuture(error) }
        }
        attempt { pulseTask?.cancel() }
        attempt { work.close() }
        attempt { HandlerList.unregisterAll(this) }
        attempt { npcs.onInteract = null }
        desired = emptyMap()
        remaining.clear()
        rendered.clear()
        attempt { controls.close() }
        drain { npcs.closeAsync() }
        drain { displays.closeAsync() }
        CompletableFuture.allOf(*drains.toTypedArray()).whenComplete { _, _ ->
            val failures = drains.mapNotNull { stage ->
                try { stage.join(); null } catch (failure: CompletionException) { failure.cause ?: failure }
                catch (failure: Throwable) { failure }
            }.distinct()
            val failure = failures.firstOrNull()
            if (failure == null) completion.complete(null) else {
                failures.drop(1).forEach(failure::addSuppressed)
                completion.completeExceptionally(failure)
            }
        }
    }

    fun drained(): CompletableFuture<Void> = terminal?.copy() ?: CompletableFuture.completedFuture(null)
}

internal data class CivsCorpseIdentity(val cityId: UUID, val workerId: UUID, val diedAt: Long) {
    val scene: SpatialSceneKey get() = SpatialSceneKey("civs-corpse:$cityId:$workerId:$diedAt")
}
private fun identity(cityId: UUID, corpse: CivCorpse) = CivsCorpseIdentity(cityId, corpse.worker.id, corpse.diedAt)

internal data class CivsCorpseTarget(val bounds: BoundingBox, val native: SpatialNativeTarget, val planes: List<SpatialHitPlane>)
internal data class CivsCorpseGeometry(val world: World, val feet: Location, val label: Location,
    val bounds: BoundingBox, val targets: List<CivsCorpseTarget>) {
    fun touches(x: Int, z: Int): Boolean = x in (floor(bounds.minX).toInt() shr 4)..(floor(bounds.maxX - 1e-5).toInt() shr 4) &&
        z in (floor(bounds.minZ).toInt() shr 4)..(floor(bounds.maxZ - 1e-5).toInt() shr 4)
}

/** 1.21.11: Ry(0) Rz(90) Ry(270), player scale .9375 and model-origin translation -1.501.
 * Head lies toward -X. Two adjacent low squares cover the body, never a two-block-wide square. */
internal fun civsCorpseGeometry(world: World, corpse: CivCorpse): CivsCorpseGeometry {
    val scale = citizenScale(corpse.worker)
    val center = Location(world, corpse.position.x + .5, corpse.feetY ?: corpse.position.y.toDouble(), corpse.position.z + .5)
    val floor = center.y + .02
    val width = scale
    val height = .55 * scale
    val targets = listOf(-.5, .5).map { offset ->
        val at = center.clone().add(offset * scale, .02, 0.0)
        val box = BoundingBox(at.x - width / 2, floor, at.z - width / 2, at.x + width / 2, floor + height, at.z + width / 2)
        val midY = floor + height / 2
        val planes = listOf(
            SpatialHitPlane(Location(world, at.x, floor + height, at.z), Vector(1, 0, 0), Vector(0, 0, 1), width, width),
            SpatialHitPlane(Location(world, at.x, floor, at.z), Vector(1, 0, 0), Vector(0, 0, 1), width, width),
            SpatialHitPlane(Location(world, box.minX, midY, at.z), Vector(0, 0, 1), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, box.maxX, midY, at.z), Vector(0, 0, 1), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, at.x, midY, box.minZ), Vector(1, 0, 0), Vector(0, 1, 0), width, height),
            SpatialHitPlane(Location(world, at.x, midY, box.maxZ), Vector(1, 0, 0), Vector(0, 1, 0), width, height),
        )
        CivsCorpseTarget(box, SpatialNativeTarget(at, width.toFloat(), height.toFloat()), planes)
    }
    return CivsCorpseGeometry(world, center.clone().add(.9384375 * scale, .02 + .265 * scale, 0.0),
        center.clone().add(0.0, .25 + .85 * scale, 0.0), targets.first().bounds.clone().union(targets.last().bounds), targets)
}

internal fun civsCorpseText(text: CivsText, corpse: CivCorpse): Component {
    fun tr(key: String, vararg values: Pair<String, Any?>) = text.render(key, "ru", *values)
    val title = tr(if (corpse.worker.sex == CivSex.FEMALE) "corpse.inventory-title-female" else "corpse.inventory-title",
        "name" to text.literal(corpse.worker.name)).color(TextColor.color(0xE1C58D))
    val death = if (corpse.killerName != null || corpse.killerType != null) {
        val key = "corpse.killer.${corpse.killerType?.lowercase(Locale.ROOT)}"
        tr("corpse.killed-by", "name" to (corpse.killerName?.let(text::literal)
            ?: tr(key.takeIf { it in text.keys("ru") } ?: "corpse.killer.unknown")))
    } else {
        val key = "corpse.cause.${corpse.cause.lowercase(Locale.ROOT)}"
        tr("corpse.died-from", "cause" to tr(key.takeIf { it in text.keys("ru") } ?: "corpse.cause.unknown"))
    }
    return title.append(Component.newline()).append(death.color(TextColor.color(0xCDC7BC)))
}
