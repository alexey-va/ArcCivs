package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsRoadMaintenanceStaffingTest : FunSpec({
    test("five existing professions borrow only the keeper after a native gap and an ordinary durable assignment") {
        MaintenanceStaffingFixture().use { h ->
            val before = h.city
            h.city.workers.size shouldBe 5
            h.city.construction shouldBe null
            h.city.constructionQueue.orEmpty() shouldBe emptyList()
            CivsAutonomy.missingBuilderForAcceptedWork(h.city) shouldBe false
            CivsAutonomy.criticalAssignment(h.city) { true } shouldBe null
            h.pulse()
            h.saves shouldBe 0
            h.observe()
            h.city shouldBe before
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.maintenance.maintenanceAssignment(h.city) { true } shouldBe (h.keeperId to h.hut.id)
            h.maintenance.admitDevelopment(h.city) shouldBe false
            // Citizen planning before the save still sees the real tavern role and cannot execute a preview.
            h.maintenance.plan(h.city, h.keeper()) shouldBe null
            h.pulse()
            h.saves shouldBe 1
            h.city shouldBe before
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.flush()
            h.keeper().assignment shouldBe h.hut.id
            h.city.workers.filterNot { it.id == h.keeperId } shouldBe before.workers.filterNot { it.id == h.keeperId }
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.city.constructionQueue shouldBe before.constructionQueue
            val work = requireNotNull(h.maintenance.plan(h.city, h.keeper()))
            work.upkeep shouldBe h.target
            work.inputs shouldBe mapOf("COBBLESTONE" to 10L)
            h.maintenance.requirements(h.city) shouldBe mapOf(h.keeperId to work.inputs)
            val assigned = h.city
            repeat(3) { h.pulse(); h.flush() }
            h.city shouldBe assigned
            h.saves shouldBe 1
        }
    }

    for (state in listOf("healthy", "unsafe", "unloaded", "no-capture", "unfundable"))
    test("$state native observation creates no assignment supply request or city save") {
        MaintenanceStaffingFixture().use { h ->
            when (state) {
                "healthy" -> h.paintHealthy()
                "unsafe" -> h.world.getBlockAt(0, 64, 0).type = Material.WATER
                "unloaded" -> h.world.unloadChunk(0, 0)
                "no-capture" -> h.captureAllowed = false
                "unfundable" -> h.payment = false
            }
            val before = h.city
            h.observe(); h.pulse(); h.flush()
            h.maintenance.maintenanceAssignment(h.city) { true } shouldBe null
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.maintenance.admitDevelopment(h.city) shouldBe true
            h.city shouldBe before
            h.saves shouldBe 0
        }
    }

    test("workerless probes use the existing eight column budget and wait five seconds before another pass") {
        MaintenanceStaffingFixture().use { h ->
            h.captureAllowed = false
            h.maintenance.publish(h.city, h.network.copy(targets = (0..15).map { h.target.copy(floor = h.target.floor.copy(x = it)) }))
            h.observe()
            h.probes shouldBe 8
            for (tick in 1..99) { h.tick = tick; h.observe() }
            h.probes shouldBe 8
            h.tick = 100
            h.observe()
            h.probes shouldBe 16
            h.maintenance.requirements(h.city) shouldBe emptyMap()
        }
    }

    test("fixed city iteration cannot put an unprobed second town to sleep behind a healthy first town") {
        MaintenanceStaffingFixture().use { h ->
            val (other, network) = h.addTown()
            h.captureCity = other.id
            h.maintenance.publish(h.city, h.network.copy(targets = (0..15).map { h.target.copy(floor = h.target.floor.copy(x = it)) }))
            h.maintenance.publish(other, network)
            var assigned: Pair<UUID, UUID>? = null
            for (tick in 0..99) {
                h.tick = tick
                h.observe() // Healthy town A always runs first and initially consumes all eight probes.
                h.maintenance.backfillSurfaces(other) { true }
                assigned = h.maintenance.maintenanceAssignment(other) { true }
                if (assigned != null) break
            }
            withClue("native probes per tick ${h.probesByTick}") {
                assigned shouldBe (other.workers.first().id to other.buildings.single { it.type == BuildingType.BUILDER_HUT }.id)
                h.probesByTick.values.all { it <= 8 } shouldBe true
                h.maintenance.requirements(other) shouldBe emptyMap()
            }
        }
    }

    for (state in listOf("cargo", "pending-stock", "collected", "child", "cannot-reassign"))
    test("a keeper with $state cannot be borrowed and each last essential producer stays assigned") {
        MaintenanceStaffingFixture().use { h ->
            val keeper = h.keeper()
            val guarded = when (state) {
                "cargo" -> keeper.copy(cargo = CivCargo(UUID(0, 90), h.depot.id, h.depot.id, h.hut.id,
                    h.depot.entrance, "COBBLESTONE", 1, inventoryBacked = true))
                "pending-stock" -> keeper.copy(pendingStock = Stock(stone = 1))
                "collected" -> keeper.copy(hasCollectedItems = true)
                "child" -> keeper.copy(childMinutes = 1)
                else -> keeper
            }
            h.city = h.city.copy(workers = h.city.workers.map { if (it.id == keeper.id) guarded else it })
            if (state == "cannot-reassign") h.blocked += keeper.id
            h.observe()
            h.probes shouldBe 0
            h.maintenance.maintenanceAssignment(h.city) { it !in h.blocked } shouldBe null
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.city.workers.filterNot { it.id == keeper.id }.forEach { worker ->
                CivsAutonomy.criticalAssignment(h.city, upkeepBuilderNeeded = true) { it == worker.id } shouldBe null
            }
        }
    }

    test("busy city and a newly protected keeper cannot pass the ordinary mutation recheck") {
        MaintenanceStaffingFixture().use { h ->
            h.observe()
            h.busy += h.city.id
            h.pulse()
            h.saves shouldBe 0
            h.busy.clear()
            h.beforeMutation = { h.blocked += h.keeperId }
            h.pulse()
            h.maintenance.maintenanceAssignment(h.city) { it !in h.blocked } shouldBe null
            h.saves shouldBe 0
            h.keeper().assignment shouldBe h.tavern.id
        }
    }

    for (state in listOf("owner", "world", "source", "cycle", "paused", "deleted", "network", "unfundable"))
    test("a pending claim releases its hold on $state staleness") {
        MaintenanceStaffingFixture().use { h ->
            h.observe()
            h.maintenance.holdsAssignment(h.city, h.keeperId) shouldBe true
            when (state) {
                "owner" -> h.city = h.city.copy(owner = UUID(0, 100))
                "world" -> h.city = h.city.copy(worldId = UUID(0, 101))
                "source" -> h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == h.target.source.id) it.copy(level = 2) else it })
                "cycle" -> h.city = h.city.copy(workers = h.city.workers.map { if (it.id == h.keeperId) it.copy(completedCycles = 1) else it })
                "paused" -> h.city = h.city.copy(autonomy = CivAutonomy(paused = true))
                "deleted" -> h.city = h.city.copy(pendingDeletion = CivDeletion(null))
                "network" -> h.maintenance.publish(h.city, h.network.copy(targets = emptyList()))
                "unfundable" -> h.payment = false
            }
            h.maintenance.maintenanceAssignment(h.city) { true } shouldBe null
            h.maintenance.holdsAssignment(h.city, h.keeperId) shouldBe false
            h.maintenance.requirements(h.city) shouldBe emptyMap()
        }
    }

    for (failure in listOf("recapture", "route"))
    test("$failure failure releases the selected hold and blocks immediate reclamation of the same unreachable column") {
        MaintenanceStaffingFixture().use { h ->
            h.observe(); h.pulse(); h.flush()
            if (failure == "recapture") h.captureAllowed = false
            val work = h.maintenance.plan(h.city, h.keeper())
            if (failure == "route") {
                requireNotNull(work)
                h.maintenance.invalidate(h.keeperId) // Existing Service workPositionFailed callback.
            } else work shouldBe null
            h.maintenance.holdsAssignment(h.city, h.keeperId) shouldBe false
            h.maintenance.requirements(h.city) shouldBe emptyMap()
            h.maintenance.plan(h.city, h.keeper()) shouldBe null
            h.pulse(); h.flush()
            h.keeper().assignment shouldBe h.tavern.id
            val probes = h.probes
            for (tick in 1..99) { h.tick = tick; h.observe(); h.pulse(); h.flush() }
            h.probes shouldBe probes
            h.saves shouldBe 2
        }
    }

    test("a completed claimed column returns the keeper once and healthy later probes do not bounce its role") {
        MaintenanceStaffingFixture().use { h ->
            h.observe(); h.pulse(); h.flush()
            val work = requireNotNull(h.maintenance.plan(h.city, h.keeper()))
            // The existing paid receipt owns the debit; this test exercises only its ordinary retirement handoff.
            h.city = CivsRoadMaintenance.complete(h.city, work, 3_000, nativePaid = true)
            work.changes.forEach { h.world.getBlockAt(it.position.x, it.position.y, it.position.z).setBlockData(Bukkit.createBlockData(it.after), false) }
            h.persistCurrent()
            h.maintenance.upkeepRetired(h.city, requireNotNull(work.upkeep))
            h.maintenance.holdsAssignment(h.city, h.keeperId) shouldBe false
            h.pulse(); h.flush()
            h.keeper().assignment shouldBe h.tavern.id
            val after = h.city
            for (tick in 1..300) { h.tick = tick; h.observe(); h.pulse(); h.flush() }
            h.city shouldBe after
            h.saves shouldBe 2
            h.maintenance.requirements(h.city) shouldBe emptyMap()
        }
    }

    test("the real resident personal meal guard prevents the prospective keeper probe") {
        residentRuntime { h ->
            try {
                val keeper = h.standingTavernkeeper()
                h.acceptTavernConstruction()
                h.city = h.city.copy(constructionQueue = null, workers = h.city.workers.map {
                    if (it.id == keeper) it.copy(hunger = 14, inventory = mapOf("BREAD" to 1L)) else it
                })
                h.owner.residentLifeEnabled = true
                h.owner.pulse(5_000)
                h.owner.canReassign(h.city.id, keeper) shouldBe false
                h.city = h.city.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), stock = Stock())
                val native = mockk<CivsWorld>()
                val maintenance = CivsRoadMaintenance(native) { 0 }
                val source = h.city.buildings.first()
                val floor = source.entrance.copy(y = source.entrance.y - 1)
                val target = CivRoadUpkeep(h.city.id, h.city.worldId, h.city.owner, source.id, source, floor,
                    "minecraft:dirt_path", listOf(source.entrance), emptyList())
                maintenance.publish(h.city, CivRoadUpkeepNetwork(h.city.id, h.city.worldId, h.city.buildings, listOf(target)))
                maintenance.backfillSurfaces(h.city) { it == keeper && h.owner.canReassign(h.city.id, it) }
                verify(exactly = 0) { native.captureRoadUpkeep(any(), any(), any()) }
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }
})

private class MaintenanceStaffingFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("MaintenanceStaffing")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val world = CivsWorksiteWorldFixture("maintenance-staffing", ground = Material.AIR, collisionForData = { data ->
        if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
    }).also(paper.server::addWorld)
    private val cities = linkedMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.getValue(UUID(211, 20))
        set(value) { cities[value.id] = value }
    val busy = mutableSetOf<UUID>()
    val blocked = mutableSetOf<UUID>()
    var tick = 0
    var probes = 0
    val probesByTick = mutableMapOf<Int, Int>()
    var saves = 0
    var beforeMutation: () -> Unit = {}
    var captureAllowed = true
    var captureCity: UUID? = null
    var payment: Boolean? = true
    val keeperId = UUID(211, 10)
    val hut: CivBuilding
    val tavern: CivBuilding
    val depot: CivBuilding
    val target: CivRoadUpkeep
    val network: CivRoadUpkeepNetwork
    private val native = CivsRoadWorld(plugin, { cities.values.toList() }, { _, _, _ -> true }, { false })
    private val adapter = mockk<CivsWorld>()
    val maintenance = CivsRoadMaintenance(adapter, paymentPossible = { payment }, currentTick = { tick })
    private val io = ArrayDeque<Runnable>()
    private val store = CivsStore(Files.createTempDirectory("maintenance-staffing-"), Executor { io += it })
    private val citizens = mockk<CivsCitizens>()
    private val life: CivsResidentLifeRuntime
    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        fun building(id: Long, type: BuildingType, x: Int, z: Int) = CivBuilding(UUID(211, id), type, 1,
            CivPosition(x, 63, z), 0, "fixture/$type", CivBox(CivPosition(x - 1, 63, z - 1), CivPosition(x + 1, 68, z + 1)),
            CivPosition(x, 64, z + 2), BuildingPhase.ACTIVE, control = CivPosition(x, 64, z))
        val hall = building(1, BuildingType.TOWN_HALL, -60, -60).copy(level = 3, entrance = CivPosition(0, 69, 0))
        hut = building(2, BuildingType.BUILDER_HUT, -10, 30)
        tavern = building(3, BuildingType.TAVERN, -20, 20)
        val farm = building(4, BuildingType.FARM, -30, 20)
        val forester = building(5, BuildingType.FORESTER, 20, 20)
        val quarry = building(6, BuildingType.QUARRY, 40, 20)
        depot = building(7, BuildingType.WAREHOUSE, -40, 20)
        val jobs = listOf(tavern, farm, forester, quarry, depot)
        city = CivsRules.validate(Settlement(UUID(211, 20), UUID(211, 21), "Staffing", world.uid, CivPosition(0, 64, 0), 96,
            "fixture", Stock(), listOf(hall, hut) + jobs, jobs.mapIndexed { index, job ->
                CivWorker(UUID(211, 10L + index), "Adult $index", job.id, nextDeliveryAt = 1, hunger = 20)
            }, autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
        val plan = CivPlacementPlan(hall.id, city.id, city.worldId, hall, emptyList(), terrain = CivTerraformPlan(
            CivBox(CivPosition(0, 68, 0), CivPosition(0, 70, 0)), emptyList(), listOf(hall.entrance), hall.anchor.y, 0, 0))
        network = CivsRoadMaintenance.prepare(city, listOf(plan), proposeCuts = false,
            restrictTo = setOf(civsTerrainKey(0, 0)))
        target = network.targets.single { it.floor == CivPosition(0, 68, 0) }
        world.getBlockAt(0, 58, 0).type = Material.GRASS_BLOCK
        world.getBlockAt(-1, 65, 0).type = Material.GRASS_BLOCK
        every { adapter.captureRoadUpkeep(any(), any(), any()) } answers {
            probes++
            probesByTick.merge(tick, 1, Int::plus)
            if (captureAllowed && (captureCity == null || firstArg<Settlement>().id == captureCity))
                native.captureRoadUpkeep(firstArg(), secondArg(), thirdArg()) else null
        }
        every { citizens.canReassign(any(), any()) } answers { secondArg<UUID>() !in blocked }
        persistCurrent() // Ordinary assignment starts from an existing durable revision zero.
        maintenance.publish(city, network)
        life = CivsResidentLifeRuntime(plugin, tasks, citizens, mockk(), 48, { cities }, busy, emptySet(), { true },
            { _, action -> action() }, { _, action -> action() }, { _, transform ->
                beforeMutation()
                val next = transform(city)
                if (next == city) CompletableFuture.completedFuture(city) else {
                    saves++
                    busy += city.id
                    store.save(next).thenApply { saved -> city = saved; busy -= saved.id; saved }
                }
            }, { tick * 50L }, maintenanceAssignment = maintenance::maintenanceAssignment, maintenanceHeld = maintenance::holdsAssignment)
    }
    fun keeper() = city.workers.single { it.id == keeperId }
    fun observe() = maintenance.backfillSurfaces(city) { it !in blocked }
    fun pulse() { life.pulseAutoAssignments(); paper.performTicks(1) }
    fun flush() { while (io.isNotEmpty()) io.removeFirst().run(); paper.performTicks(1) }
    fun persistCurrent() {
        val saving = store.save(city)
        while (io.isNotEmpty()) io.removeFirst().run()
        saving.join() shouldBe city
    }
    fun paintHealthy() {
        val work = requireNotNull(native.captureRoadUpkeep(city, keeper().copy(assignment = hut.id), target))
        work.changes.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).setBlockData(Bukkit.createBlockData(it.after), false) }
    }
    fun addTown(): Pair<Settlement, CivRoadUpkeepNetwork> {
        fun shifted(p: CivPosition) = p.copy(x = p.x + 256)
        val ids = city.buildings.associate { it.id to UUID(212, it.id.leastSignificantBits) }
        val other = city.copy(id = UUID(212, 20), owner = UUID(212, 21), center = shifted(city.center),
            buildings = city.buildings.map { b -> b.copy(id = ids.getValue(b.id), anchor = shifted(b.anchor),
                bounds = CivBox(shifted(b.bounds.min), shifted(b.bounds.max)), entrance = shifted(b.entrance), control = b.control?.let(::shifted)) },
            workers = city.workers.map { it.copy(id = UUID(212, it.id.leastSignificantBits), assignment = ids[it.assignment]) })
        cities[other.id] = other
        val hall = other.buildings.first()
        val plan = CivPlacementPlan(hall.id, other.id, other.worldId, hall, emptyList(), terrain = CivTerraformPlan(
            CivBox(CivPosition(256, 68, 0), CivPosition(256, 70, 0)), emptyList(), listOf(hall.entrance), hall.anchor.y, 0, 0))
        for (x in 15..17) for (z in -1..1) world.loadChunk(x, z)
        world.getBlockAt(256, 58, 0).type = Material.GRASS_BLOCK
        world.getBlockAt(255, 65, 0).type = Material.GRASS_BLOCK
        return other to CivsRoadMaintenance.prepare(other, listOf(plan), proposeCuts = false,
            restrictTo = setOf(civsTerrainKey(256, 0)))
    }
    override fun close() { flush(); store.close(); tasks.close(); paper.close() }
}
