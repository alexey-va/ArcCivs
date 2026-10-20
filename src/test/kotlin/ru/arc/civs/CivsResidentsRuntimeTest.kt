package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.ChunkMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

class CivsResidentsRuntimeTest : FunSpec({
    test("an accepted packet hit freezes work until durable injury and viewer retirement cannot heal it") {
        residentRuntime { h ->
            h.hit(7.0)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.hit(7.0)
            h.hits shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.visualHealth shouldBe 13.0
            h.viewing = false; h.owner.pulse(3_000)
            h.owner.activeCount() shouldBe 0
            h.viewing = true; h.owner.pulse(4_000)
            h.owner.activeCount() shouldBe 1
            h.visualHealth shouldBe 13.0
        }
    }

    test("durable death removes the worker and never creates a replacement packet actor") {
        residentRuntime { h ->
            h.hit(20.0)
            h.owner.activeCount() shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 0
            repeat(5) { h.owner.pulse(3_000L + it * 1_000) }
            h.spawns shouldBe 1
            h.city.workers shouldBe emptyList()
        }
    }

    test("unknown injury commit stays frozen until explicit canonical reconciliation") {
        residentRuntime { h ->
            h.hit(3.0)
            h.pending.completeExceptionally(IllegalStateException("unknown commit"))
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.owner.reconcileResidents()
            h.owner.pulse(3_000)
            h.visualHealth shouldBe 20.0
        }
    }

    test("summoned farmer starts a supplied work plan at the barrel and keeps its delivery receipt fenced") {
        residentRuntime { h ->
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
                "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)),
                CivPosition(20, 64, 0), BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
            for (z in -1..1) h.world.loadChunk(2, z) // Loaded search margin east of the farm's chunk.
            h.world.getBlockAt(20, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + farm,
                workers = h.city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
                inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.owner.pulse(2_000) // A waiting farmer may already have an idle route from the former position.
            h.owner.activeCount() shouldBe 1
            val actor = h.owner.collisionActors().single()
            val previous = actor.location
            h.owner.summon(h.city, farm.id) shouldBe true
            val summoned = actor.location
            (previous.distance(summoned) > 8.0) shouldBe true
            h.deliveries shouldBe 0

            // Publication of supplied seeds makes a new field plan available on the next pulse.
            h.city = h.city.copy(revision = h.city.revision + 1,
                inventory = requireNotNull(h.city.inventory).copy(buildingMaterials = mapOf(farm.id.toString() to mapOf("WHEAT_SEEDS" to 1L))))
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0), emptyList(),
                inputs = mapOf("WHEAT_SEEDS" to 1L), durationMillis = 1_500, status = "working-field")
            h.owner.pulse(3_000)
            actor.location shouldBe summoned
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.owner.activateNavigating()
                if (tick % 20 == 0) h.owner.pulse(3_000L + tick * 50)
                // A fresh plan walks from the summon, without a stale route or recovery jump.
                (before.distance(actor.location) <= .215001) shouldBe true
                (actor.location.x >= summoned.x) shouldBe true
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            h.spawns shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city.workers.single().completedCycles shouldBe 0
            h.city.inventory?.buildingStock(farm.id) shouldBe mapOf("WHEAT_SEEDS" to 1L)
            h.pendingDelivery.isDone shouldBe false
            h.owner.summon(h.city, farm.id) shouldBe false // Never abandon an admitted receipt.
            h.owner.pulse(12_000)
            h.deliveries shouldBe 1
        }
    }
})

private fun residentRuntime(block: (ResidentRuntime) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = ResidentRuntime(paper)
            try { block(fixture) } finally { fixture.owner.closeAsync().join() }
        }
    }
}

private class ResidentRuntime(paper: MockBukkitTestRuntime) {
    private val plugin = paper.createSimplePlugin("ResidentsTest")
    val world = ResidentRuntimeWorld().also(paper.server::addWorld)
    private val packets = mockk<PaperPacketNpcs>(relaxed = true)
    val workerId = UUID.randomUUID()
    var viewing = true
    var spawns = 0
    var hits = 0
    var deliveries = 0
    var work: CivWorldWork? = null
    val pendingDelivery = CompletableFuture<Settlement>()
    val pending = CompletableFuture<Settlement>()
    private var damage = 0.0
    var city: Settlement
    val owner: CivsCitizens
    val visualHealth: Double get() = owner.hologramSnapshots().single().health

    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        val viewer = paper.addPlayer("ResidentViewer")
        viewer.teleport(Location(world, .5, 64.0, .5))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
            "townhall1", CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), viewer.uniqueId, "Town", world.uid, CivPosition(0, 64, 0),
            96, "nordic", Stock(), listOf(hall), listOf(CivWorker(workerId, "Resident", null))))
        every { packets.spawnPlayer(any(), any()) } answers {
            spawns++
            var position = firstArg<Location>().clone()
            var valid = true
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { isValid } answers { valid }
                every { location } answers { position.clone() }
                every { world } returns this@ResidentRuntime.world
                every { height } returns 1.8
                every { eyeHeight } returns 1.62
                every { teleport(any()) } answers { position = firstArg<Location>().clone(); Unit }
                every { remove() } answers { valid = false; Unit }
            }
        }
        every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
        owner = CivsCitizens(plugin, { if (viewing) listOf(city) else emptyList() },
            { _, _, _, _, _, _, _ ->
                check(work != null) { "idle resident cannot deliver" }
                deliveries++
                pendingDelivery
            },
            { _, _, _ -> }, hurt = { _, _, hit -> hits++; damage = hit; pending },
            workPlan = { _, _ -> work }, workIdleReason = { _, _ -> "waiting-inputs" }, packetOwner = packets)
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    fun hit(amount: Double) = owner.damage(workerId, amount)
    fun commitHit() {
        city = CivsRules.hurtResident(city, city.owner, workerId, damage)
        pending.complete(city)
    }
}

/** A stone plane with no tile entities. Only pinned MockBukkit's missing terrain queries are supplied. */
private class ResidentRuntimeWorld : WorldMock(Material.STONE, 63) {
    private val cells = mutableMapOf<Coordinate, BlockMock>()
    private val chunkFixtures = mutableMapOf<Pair<Int, Int>, ChunkMock>()
    init { setName("resident-runtime") }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))
    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            override fun isPassable(): Boolean = type.isAir
            override fun getBoundingBox(): BoundingBox = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(),
                x + 1.0, y + if (type.isAir) 0.0 else 1.0, z + 1.0)
            override fun getCollisionShape(): VoxelShape = if (type.isAir) EMPTY_SHAPE else FULL_SHAPE
        }
    }

    override fun getChunkAt(x: Int, z: Int, generate: Boolean): ChunkMock {
        val native = super.getChunkAt(x, z, generate)
        return chunkFixtures.getOrPut(x to z) {
            spyk(native).also { chunk ->
                every { chunk.getTileEntities(any<Predicate<Block>>(), false) } returns emptyList<BlockState>()
            }
        }
    }

    companion object {
        private fun shape(boxes: List<BoundingBox>) = object : VoxelShape {
            override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
            override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
        }
        private val EMPTY_SHAPE = shape(emptyList())
        private val FULL_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
    }
}
