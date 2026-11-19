package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs

class CivsLocalInventoryServiceTest : FunSpec({
    test("bread pickup, wheat harvest, and return to the farm barrel advance the same city before storage runs") {
        localInventoryScenario { h, disk ->
            val field = LocalFarm(h)
            val city = field.city
            disk.store.save(city).join()
            h.start()

            val checkpoint = disk.holdCheckpoint()
            val supplied = h.await(field.pickup())
            supplied.revision shouldBe city.revision + 1
            CivsEquipment.plainCount(supplied.workers.first(), "BREAD") shouldBe 4
            supplied.workers.last() shouldBe field.farmer
            field.barrel.getItem(0) shouldBe null
            field.barrel.getItem(1) shouldBe ItemStack(Material.STONE, 64)

            val work = field.findHarvest()
            val harvested = h.await(field.deliver())
            harvested.revision shouldBe city.revision + 2
            harvested.workers.first() shouldBe supplied.workers.first()
            val farmer = harvested.workers.last()
            farmer.completedCycles shouldBe field.farmer.completedCycles + 1
            CivsEquipment.plainCount(farmer, "WHEAT") shouldBe 1
            CivsEquipment.plainCount(farmer, "WHEAT_SEEDS") shouldBe 1
            CivsEquipment.plainCount(harvested.workers.first(), "WHEAT") shouldBe 0
            harvested.stock shouldBe city.stock
            field.crop.blockData.asString shouldBe work.changes.single().after
            field.barrel.getItem(1) shouldBe ItemStack(Material.STONE, 64)

            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(field.deliver()) })
                ?.reason shouldBe "stale-delivery"
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(field.pickup()) })
                ?.reason shouldBe "resident-food-missing"
            h.service.settlements().single() shouldBe harvested
            checkpoint.isDone shouldBe false
            disk.checkpointed() shouldBe city

            val returned = field.confirmOutputReturn(harvested, farmer)
            returned.revision shouldBe city.revision + 3
            returned.workers.first() shouldBe supplied.workers.first()
            val carrying = returned.workers.last()
            carrying.completedCycles shouldBe farmer.completedCycles
            CivsEquipment.plainCount(carrying, "WHEAT") shouldBe 0
            CivsEquipment.plainCount(carrying, "WHEAT_SEEDS") shouldBe 1
            returned.stock shouldBe city.stock
            checkpoint.isDone shouldBe false

            disk.resume()
            checkpoint.join()
            h.tick()
            h.service.settlements().single() shouldBe returned
            disk.checkpointed() shouldBe returned
        }
    }

    for (native in listOf("changed", "already replanted", "unloaded")) test("a harvest cannot credit $native native work") {
        localInventoryScenario { h, disk ->
            val field = LocalFarm(h)
            disk.store.save(field.city).join()
            h.start()

            val checkpoint = disk.holdCheckpoint()
            val work = field.findHarvest()
            val crop = work.changes.single()
            var arrivalChecks = 0
            every { h.citizens.requireWorldWorkArrival(field.city.id, field.farmer.id, work) } answers {
                // Change the exact cell after its last service validation to exercise the native write fence.
                if (++arrivalChecks == 2) when (native) {
                    "unloaded" -> h.world.unloadChunk(field.ripe.x shr 4, field.ripe.z shr 4) shouldBe true
                    "already replanted" -> field.crop.blockData = Bukkit.createBlockData(crop.after)
                    else -> field.crop.type = Material.STONE
                }
            }
            val failure = shouldThrow<CompletionException> { h.await(field.deliver()) }
            CivsDiagnostics.rejection(failure)?.values?.get("reason") shouldBe
                (if (native == "unloaded") "chunk-unloaded" else "world-cell-drift")
            arrivalChecks shouldBe 2
            when (native) {
                "unloaded" -> {
                    h.world.isChunkLoaded(field.ripe.x shr 4, field.ripe.z shr 4) shouldBe false
                    field.crop.blockData.asString shouldBe crop.before
                }
                "already replanted" -> field.crop.blockData.asString shouldBe crop.after
                else -> field.crop.type shouldBe Material.STONE
            }
            h.service.settlements().single() shouldBe field.city

            val supplied = h.await(field.pickup())
            supplied.revision shouldBe field.city.revision + 1
            supplied.workers.last() shouldBe field.farmer
            CivsEquipment.plainCount(supplied.workers.first(), "BREAD") shouldBe 4
            field.barrel.getItem(0) shouldBe null

            val returned = h.await(field.returnBucket())
            returned.revision shouldBe field.city.revision + 2
            returned.workers.last() shouldBe field.farmer
            CivsEquipment.plainCount(returned.workers.first(), "BUCKET") shouldBe 0
            CivsEquipment.plainCount(returned.workers.first(), "BREAD") shouldBe 4
            field.barrel.getItem(0) shouldBe ItemStack(Material.BUCKET)
            checkpoint.isDone shouldBe false
            disk.resume()
            checkpoint.join()
            disk.checkpointed() shouldBe returned
        }
    }
})

private class LocalFarm(private val h: ServiceScenario) {
    private val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
    private val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0)).copy(production = "WHEAT")
    private val resident = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10,
        items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("BUCKET", 1) else null })
    val farmer = spatialWorker(farm, "IRON_HOE")
    val city = CivsRules.validate(h.city.copy(buildings = listOf(hall, farm), workers = listOf(resident, farmer),
        stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
    private val source = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(0, 64, 1))
    val barrel = h.world.getBlockAt(source.container.x, source.container.y, source.container.z)
        .also { it.type = Material.BARREL }.let { (it.state as Barrel).inventory }
    private val control = requireNotNull(farm.control)
    private val output = h.world.getBlockAt(control.x, control.y, control.z)
        .also { it.type = Material.BARREL }.let { (it.state as Barrel).inventory }
    private val plots = CivsWorksiteLayouts.field(farm)
    val ripe = plots[20].let { it.copy(y = it.y + 1) }
    val crop get() = h.world.getBlockAt(ripe.x, ripe.y, ripe.z)
    private val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
        .get(h.service) as CivsWorksites
    private val commit = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
        UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
        .also { it.isAccessible = true }

    init {
        for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
        h.world.time = 6_000
        plots.forEach { soil ->
            h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
            h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=2]")
        }
        CivsWorksiteLayouts.irrigation(farm).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
        crop.blockData = Bukkit.createBlockData("minecraft:wheat[age=7]")
        barrel.setItem(0, ItemStack(Material.BREAD, 4))
        barrel.setItem(1, ItemStack(Material.STONE, 64))
        every { h.citizens.residentCollectionFeet(resident.id, false) } returns source.standing.location(h.world)
        // Use the native plan and shape budget without depending on host or MockBukkit speed.
        CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }.set(worksites, { 0L })
    }

    fun pickup() = h.service.pickupResidentFood(city.id, resident.id, source)
    fun returnBucket() = h.service.returnResidentOutputs(city.id, resident.id, hall.id)

    fun findHarvest(): CivWorldWork {
        val current = h.service.settlements().single()
        h.tickUntil("farm production order") { h.service.productionOrder(current, farmer)?.allows("WHEAT") == true }
        repeat(16) { worksites.pulse(listOf(current)) }
        return requireNotNull(worksites.plan(current, farmer)).also {
            it.focus shouldBe ripe
            it.outputs shouldBe mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 1L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun deliver() = commit.invokeService(h.service, city.id, farmer.id, farm.id, farmer.completedCycles,
        farmer.nextDeliveryAt, null, h.time) as CompletableFuture<Settlement>

    fun confirmOutputReturn(harvested: Settlement, worker: CivWorker): Settlement {
        val packets = mockk<PaperPacketNpcs>(relaxed = true)
        every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
        return CivsCitizens(h.plugin, h.service::settlements, { _, _, _, _, _, _, _ -> error("Unexpected work") },
            { _, _, _ -> }, packetOwner = packets, navigationTasks = h.tasks,
            navigationExecutor = Executor(Runnable::run)).use { citizens ->
            var feet = control.copy(x = control.x + 8).location(h.world)
            val npc = mockk<PacketPlayerNpc>(relaxed = true)
            every { npc.isValid } returns true
            every { npc.world } returns h.world
            every { npc.location } answers { feet }
            every { npc.eyeHeight } returns 1.5
            val actor = CitizenActor(city.id, worker.id, city.worldId, npc, requireNotNull(citizenPlan(harvested, worker)))
            actor.supplyPickup = CitizenSupplyPickup(UUID.randomUUID(), farm.id, resumeWork = false, returnOutputs = true)
            val runtime = CivsCitizens::class.java.getDeclaredField("runtime").also { it.isAccessible = true }.get(citizens) as CitizenRuntime
            runtime.actors[worker.id] = actor
            every { h.citizens.requireStorageArrival(city.id, worker.id, farm.id) } answers {
                citizens.requireStorageArrival(city.id, worker.id, farm.id)
            }

            fun returnOutput() = h.await(h.service.returnResidentOutputs(city.id, worker.id, farm.id))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { returnOutput() })?.reason shouldBe "route-unreachable"
            h.service.settlements().single() shouldBe harvested
            output.getItem(0) shouldBe null

            feet = requireNotNull(citizenCollectedHomeTarget(harvested, farm, h.world, control)).location(h.world)
            repeat(output.size) { output.setItem(it, ItemStack(Material.STONE, 64)) }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { returnOutput() })?.reason shouldBe "storage-full"
            h.service.settlements().single() shouldBe harvested
            output.getItem(0) shouldBe ItemStack(Material.STONE, 64)

            repeat(productionOutputHeadroom(farm.type) + 1) { output.setItem(it, null) }
            val returned = returnOutput()
            output.getItem(0) shouldBe ItemStack(Material.WHEAT)
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { returnOutput() })?.reason shouldBe "storage-full"
            output.getItem(0) shouldBe ItemStack(Material.WHEAT)
            h.service.settlements().single() shouldBe returned
            returned
        }
    }
}

internal fun localInventoryScenario(action: (ServiceScenario, InventoryCheckpointFixture) -> Unit) {
    val directory = Files.createTempDirectory("civs-local-inventory-")
    try {
        InventoryCheckpointFixture(directory).use { disk ->
            serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture(), actualStore = disk.store) { h ->
                action(h, disk)
            }
        }
    } finally { directory.toFile().deleteRecursively() }
}

internal class InventoryCheckpointFixture(private val directory: Path) : Executor, AutoCloseable {
    val store = CivsStore(directory, this)
    private var held: ArrayDeque<Runnable>? = null

    override fun execute(command: Runnable) {
        held?.addLast(command) ?: command.run()
    }

    fun holdCheckpoint(): CompletableFuture<Unit> {
        check(held == null)
        held = ArrayDeque()
        return store.flush()
    }

    fun resume() {
        val pending = requireNotNull(held)
        held = null
        pending.forEach(Runnable::run)
    }

    fun checkpointed() = CivsStore(directory, Executor(Runnable::run)).use { it.load().join().single() }

    override fun close() {
        if (held != null) resume()
        store.close()
        store.drained().join()
    }
}
