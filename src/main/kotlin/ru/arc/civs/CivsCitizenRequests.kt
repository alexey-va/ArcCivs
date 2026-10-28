package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transient requests for resident-life and collected-item actions. Start, tick and cancellation belong
 * to the resident owner thread; a future callback only enqueues its immutable reply.
 */
internal class CivsCitizenRequests<P>(private val ownerClosed: () -> Boolean) {
    private data class Request<P>(val workerId: UUID, val actorId: UUID, val ticket: UUID, val payload: P)
    private data class Reply<P>(val request: Request<P>, val error: Throwable?)
    data class Completed<P>(val workerId: UUID, val actorId: UUID, val payload: P, val error: Throwable?)
    private val pending = hashMapOf<UUID, Request<P>>()
    private val replies = ConcurrentLinkedQueue<Reply<P>>()
    private val closed = AtomicBoolean()

    fun pending(workerId: UUID, actorId: UUID): Boolean = pending[workerId]?.actorId == actorId

    fun start(workerId: UUID, actorId: UUID, payload: P, operation: () -> CompletableFuture<Settlement>) {
        if (closed.get() || ownerClosed()) return
        val request = Request(workerId, actorId, UUID.randomUUID(), payload)
        pending[workerId] = request
        val future = try { operation() } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        future.whenComplete { _, error ->
            if (!closed.get() && !ownerClosed()) replies.add(Reply(request, error))
        }
    }

    fun tick(currentInstance: (UUID) -> UUID?, completed: (Completed<P>) -> Unit) {
        while (true) {
            val reply = replies.poll() ?: return
            val request = reply.request
            val actorId = currentInstance(request.workerId)
            val current = pending[request.workerId] ?: continue
            if (current.actorId != request.actorId || current.ticket != request.ticket) continue
            pending.remove(request.workerId)
            if (actorId != request.actorId) continue
            completed(Completed(request.workerId, request.actorId, request.payload, reply.error))
        }
    }

    /** Revokes only transient completion eligibility; an accepted durable operation keeps running. */
    fun cancel(workerId: UUID, actorId: UUID) {
        if (pending[workerId]?.actorId == actorId) pending.remove(workerId)
    }

    fun close() {
        closed.set(true)
        pending.clear()
        replies.clear()
    }
}
