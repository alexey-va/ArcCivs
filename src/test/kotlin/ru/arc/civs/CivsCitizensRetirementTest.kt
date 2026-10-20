package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class CivsCitizensRetirementTest : FunSpec({
    test("packet cleanup waits for its real terminal and observer cancellation cannot cancel the owner") {
        MockBukkitTestRuntime.open().use { paper ->
            val terminal = CompletableFuture<Void>()
            val packets = mockk<PaperPacketNpcs>(relaxed = true)
            every { packets.closeAsync() } returns terminal
            val owner = CivsCitizens(paper.createSimplePlugin("ResidentRetirement"), { emptyList() },
                { _, _, _, _, _, _, _ -> CompletableFuture.failedFuture(AssertionError("no work during close")) },
                { _, _, _ -> error("no interaction during close") }, packetOwner = packets)
            val first = owner.closeAsync()
            first.isDone shouldBe false
            first.cancel(false)
            terminal.isCancelled shouldBe false
            val second = owner.closeAsync()
            second.isDone shouldBe false
            terminal.complete(null)
            second.join()
            owner.close()
            verify(exactly = 1) { packets.closeAsync() }
            verify { packets.onInteract = null }
        }
    }

    test("packet transport cleanup failure stays visible on repeated close") {
        MockBukkitTestRuntime.open().use { paper ->
            val terminal = CompletableFuture<Void>()
            val packets = mockk<PaperPacketNpcs>(relaxed = true)
            every { packets.closeAsync() } returns terminal
            val owner = CivsCitizens(paper.createSimplePlugin("ResidentRetirementFailure"), { emptyList() },
                { _, _, _, _, _, _, _ -> CompletableFuture.failedFuture(AssertionError("no delivery")) },
                { _, _, _ -> }, packetOwner = packets)
            val observed = owner.closeAsync()
            terminal.completeExceptionally(IllegalStateException("channel cleanup failed"))
            observed.isCompletedExceptionally shouldBe true
            owner.closeAsync().isCompletedExceptionally shouldBe true
            verify(exactly = 1) { packets.closeAsync() }
        }
    }
})
