package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

class CivsCitizensRetirementTest : FunSpec({
    test("all 48 old NPCs retire in host slices before the empty registry acknowledgement") {
        withCitizensRetirementFixture { fixture ->
            fixture.populate(48)
            val observer = fixture.owner.closeAsync()
            observer.isDone shouldBe false
            fixture.destroyed shouldBe 0
            fixture.deregistered shouldBe 0
            observer.cancel(false)
            fixture.paper.server.pluginManager.disablePlugin(fixture.consumer)
            repeat(30) {
                val before = fixture.destroyed
                fixture.scheduler.tick()
                (fixture.destroyed - before <= 7) shouldBe true
            }
            fixture.owner.closeAsync().join()
            fixture.destroyed shouldBe 48
            fixture.remaining.isEmpty() shouldBe true
            fixture.deregistered shouldBe 1
            fixture.owner.close()
            fixture.scheduler.tick()
            fixture.deregistered shouldBe 1
        }
    }

    test("one failed NPC does not skip the other old actors or fall back to bulk deregistration") {
        withCitizensRetirementFixture { fixture ->
            fixture.populate(20, failAt = 0)
            val retired = fixture.owner.closeAsync()
            repeat(20) { fixture.scheduler.tick() }
            retired.isCompletedExceptionally shouldBe true
            fixture.destroyed shouldBe 19
            fixture.remaining.size shouldBe 1
            fixture.deregistered shouldBe 0
            fixture.npcs.forEach { npc ->
                verify(exactly = 1) { npc.navigator.cancelNavigation() }
                verify(exactly = 1) { npc.destroy() }
            }
        }
    }

    test("host stop after partial cleanup fails the actual future without touching the remaining NPCs") {
        withCitizensRetirementFixture { fixture ->
            fixture.populate(48)
            val retired = fixture.owner.closeAsync()
            repeat(10) { fixture.scheduler.tick() }
            (fixture.destroyed in 1..47) shouldBe true
            retired.isDone shouldBe false
            val before = fixture.destroyed
            fixture.queue.close()
            retired.isCompletedExceptionally shouldBe true
            repeat(5) { fixture.scheduler.tick() }
            fixture.destroyed shouldBe before
            fixture.deregistered shouldBe 0
        }
    }
})

private inline fun withCitizensRetirementFixture(block: (CitizensRetirementFixture) -> Unit) {
    mockkStatic(CitizensAPI::class)
    try {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = CitizensRetirementFixture(paper)
            try { block(fixture) } finally { fixture.queue.close(); fixture.scope.close() }
        }
    } finally { unmockkStatic(CitizensAPI::class) }
}

private class CitizensRetirementFixture(val paper: MockBukkitTestRuntime) {
    val consumer = paper.createSimplePlugin("CivsRetiring")
    private val provider = paper.createSimplePlugin("Citizens")
    private val host = paper.createSimplePlugin("CivsCleanupHost")
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    val queue = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
    private val registry = mockk<NPCRegistry>()
    val remaining = mutableListOf<NPC>()
    val npcs = mutableListOf<NPC>()
    var destroyed = 0
    var deregistered = 0
    val owner: CivsCitizens

    init {
        paper.server.servicesManager.register(ArcNativeCleanupService::class.java, object : ArcNativeCleanupService {
            override fun submit(owner: String, step: BooleanSupplier) =
                queue.submit({ true }, step::getAsBoolean).thenApply<Void> { null }.minimalCompletionStage()
        }, host, ServicePriority.Normal)
        every { CitizensAPI.getPlugin() } returns provider
        every { CitizensAPI.createAnonymousNPCRegistry(any()) } returns registry
        every { registry.iterator() } answers { remaining.iterator() }
        every { registry.deregisterAll() } answers {
            Bukkit.isPrimaryThread() shouldBe true
            remaining.isEmpty() shouldBe true
            deregistered++
        }
        owner = CivsCitizens(consumer, { emptyList() }, { _, _, _, _, _, _, _ ->
            CompletableFuture.failedFuture(AssertionError("retirement must not submit a delivery"))
        }, { _, _, _ -> error("retirement must not invoke interaction") })
    }

    fun populate(count: Int, failAt: Int? = null) {
        repeat(count) { index ->
            val npc = mockk<NPC>(relaxed = true)
            every { npc.navigator.cancelNavigation() } answers {
                Bukkit.isPrimaryThread() shouldBe true
                Unit
            }
            every { npc.destroy() } answers {
                Bukkit.isPrimaryThread() shouldBe true
                if (index == failAt) throw IllegalStateException("NPC $index refused retirement")
                remaining.remove(npc)
                destroyed++
                Unit
            }
            npcs.add(npc)
            remaining.add(npc)
        }
    }
}
