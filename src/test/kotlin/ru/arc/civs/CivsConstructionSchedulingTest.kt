package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier
import java.util.function.Consumer

class CivsConstructionSchedulingTest : FunSpec({
    test("builder waits yield both native slots only after acknowledgement and resume the same paid row") {
        MockBukkitTestRuntime.open().use { paper ->
            ConstructionSchedulingFixture(paper).use { h ->
                val first = h.addCity(1)
                val second = h.addCity(2, terrain = true)
                h.arrived += first
                h.pulse()
                h.runtime.activeCount shouldBe 2
                val firstPaste = h.pastes.getValue(first).single()
                firstPaste.ready.asBoolean shouldBe true
                firstPaste.write()
                h.arrived -= first
                h.tick()
                firstPaste.ready.asBoolean shouldBe false
                val terrace = h.terrain.getValue(second)
                terrace.first() shouldBe false
                val waiting = h.addCity(3, paid = false)
                h.arrived += waiting

                h.time += 29_999
                h.pulse()
                firstPaste.cancellations shouldBe 0
                terrace.second.isDone shouldBe false
                h.runtime.isPlacing(waiting) shouldBe false

                h.time++
                h.pulse()
                firstPaste.cancellations shouldBe 1
                firstPaste.ready.asBoolean shouldBe false
                terrace.second.isCancelled shouldBe true
                h.runtime.isPlacing(first) shouldBe true
                h.order(second).phase shouldBe ConstructionPhase.PLACING
                h.order(second).payment?.reservedBatch shouldBe 0
                h.pulse()
                h.runtime.activeCount shouldBe 2
                h.payments shouldBe listOf(waiting)

                firstPaste.completion.complete(ArcSchematicMutationResult(1, 0, true))
                h.tick()
                h.runtime.isPlacing(first) shouldBe false
                h.order(first).phase shouldBe ConstructionPhase.PLACING
                h.order(first).workDone shouldBe 0
                h.order(first).payment?.reservedBatch shouldBe 0
                h.block(first) shouldBe Material.STONE
                repeat(3) { h.time += 5_000; h.pulse() }
                h.pastes.getValue(first).size shouldBe 1

                h.arrived += first
                h.time += 5_000
                h.pulse()
                h.runtime.activeCount shouldBe 2
                val resumed = h.pastes.getValue(first).last()
                resumed.ready.asBoolean shouldBe true
                h.validations.count { it == first } shouldBe 2
                resumed.write() // The retained native cell is already correct on the resumed capture.
                resumed.completion.complete(ArcSchematicMutationResult(0, 1, false))
                h.tick()
                h.order(first).workDone shouldBe 1
                h.order(first).blocksPlaced shouldBe 1
                h.order(first).payment?.reservedBatch shouldBe null
                h.payments shouldBe listOf(waiting)
                h.block(first) shouldBe Material.STONE
                h.nativeWrites shouldBe listOf(first)

                h.arrived += second
                h.time += 5_000
                h.pulse()
                val resumedTerrace = h.terrain.getValue(second)
                (resumedTerrace.second !== terrace.second) shouldBe true
                resumedTerrace.first() shouldBe true
                resumedTerrace.second.complete(Unit)
                h.tick()
                h.order(second).workDone shouldBe 1
                h.order(second).payment?.reservedBatch shouldBe null
                h.payments shouldBe listOf(waiting)
            }
        }
    }

    test("a native success that beats the builder yield commits its paid row once") {
        MockBukkitTestRuntime.open().use { paper ->
            ConstructionSchedulingFixture(paper).use { h ->
                val city = h.addCity(1)
                h.pulse()
                val paste = h.pastes.getValue(city).single()
                paste.ready.asBoolean shouldBe false
                h.time += 30_000
                h.pulse()
                paste.cancellations shouldBe 1
                paste.completion.complete(ArcSchematicMutationResult(1, 0, false))
                h.tick()
                h.order(city).workDone shouldBe 1
                h.order(city).payment?.reservedBatch shouldBe null
                h.runtime.activeCount shouldBe 0
                h.payments shouldBe emptyList()
            }
        }
    }

    test("a native protection failure during a builder yield pauses the same paid row") {
        MockBukkitTestRuntime.open().use { paper ->
            ConstructionSchedulingFixture(paper).use { h ->
                val city = h.addCity(1)
                h.pulse()
                val paste = h.pastes.getValue(city).single()
                paste.ready.asBoolean shouldBe false
                h.time += 30_000
                h.pulse()
                paste.cancellations shouldBe 1
                paste.completion.completeExceptionally(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "Lands denied"))
                h.tick()
                h.order(city).phase shouldBe ConstructionPhase.PAUSED
                h.order(city).failure shouldBe "protected-area"
                h.order(city).workDone shouldBe 0
                h.order(city).payment?.reservedBatch shouldBe 0
                h.runtime.activeCount shouldBe 0
                h.payments shouldBe emptyList()
            }
        }
    }
})

private class ConstructionSchedulingFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val native = paper.addSimpleWorld("construction-yield")
    private val adapter = mockk<CivsWorld>(relaxed = true)
    private val blueprints = mockk<CivsBlueprints>()
    private val store = mockk<CivsStore>()
    private val citizens = mockk<CivsCitizens>(relaxed = true)
    private val cities = linkedMapOf<UUID, Settlement>()
    val arrived = mutableSetOf<UUID>()
    val payments = mutableListOf<UUID>()
    val validations = mutableListOf<UUID>()
    val nativeWrites = mutableListOf<UUID>()
    val pastes = mutableMapOf<UUID, MutableList<ConstructionNativePaste>>()
    val terrain = mutableMapOf<UUID, Pair<() -> Boolean, CompletableFuture<Unit>>>()
    var time = 10_000L
    val runtime = CivsConstructionRuntime(paper.createSimplePlugin("ConstructionScheduling"), tasks, adapter, blueprints,
        store, Executor(Runnable::run), mockk(relaxed = true), mockk(relaxed = true), citizens, mockk(relaxed = true),
        { cities }, emptySet(), emptySet(), { true }, { false }, { true }, { true }, { _, action -> action() },
        { id, update -> save(update(cities.getValue(id))) }, { _, _ -> error("Unexpected activation") },
        { city, order, cost ->
            cost shouldBe mapOf("STONE" to 1L)
            payments += city.id
            save(city.copy(construction = order.copy(phase = ConstructionPhase.PLACING,
                payment = CivConstructionPayment(reservedBatch = order.workDone))))
        }, {}, { time })

    init {
        every { citizens.constructionReady(any(), any()) } answers { firstArg<UUID>() in arrived }
        every { adapter.revalidate(any(), any()) } answers {
            validations += firstArg<CivPlacementPlan>().cityId
            CompletableFuture.completedFuture(Unit)
        }
        every { adapter.applyTerraform(any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture<Unit>().also { terrain[firstArg<CivPlacementPlan>().cityId] = arg<() -> Boolean>(4) to it }
        }
    }

    fun addCity(number: Int, terrain: Boolean = false, paid: Boolean = true): UUID {
        val anchor = CivPosition(number * 32, 64, 0)
        val upper = anchor.copy(y = 65)
        val building = CivBuilding(UUID(301, number.toLong()), BuildingType.HOUSE, 1, anchor, 0, "fixture/house",
            CivBox(anchor, upper), anchor, BuildingPhase.CONSTRUCTING)
        val city = settlementPlanCity().let { base ->
            base.copy(id = UUID(302, number.toLong()), worldId = native.uid, buildings = base.buildings + building,
                construction = CivConstruction(UUID(303, number.toLong()), building.id, 1, if (terrain) 3 else 2, 0,
                    if (paid) ConstructionPhase.PLACING else ConstructionPhase.WAITING_MATERIALS, Stock(),
                    plannedBuilding = building, batches = CivConstructionBatches(if (terrain) 3 else 2, 16),
                    materials = mapOf("STONE" to 2L), payment = CivConstructionPayment(reservedBatch = if (paid) 0 else null),
                    blocksRequired = if (terrain) 3 else 2, blocksPlaced = 0))
        }
        val order = requireNotNull(city.construction)
        val route = anchor.copy(x = anchor.x - 1)
        val earth = CivPlanBlock(route.copy(y = 63), "minecraft:air", "minecraft:dirt")
        val plan = CivPlacementPlan(order.id, city.id, city.worldId, building, listOf(
            CivPlanBlock(anchor, "minecraft:air", "minecraft:stone"), CivPlanBlock(upper, "minecraft:air", "minecraft:stone")),
            "a".repeat(64), if (terrain) CivTerraformPlan(CivBox(earth.position, earth.position),
                listOf(earth), listOf(route), 63, 1, 0) else null)
        val snapshot = mockk<ArcSchematicSnapshot>()
        every { store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { blueprints.pasteParts(snapshot, building, any(), any()) } returns CompletableFuture.completedFuture(listOf(snapshot))
        every { blueprints.paste(snapshot, native.uid, anchor, any(), any(), any()) } answers {
            val observe = arg<Consumer<ArcSchematicBlock>>(3)
            ConstructionNativePaste(arg(4)) {
                val block = native.getBlockAt(anchor.x, anchor.y, anchor.z)
                if (block.type != Material.STONE) {
                    block.type = Material.STONE
                    nativeWrites += city.id
                    observe.accept(ArcSchematicBlock(
                        ArcSchematicPoint(anchor.x, anchor.y, anchor.z), "minecraft:stone", false, false))
                }
            }.also { pastes.getOrPut(city.id, ::mutableListOf).add(it) }
        }
        native.loadChunk(anchor.x shr 4, anchor.z shr 4)
        cities[city.id] = city
        return city.id
    }

    private fun save(city: Settlement) = CompletableFuture.completedFuture(city.also { cities[it.id] = it })
    fun order(cityId: UUID) = requireNotNull(cities.getValue(cityId).construction)
    fun block(cityId: UUID): Material = requireNotNull(order(cityId).plannedBuilding).anchor.let { native.getBlockAt(it.x, it.y, it.z).type }
    fun pulse() { runtime.pulse(); tick() }
    fun tick() {
        repeat(12) {
            scheduler.tick()
            paper.performTicks(1)
        }
    }
    override fun close() { runtime.close(); tasks.close() }
}

private class ConstructionNativePaste(val ready: BooleanSupplier, private val writeBlock: () -> Unit) : ArcSchematicOperation {
    override val completion = CompletableFuture<ArcSchematicMutationResult>()
    var cancellations = 0
        private set
    fun write() {
        check(ready.asBoolean)
        writeBlock()
    }
    override fun cancel(): Boolean {
        cancellations++
        return !completion.isDone
    }
}
