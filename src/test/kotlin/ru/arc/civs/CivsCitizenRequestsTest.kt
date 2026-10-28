package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenRequestsTest : FunSpec({
    for ((channel, payload) in listOf("resident-life" to true, "collected-items" to Unit)) {
        test("$channel publishes pending before operation and applies replies only on the owner tick") {
            val requests = CivsCitizenRequests<Any> { false }
            val worker = UUID.randomUUID(); val actor = UUID.randomUUID()
            val failure = IllegalStateException("operation failed")
            val accepted = mutableListOf<CivsCitizenRequests.Completed<Any>>()
            requests.start(worker, actor, payload) {
                requests.pending(worker, actor) shouldBe true
                throw failure
            }
            requests.pending(worker, actor) shouldBe true
            accepted.size shouldBe 0
            requests.tick({ actor }) {
                requests.pending(worker, actor) shouldBe false
                accepted += it
            }
            accepted.single().payload shouldBe payload
            accepted.single().error shouldBe failure
            val future = CompletableFuture<Settlement>()
            requests.start(worker, actor, payload) { future }
            CompletableFuture.runAsync { future.complete(null) }.join()
            requests.pending(worker, actor) shouldBe true
            accepted.size shouldBe 1
            val ownerThread = Thread.currentThread()
            requests.tick({ actor }) { Thread.currentThread() shouldBe ownerThread; accepted += it }
            accepted.size shouldBe 2
            accepted.last().error shouldBe null
        }

        test("$channel fences actor replacement and superseded tickets without retiring a newer request") {
            val requests = CivsCitizenRequests<Any> { false }
            val worker = UUID.randomUUID(); val oldActor = UUID.randomUUID(); val actor = UUID.randomUUID()
            val old = CompletableFuture<Settlement>(); val current = CompletableFuture<Settlement>()
            val accepted = mutableListOf<CivsCitizenRequests.Completed<Any>>()
            requests.start(worker, oldActor, payload) { old }
            requests.cancel(worker, oldActor)
            requests.start(worker, actor, payload) { current }
            requests.cancel(worker, oldActor)
            old.complete(null)
            requests.tick({ actor }, accepted::add)
            accepted.size shouldBe 0
            requests.pending(worker, actor) shouldBe true
            current.complete(null)
            requests.tick({ actor }, accepted::add)
            accepted.single().actorId shouldBe actor
            val first = CompletableFuture<Settlement>(); val second = CompletableFuture<Settlement>()
            requests.start(worker, actor, payload) { first }
            requests.start(worker, actor, payload) { second }
            first.complete(null)
            requests.tick({ actor }, accepted::add)
            requests.pending(worker, actor) shouldBe true
            accepted.size shouldBe 1
            second.complete(null)
            requests.tick({ actor }, accepted::add)
            accepted.size shouldBe 2
            requests.pending(worker, actor) shouldBe false
            val orphan = CompletableFuture<Settlement>()
            requests.start(worker, actor, payload) { orphan }
            orphan.complete(null)
            requests.tick({ null }, accepted::add)
            requests.pending(worker, actor) shouldBe false
            accepted.size shouldBe 2
        }

        test("$channel cancellation and close revoke replies without cancelling durable operations") {
            val requests = CivsCitizenRequests<Any> { false }
            val worker = UUID.randomUUID(); val actor = UUID.randomUUID()
            var accepted = 0
            val cancelled = CompletableFuture<Settlement>()
            requests.start(worker, actor, payload) { cancelled }
            requests.cancel(worker, actor)
            cancelled.isCancelled shouldBe false
            cancelled.isDone shouldBe false
            cancelled.complete(null)
            requests.tick({ actor }) { accepted++ }
            accepted shouldBe 0
            val late = CompletableFuture<Settlement>()
            requests.start(worker, actor, payload) { late }
            requests.close()
            requests.pending(worker, actor) shouldBe false
            late.isCancelled shouldBe false
            late.complete(null)
            requests.tick({ actor }) { accepted++ }
            accepted shouldBe 0
            requests.start(worker, actor, payload) { error("closed owner dispatched a new operation") }
            requests.pending(worker, actor) shouldBe false
        }
    }
})
