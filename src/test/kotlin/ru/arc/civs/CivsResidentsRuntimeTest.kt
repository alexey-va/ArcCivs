package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.ChunkMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcPose
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

    test("a miner waiting for a tool walks from the hall to its quarry through an upward ingress step") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 66.0, .5))
            h.world.getBlockAt(0, 65, 0).type = Material.STONE
            h.world.getBlockAt(1, 66, 0).type = Material.STONE
            h.world.getBlockAt(2, 65, 0).type = Material.STONE
            h.world.getBlockAt(3, 64, 0).type = Material.STONE
            h.owner.pulse(2_000)
            val control = requireNotNull(quarry.control)
            val destination = Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)
            // Drain the real 1ms search budget without advancing the recovery timeout.
            // Cold mock block reads may need more calls, but every movement must stay continuous.
            for (index in 0 until 2_000) {
                val before = actor.location
                h.owner.activateNavigating()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L)
                (before.distance(actor.location) <= .215001) shouldBe true
                if (actor.location.distanceSquared(destination) <= 8.0 * 8.0) break
            }
            val at = actor.location
            (at.distanceSquared(destination) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an unreachable waiting miner recovers beside its quarry without completing work or returning to the hall") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 64.0, .5))
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.owner.pulse(2_000)
            var recovered = false
            repeat(80) { index ->
                val before = actor.location
                h.owner.activateNavigating()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L + (index + 1) * 50)
                if (before.distance(actor.location) > 8.0) recovered = true
            }
            val control = requireNotNull(quarry.control)
            val at = actor.location
            recovered shouldBe true
            (at.distanceSquared(Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.pendingDelivery.isDone shouldBe false
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
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), focus = focus,
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
            (actor.location.x > summoned.x) shouldBe true
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

    test("reachable work stays at the observed position faces the real block and rechecks LOS at receipt admission") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val observed = Location(h.world, 22.5, 64.0, .5)
            actor.teleport(observed)
            val focus = CivPosition(23, 63, 0)
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.work = work
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            repeat(20) { h.owner.activateNavigating() }
            actor.location shouldBe observed
            verify(atLeast = 1) { actor.swingMainHand() }
            verify(atLeast = 1) { actor.pitch = match { it > 45f } }
            h.owner.pulse(3_499)
            h.deliveries shouldBe 0
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            actor.location shouldBe observed
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)

            // A city wait must not let an already admitted action work through a newly placed wall.
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.world.getBlockAt(23, 64, 0).type = Material.AIR
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(Location(h.world, 27.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an out-of-reach worker approaches only until the actual block becomes reachable") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.owner.activateNavigating()
                (before.distance(actor.location) <= .215001) shouldBe true
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            citizenCanReachBlock(actor, focus) shouldBe true
            (actor.location.x > 21.5 && actor.location.x < 24.0) shouldBe true
            (actor.location.distance(Location(h.world, 24.5, 64.0, .5)) > .65) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
        }
    }

    test("a worker already at another side of its barrel does not route to the preferred side") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val otherSide = Location(h.world, 19.5, 64.0, .5)
            actor.teleport(otherSide)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            repeat(20) { h.owner.activateNavigating() }
            actor.location shouldBe otherSide
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe otherSide
        }
    }

    test("storage approach stops at the first usable side between coarse work pulses") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            repeat(160) { h.owner.activateNavigating() }
            val arrival = actor.location
            citizenAtStorage(h.world, CitizenPoint(arrival.x, arrival.y, arrival.z), requireNotNull(farm.control)) shouldBe true
            (arrival.x < 20.0) shouldBe true
            repeat(40) { h.owner.activateNavigating() }
            actor.location shouldBe arrival
            h.deliveries shouldBe 0
            h.owner.pulse(3_000)
            h.owner.pulse(4_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe arrival
        }
    }

    test("physical nudges preserve a pending receipt and stop at native walls") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.0, .5))
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            h.owner.pulse(4_000)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            actor.teleport(Location(h.world, 22.7, 64.0, .5))
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe false
            actor.location.x shouldBe 22.7
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
    var idleReason = "waiting-inputs"
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
            var npcScale = 1.0
            var npcPose = PacketNpcPose.STANDING
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { uniqueId } returns workerId
                every { isValid } answers { valid }
                every { location } answers { position.clone() }
                every { world } returns this@ResidentRuntime.world
                every { height } answers { 1.8 * npcScale }
                every { eyeHeight } answers { 1.62 * npcScale }
                every { scale } answers { npcScale }
                every { scale = any() } answers { npcScale = firstArg() }
                every { pose } answers { npcPose }
                every { pose = any() } answers { npcPose = firstArg() }
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
            workPlan = { _, _ -> work }, workIdleReason = { _, _ -> idleReason }, packetOwner = packets)
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    fun hit(amount: Double) = owner.damage(workerId, amount)
    fun assignFarm(): CivBuilding {
        val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
            "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)), CivPosition(20, 64, 0),
            BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
        for (z in -1..1) world.loadChunk(2, z)
        world.getBlockAt(20, 64, 0).type = Material.BARREL
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm,
            workers = city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
            inventory = CivInventory(CivStorageMode.PHYSICAL)))
        return farm
    }
    fun assignQuarry(): CivBuilding = assignFarm().copy(type = BuildingType.QUARRY, schematicId = "miner1", production = "COBBLESTONE").also { quarry ->
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
    }
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

    /** Pinned WorldMock omits this native API. This fixture contains only full cubes and air. */
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        val end = start.clone().add(direction.clone().normalize().multiply(maxDistance))
        var nearest: RayTraceResult? = null
        for (x in minOf(start.blockX, end.blockX)..maxOf(start.blockX, end.blockX))
            for (y in minOf(start.blockY, end.blockY)..maxOf(start.blockY, end.blockY))
                for (z in minOf(start.blockZ, end.blockZ)..maxOf(start.blockZ, end.blockZ)) {
                    val block = getBlockAt(x, y, z)
                    if (block.type.isAir) continue
                    val hit = block.boundingBox.rayTrace(start.toVector(), direction, maxDistance) ?: continue
                    if (nearest == null || hit.hitPosition.distanceSquared(start.toVector()) < nearest.hitPosition.distanceSquared(start.toVector()))
                        nearest = RayTraceResult(hit.hitPosition, block, hit.hitBlockFace)
                }
        return nearest
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
