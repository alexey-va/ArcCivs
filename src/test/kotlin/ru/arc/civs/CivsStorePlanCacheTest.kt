package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import ru.arc.persistence.AtomicFileStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsStorePlanCacheTest : FreeSpec({
    lateinit var root: Path
    beforeEach { root = Files.createTempDirectory("arc-civs-plan-cache-") }
    afterEach { root.toFile().deleteRecursively() }

    "repeated committed plan loads read and decode the file once" {
        val plan = plan()
        val file = writePlan(root, plan)
        val store = CivsStore(root, directExecutor)
        var reads = 0
        mockkConstructor(AtomicFileStore::class) {
            every { anyConstructed<AtomicFileStore<Any>>().loadOrNull() } answers { reads++; callOriginal() }
            repeat(12) { store.loadPlan(plan.id).join() shouldBe plan }
            reads shouldBe 1
        }
    }

    "successful replacement publishes its committed readback without another load" {
        val original = plan()
        val replacement = original.copy(blocks = original.blocks.map { it.copy(after = "minecraft:andesite") })
        val file = root.resolve("plans/${original.id}.json")
        val store = CivsStore(root, directExecutor)
        var reads = 0
        mockkConstructor(AtomicFileStore::class) {
            every { anyConstructed<AtomicFileStore<Any>>().loadOrNull() } answers { reads++; callOriginal() }
            store.savePlan(original).join() shouldBe original
            store.loadPlan(original.id).join() shouldBe original
            store.savePlan(replacement).join() shouldBe replacement
            repeat(3) { store.loadPlan(original.id).join() shouldBe replacement }
            reads shouldBe 2 // One verified AtomicFileStore readback for each committed save.
        }
    }

    "a failed acknowledgement after committed write evicts the previous value and requires fresh readback" {
        val original = plan()
        val replacement = original.copy(blocks = original.blocks.map { it.copy(after = "minecraft:andesite") })
        val file = writePlan(root, original)
        val store = CivsStore(root, directExecutor)
        store.loadPlan(original.id).join() shouldBe original
        var reads = 0
        mockkConstructor(AtomicFileStore::class) {
            every { anyConstructed<AtomicFileStore<Any>>().write(any()) } answers {
                callOriginal()
                throw IOException("lost acknowledgement after committed write")
            }
            every { anyConstructed<AtomicFileStore<Any>>().loadOrNull() } answers { reads++; callOriginal() }
            shouldThrow<CompletionException> { store.savePlan(replacement).join() }
            store.loadPlan(original.id).join() shouldBe replacement
            reads shouldBe 2 // Verified write readback, then a fresh read after the failed acknowledgement.
        }
    }

    "delete and failed delete cannot leave a cached plan behind" {
        val original = plan()
        val replacement = original.copy(adminPlacement = true)
        val file = writePlan(root, original)
        val store = CivsStore(root, directExecutor)
        store.loadPlan(original.id).join() shouldBe original
        mockkConstructor(AtomicFileStore::class) {
            every { anyConstructed<AtomicFileStore<Any>>().deleteIfExists() } throws IOException("delete failed")
            shouldThrow<CompletionException> { store.deletePlan(original.id).join() }
            writePlan(root, replacement)
            store.loadPlan(original.id).join() shouldBe replacement
        }
        store.deletePlan(original.id).join() shouldBe true
        store.loadPlan(original.id).join() shouldBe null
        store.deletePlan(original.id).join() shouldBe false
    }

    "missing and failed reads are not retained" {
        val plan = plan()
        val store = CivsStore(root, directExecutor)
        store.loadPlan(plan.id).join() shouldBe null
        val file = writePlan(root, plan)
        Files.writeString(file, "{")
        shouldThrow<CompletionException> { store.loadPlan(plan.id).join() }
        writePlan(root, plan)
        store.loadPlan(plan.id).join() shouldBe plan
    }

    "explicit load refreshes external plan edits even when the city reload fails" {
        val original = plan()
        val replacement = original.copy(adminPlacement = true)
        val finalPlan = replacement.copy(blocks = original.blocks.map { it.copy(after = "minecraft:andesite") })
        writePlan(root, original)
        val store = CivsStore(root, directExecutor)
        store.loadPlan(original.id).join() shouldBe original
        writePlan(root, replacement)
        store.load().join() shouldBe emptyList()
        store.loadPlan(original.id).join() shouldBe replacement
        writePlan(root, finalPlan)
        Files.createDirectories(root.resolve("cities"))
        Files.writeString(root.resolve("cities/${UUID.randomUUID()}.json"), "{")
        shouldThrow<CompletionException> { store.load().join() }
        store.loadPlan(original.id).join() shouldBe finalPlan
    }

    "four recent plans are retained and an access protects the recently used plan" {
        val plans = List(5) { plan() }
        plans.forEach { writePlan(root, it) }
        val store = CivsStore(root, directExecutor)
        var reads = 0
        mockkConstructor(AtomicFileStore::class) {
            every { anyConstructed<AtomicFileStore<Any>>().loadOrNull() } answers { reads++; callOriginal() }
            plans.take(4).forEach { store.loadPlan(it.id).join() shouldBe it }
            store.loadPlan(plans.first().id).join() shouldBe plans.first()
            store.loadPlan(plans.last().id).join() shouldBe plans.last()
            store.loadPlan(plans.first().id).join() shouldBe plans.first()
            store.loadPlan(plans[1].id).join() shouldBe plans[1]
            reads shouldBe 6 // Four initial reads, the fifth plan, then the evicted second plan.
        }
    }

    "cache hits execute under the same serialized IO ordering as replacement and deletion" {
        val original = plan()
        val replacement = original.copy(adminPlacement = true)
        writePlan(root, original)
        val executor = QueuedExecutor()
        val store = CivsStore(root, executor)
        val initial = store.loadPlan(original.id)
        executor.runNext()
        initial.join() shouldBe original
        val loading = store.loadPlan(original.id)
        val saving = store.savePlan(replacement)
        loading.isDone shouldBe false
        executor.runLast()
        saving.join() shouldBe replacement
        executor.runNext()
        loading.join() shouldBe replacement
        val laterLoad = store.loadPlan(original.id)
        val deleting = store.deletePlan(original.id)
        executor.runLast()
        deleting.join() shouldBe true
        executor.runNext()
        laterLoad.join() shouldBe null
    }

    "cached plan collections cannot alias a caller mutation of native or terrain work" {
        val original = plan().let { plan -> plan.copy(building = plan.building.copy(transit = listOf(CivPosition(0, 64, 0))),
            terrain = CivTerraformPlan(CivBox(CivPosition(0, 63, 0), CivPosition(0, 63, 0)),
                listOf(CivPlanBlock(CivPosition(0, 63, 0), "minecraft:air", "minecraft:dirt")),
                listOf(CivPosition(0, 64, 0)), 63, 0, 1, emptyList())) }
        writePlan(root, original)
        val store = CivsStore(root, directExecutor)
        val loaded = requireNotNull(store.loadPlan(original.id).join())
        loaded shouldBe original
        shouldThrow<UnsupportedOperationException> { (loaded.blocks as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (loaded.building.transit as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (loaded.terrain!!.blocks as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (loaded.terrain!!.route as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (loaded.terrain!!.waterFoundations as MutableList<*>).clear() }
        store.loadPlan(original.id).join() shouldBe original
    }
}) {
    companion object {
        private val directExecutor = Executor { it.run() }
        private fun plan(): CivPlacementPlan {
            val building = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
                "test/townhall1", CivBox(CivPosition(-2, 60, -2), CivPosition(2, 68, 2)),
                CivPosition(0, 61, -2), BuildingPhase.CONSTRUCTING)
            return CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), building,
                listOf(CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:stone")))
        }
        private fun writePlan(root: Path, plan: CivPlacementPlan): Path {
            val file = root.resolve("plans/${plan.id}.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "plan" to plan)))
            return file
        }
        private class QueuedExecutor : Executor {
            private val tasks = ArrayDeque<Runnable>()
            override fun execute(command: Runnable) { tasks.addLast(command) }
            fun runNext() = tasks.removeFirst().run()
            fun runLast() = tasks.removeLast().run()
        }
    }
}
