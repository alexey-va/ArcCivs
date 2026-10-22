package ru.arc.civs

import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

internal const val CIVS_CHUNK_PERMISSION = "arccivs.chunkload"

/** Main-thread cache; provider IO and late replies never admit a city by themselves. */
internal class CivsChunkPermissions(
    private val available: () -> Boolean,
    private val check: (UUID, String) -> CompletableFuture<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    constructor(plugin: Plugin, clock: () -> Long = System::currentTimeMillis) : this(
        { plugin.server.pluginManager.isPluginEnabled("LuckPerms") && civsChunkPermissionApiPresent(plugin) &&
            CivsLuckPermsChunkPermission.available(plugin) },
        { owner, world -> checkCivsChunkPermission(plugin, owner, world) }, clock)

    private data class Subject(val owner: UUID, val world: String)
    private class State(var nextCheck: Long = 0, var allowedUntil: Long = 0)
    private data class Reply(val subject: Subject, val state: State, val started: Long, val allowed: Boolean)
    private val states = linkedMapOf<Subject, State>()
    private val pending = mutableSetOf<State>()
    private val replies = ConcurrentLinkedQueue<Reply>()
    @Volatile private var closed = false

    fun pulse(subjects: Collection<Pair<UUID, String>>) {
        if (closed) return
        while (true) {
            val reply = replies.poll() ?: break
            pending -= reply.state
            if (states[reply.subject] === reply.state)
                reply.state.allowedUntil = if (reply.allowed) reply.started + 30_000 else 0
        }
        if (!available()) { states.clear(); return }
        val wanted = subjects.mapTo(linkedSetOf()) { Subject(it.first, it.second) }
        states.keys.retainAll(wanted)
        val now = clock()
        for (subject in wanted) {
            val state = states.getOrPut(subject) { State() }
            if (pending.size >= 4) break
            if (state in pending || now < state.nextCheck) continue
            state.nextCheck = now + 15_000
            pending += state
            val future = runCatching { check(subject.owner, subject.world) }
                .getOrElse { CompletableFuture.failedFuture(it) }
            future.copy().orTimeout(10, TimeUnit.SECONDS).whenComplete { allowed, failure ->
                if (!closed) replies += Reply(subject, state, now, failure == null && allowed == true)
            }
        }
    }

    fun allowed(owner: UUID, world: String): Boolean = !closed && available() &&
        clock() < (states[Subject(owner, world)]?.allowedUntil ?: 0)

    fun reset() { states.clear() }
    override fun close() { closed = true; states.clear(); replies.clear() }
}

private fun checkCivsChunkPermission(plugin: Plugin, owner: UUID, world: String): CompletableFuture<Boolean> {
    if (!civsChunkPermissionApiPresent(plugin)) return CompletableFuture.completedFuture(false)
    return CivsLuckPermsChunkPermission.check(plugin, owner, world)
}

private fun civsChunkPermissionApiPresent(plugin: Plugin): Boolean =
    runCatching { Class.forName("net.luckperms.api.LuckPerms", false, plugin.javaClass.classLoader) }.isSuccess

/** Isolated so listener discovery and startup remain safe without the optional API. */
internal object CivsLuckPermsChunkPermission {
    fun available(plugin: Plugin): Boolean = plugin.server.servicesManager.load(net.luckperms.api.LuckPerms::class.java) != null

    fun check(plugin: Plugin, owner: UUID, world: String): CompletableFuture<Boolean> {
        val api = plugin.server.servicesManager.load(net.luckperms.api.LuckPerms::class.java)
            ?: return CompletableFuture.completedFuture(false)
        val context = api.contextManager.staticContext.mutableCopy().apply {
            removeAll("world")
            add("world", world)
        }.immutableCopy()
        val options = api.contextManager.staticQueryOptions.toBuilder().context(context).build()
        return api.userManager.loadUser(owner).thenApply { user ->
            try { user.cachedData.getPermissionData(options).checkPermission(CIVS_CHUNK_PERMISSION).asBoolean() }
            finally { api.userManager.cleanupUser(user) }
        }
    }
}
