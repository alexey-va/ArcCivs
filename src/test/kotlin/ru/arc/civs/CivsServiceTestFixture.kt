package ru.arc.civs

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.RayTraceResult
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

internal fun serviceScenario(empty: Boolean = false, maximumCities: Int = 500, worldName: String = "vanilla",
    allowedWorlds: Set<String> = setOf("vanilla"), storageFactory: ((org.bukkit.plugin.Plugin) -> CivsStorage)? = null,
    nativeGeometry: Boolean = false, serverFixture: ServerMock? = null,
    action: (ServiceScenario) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        val runtime = if (serverFixture == null) MockBukkitTestRuntime.open() else MockBukkitTestRuntime.open(serverFixture)
        runtime.use { paper -> ServiceScenario(paper, empty, maximumCities, worldName, allowedWorlds, storageFactory, nativeGeometry).use(action) }
    }

internal class ServiceScenario(val paper: MockBukkitTestRuntime, empty: Boolean, maximumCities: Int,
    worldName: String, allowedWorlds: Set<String>, storageFactory: ((org.bukkit.plugin.Plugin) -> CivsStorage)?, nativeGeometry: Boolean) : AutoCloseable {
    val plugin = paper.createSimplePlugin("CivsServiceTest")
    val world = if (nativeGeometry) CivsWorksiteWorldFixture(worldName).also(paper.server::addWorld) else paper.addSimpleWorld(worldName)
    val player = mockk<Player>(relaxed = true)
    val store = mockk<CivsStore>().also { it.stubCheckpointAcknowledgements() }
    val blueprints = mockk<CivsBlueprints>()
    val worldAdapter = mockk<CivsWorld>(relaxed = true)
    val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
    val citizens = mockk<CivsCitizens>(relaxed = true)
    val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
    val transfer = mockk<BackendTransfer>()
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val saves = mutableListOf<Pair<Settlement, CompletableFuture<Settlement>>>()
    val owner = UUID.randomUUID()
    var time = System.currentTimeMillis()
    val city: Settlement
    val service: CivsService

    init {
        every { worldAdapter.canModify(any(), any(), any()) } returns true
        every { worldAdapter.permitsWaterFoundationMutation(any(), any(), any()) } answers { callOriginal() }
        every { worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(null)
        every { worldAdapter.captureSite(any(), any(), any(), any(), any(), any(), isNull(), any()) } answers {
            worldAdapter.capture(firstArg(), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7))
        }
        every { blueprints.pasteParts(any(), any(), isNull()) } answers { CompletableFuture.completedFuture(listOf(firstArg())) }
        every { blueprints.availableStyles(any()) } answers {
            when (firstArg<BuildingType?>()) {
                BuildingType.PLAN_BOARD -> listOf("plan-board")
                BuildingType.DECORATION -> emptyList()
                else -> CivsBlueprints.STYLES
            }
        }
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
            val building = firstArg<CivBuilding>()
            check(!building.schematicId.startsWith("civs/v2/")) { "Catalog upgrades need an explicit fixture" }
            val style = CivsBlueprints.styleOf(building) ?: throw CivsRejection("invalid-style")
            blueprints.prepare(style, building.type, arg(1), building.id, arg(2), building.anchor,
                building.rotation / 90, building.rotation, building.entrance)
        }
        every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        every { boundary.drained() } returns CompletableFuture.completedFuture(null)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        every { citizens.autonomousVisualWork(any(), any()) } returns null
        every { citizens.pendingStockDelivery(any(), any()) } returns null
        every { store.markConsumedItems(any()) } answers { CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY.adding(
            firstArg<CivInventoryTransaction>().collected?.ground.orEmpty().map(CivGroundItemChange::entityId))) }
        every { player.uniqueId } returns owner
        every { player.world } returns world
        every { player.location } returns Location(world, 0.0, 65.0, 0.0)
        every { player.eyeLocation } returns Location(world, 0.0, 66.62, 0.0)
        every { player.hasPermission(any<String>()) } returns true
        every { player.isOnline } returns true
        world.loadChunk(0, 0)
        val ground = world.getBlockAt(0, 63, 0).also { it.type = Material.GRASS_BLOCK }
        every { player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER) } returns RayTraceResult(ground.location.toVector(), ground, BlockFace.UP)
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 2, CivPosition(0, 64, 0), 0,
            CivsBlueprints.schematicId("nordic", BuildingType.TOWN_HALL, 2), CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)),
            CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
        val home = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, level = 1, anchor = CivPosition(10, 64, 0),
            schematicId = CivsBlueprints.schematicId("nordic", BuildingType.HOUSE, 1),
            bounds = CivBox(CivPosition(8, 63, -2), CivPosition(12, 68, 2)), entrance = CivPosition(10, 64, -2))
        city = CivsRules.validate(Settlement(UUID.randomUUID(), owner, "Oak Town", world.uid, CivPosition(0, 64, 0), 96,
            "nordic", Stock(96, 96, 24), listOf(hall, home), (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, createdAt = time))
        every { store.load() } returns CompletableFuture.completedFuture(if (empty) emptyList() else listOf(city))
        every { store.loadPlan(any()) } returns CompletableFuture.completedFuture(null)
        every { store.loadRetainedPlanSource(any()) } answers {
            store.loadPlan(firstArg()).thenApply { plan -> plan?.let(CivRetainedPlanSource::from) }
        }
        every { store.planGeneration(any()) } returns 0L
        every { store.homeServer() } returns null
        every { store.lookup(any()) } returns CompletableFuture.completedFuture(null)
        every { store.save(any()) } answers {
            CompletableFuture<Settlement>().also { saves += firstArg<Settlement>() to it }
        }
        service = CivsService(plugin, tasks, CivsSettings(Path.of("/tmp/civs-unused"), allowedWorlds, 96,
            maximumCities, 50_000, 4, 120, 48, 96), store, blueprints, Executor(Runnable::run), worldAdapter, renderer, citizens,
            backendTransfer = transfer, clock = { time }, boundaryRenderer = boundary,
            storageAdapter = storageFactory?.invoke(plugin) ?: CivsStorage(plugin, supplyClock = { time }) { emptyArray() },
            supplyExecutor = Executor(Runnable::run))
    }
    fun prepareFounding(name: String, style: String): CompletableFuture<Unit> =
        service.beginFoundingSelection(player, name, style).thenCompose {
            service.selectGround(player, service.selection(player)!!.id, CivsGroundTarget(world.uid, CivPosition(0, 63, 0)))
        }
    fun start() { val loaded = service.start(); tick(); await(loaded) shouldBe Unit }
    fun tick() = paper.performTicks(8)
    fun tickUntil(description: String, condition: () -> Boolean) {
        repeat(80) {
            if (condition()) return
            paper.performTicks(1)
        }
        check(condition()) { "$description did not complete within 80 scheduler ticks" }
    }

    fun replaceCityInMemory(city: Settlement) {
        @Suppress("UNCHECKED_CAST")
        val cities = CivsService::class.java.getDeclaredField("cities").also { it.isAccessible = true }
            .get(service) as MutableMap<UUID, Settlement>
        cities[city.id] = city
    }

    fun prepareCarpentry(stock: Map<String, Long> = emptyMap()): Pair<CivBuilding, CivWorker> {
        val workshop = city.buildings.last().copy(type = BuildingType.SAWMILL,
            control = CivPosition(10, 63, 0), production = null)
        val smith = workshop.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH,
            anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
            bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), production = "IRON_PICKAXE")
        val carpenter = city.workers.first().copy(assignment = workshop.id, nextDeliveryAt = 1)
        val smithWorker = city.workers[1].copy(assignment = smith.id, nextDeliveryAt = 1)
        val ready = CivsRules.validate(city.copy(buildings = listOf(city.buildings.first(), workshop, smith),
            workers = listOf(carpenter, smithWorker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL,
                buildingMaterials = mapOf(workshop.id.toString() to stock))))
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        return workshop to carpenter
    }
    fun craftJob(workerId: UUID): CivCraftJob {
        repeat(80) {
            val current = requireNotNull(service.city(player))
            service.craftPlan(current, current.workers.first { it.id == workerId })?.let { return it }
            paper.performTicks(1)
        }
        error("Supply dispatcher did not assign a carpenter batch within80 ticks")
    }
    fun supplyPlan(workerId: UUID): CivCourierPlan {
        repeat(80) {
            val current = requireNotNull(service.city(player))
            service.courierPlan(current, current.workers.first { it.id == workerId })?.let { return it }
            paper.performTicks(1)
        }
        error("Supply dispatcher did not assign worker $workerId within80 ticks")
    }
    fun <T> await(future: CompletableFuture<T>): T {
        repeat(80) {
            if (future.isDone) return future.join()
            paper.performTicks(1)
        }
        check(future.isDone) { "Service future did not finish within 80 ticks; pending saves=${saves.count { !it.second.isDone }}" }
        return future.join()
    }
    fun supplySite(owner: UUID, logs: Int): CivPlacementPlan {
        val site = city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE, control = CivPosition(10, 63, 0))
        world.getBlockAt(10, 63, 0).type = Material.BARREL
        val ready = city.copy(owner = owner, memberNames = paper.server.getPlayer(owner)?.name?.let { mapOf(owner.toString() to it) },
            stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOf(city.buildings.first(), site))
        val plan = CivPlacementPlan(site.id, ready.id, world.uid, site.copy(phase = BuildingPhase.CONSTRUCTING),
            List(logs) { i -> CivPlanBlock(CivPosition(8 + i % 5, 64 + i / 25, -2 + i / 5 % 5),
                "minecraft:air", "minecraft:oak_log[axis=y]") }, "a".repeat(64))
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(site.id) } returns CompletableFuture.completedFuture(plan)
        return plan
    }
    fun sitePreview(blueprint: CivBlueprint = previewBlueprint()): CivBlueprint {
        every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
        every { worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), world.uid, blueprint.building,
                emptyList(), adminPlacement = arg(7)))
        }
        every { store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        return blueprint
    }
    fun previewBlueprint(anchor: CivPosition = CivPosition(0, 63, 5), rotation: Int = 0): CivBlueprint {
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val entrance = anchor.copy(y = anchor.y + 1)
        val offset = listOf(CivPosition(0, 1, -5), CivPosition(5, 1, 0), CivPosition(0, 1, 5), CivPosition(-5, 1, 0))[rotation / 90]
        val control = CivPosition(anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z)
        val corners = listOf(CivPosition(-2, 0, -5), CivPosition(2, 5, -1)).map {
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(it.x, it.y, it.z), rotation / 90)
        }
        val hall = city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, anchor = anchor,
            entrance = entrance, rotation = rotation, control = control,
            bounds = CivBox(CivPosition(anchor.x + corners.minOf { it.x }, anchor.y, anchor.z + corners.minOf { it.z }),
                CivPosition(anchor.x + corners.maxOf { it.x }, anchor.y + 5, anchor.z + corners.maxOf { it.z })))
        return CivBlueprint(hall, world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
    }
    fun commit() { saves.last().let { it.second.complete(it.first) }; tick() }
    fun queuedUpgrade(legacy: Boolean = false): ServiceQueuedUpgrade {
        val building = city.buildings.last().copy(entrance = CivPosition(10, 64, 3),
            control = if (legacy) null else CivPosition(10, 63, 0))
        val hut = building.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT, control = null,
            anchor = CivPosition(20, 64, 0), bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), entrance = CivPosition(20, 64, 3))
        val site = building.copy(id = UUID.randomUUID(), phase = BuildingPhase.SITE, control = CivPosition(30, 63, 0),
            anchor = CivPosition(30, 64, 0), bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)), entrance = CivPosition(30, 64, 3))
        val queued = CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOf(city.buildings.first(), building, hut, site), constructionQueue = listOf(building.id, site.id),
            workers = listOf(city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1))))
        val target = building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING, control = CivPosition(10, 63, 0))
        val id = UUID.nameUUIDFromBytes("arccivs-upgrade/${building.id}/2".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val design = CivPlacementPlan(id, queued.id, world.uid, target,
            listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:air", "minecraft:stone")), "a".repeat(64))
        val fresh = design.copy(id = UUID.randomUUID(), blocks = design.blocks.map { it.copy(before = "minecraft:oak_planks") })
        val snapshot = mockk<ArcSchematicSnapshot>()
        every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
        val capture = CompletableFuture<CivPlacementPlan>()
        every { store.load() } returns CompletableFuture.completedFuture(listOf(queued))
        every { store.loadPlan(id) } returns CompletableFuture.completedFuture(design)
        every { store.loadSchematic(id) } returns CompletableFuture.completedFuture(byteArrayOf(2))
        every { blueprints.recover(design) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.capture(any(), any(), any(), any(), any(), any(),
            constructionStorage = true, acceptedStorage = emptyList()) } returns capture
        every { store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deletePlan(any()) } returns CompletableFuture.completedFuture(true)
        every { store.deleteSchematic(any()) } returns CompletableFuture.completedFuture(true)
        world.loadChunk(1, 0)
        world.getBlockAt(10, 63, 3).type = Material.DIRT
        if (!legacy) world.getBlockAt(10, 63, 0).type = Material.BARREL
        return ServiceQueuedUpgrade(queued, building, design, fresh, capture)
    }
    fun incrementalPlacement(phase: ConstructionPhase = ConstructionPhase.READY_TO_PLACE, paid: Boolean = false, stock: Int = 0): ServicePlacement {
        world.getBlockAt(0, 63, 0).type = Material.BARREL
        world.getBlockAt(15, 63, 1).type = Material.BARREL
        world.getBlockAt(20, 63, 0).type = Material.BARREL
        if (stock > 0) (world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            .setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, stock))
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
            anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
            entrance = CivPosition(15, 64, 3), control = CivPosition(15, 63, 1), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, 0, phase, Stock(),
            failure = if (phase == ConstructionPhase.PAUSED) "placement-cancelled" else null, plannedBuilding = building,
            batches = CivConstructionBatches(2, 16), materials = if (paid) mapOf("OAK_PLANKS" to 1L) else mapOf("STONE" to 1L, "OAK_PLANKS" to 1L),
            payment = CivConstructionPayment(reservedBatch = if (paid) 0 else null))
        val hut = city.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT,
            anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, 3), control = CivPosition(20, 63, 0),
            bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
        val ready = CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = CivPosition(0, 63, 0)) else b } + hut + building,
            workers = city.workers.mapIndexed { index, worker -> if (index == 0) worker.copy(assignment = hut.id, nextDeliveryAt = 1) else worker },
            construction = order))
        val plan = CivPlacementPlan(order.id, city.id, world.uid, building, listOf(
            CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
            CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64))
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val placement = ServicePlacement(ready)
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.revalidate(any(), owner) } returns CompletableFuture.completedFuture(Unit)
        every { blueprints.pasteParts(snapshot, building, any()) } answers {
            arg<CivBuildBatch>(2) shouldBe CivBuildBatch(CivPosition(15, 64, 0), CivPosition(15, 64, 0))
            CompletableFuture.completedFuture(listOf(snapshot))
        }
        every { placement.operation.completion } returns placement.pasted
        every { blueprints.paste(snapshot, world.uid, building.anchor, any(), any()) } returns placement.operation
        world.loadChunk(0, 0); world.loadChunk(1, 0)
        return placement
    }
    fun placement(firstMaterial: Material = Material.OAK_PLANKS): ServicePlacement {
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
            anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
            entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), building.id, 1, 3, 0, ConstructionPhase.CONSTRUCTING,
            CivsRules.cost(building.type, 1), plannedBuilding = building)
        val reserved = CivsRules.reserveBuild(city, owner, building, order)
        val ready = CivsRules.validate(reserved.copy(construction = order.copy(workDone = 3, phase = ConstructionPhase.READY_TO_PLACE)))
        val plan = CivPlacementPlan(order.id, city.id, world.uid, building, listOf(
            CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", firstMaterial.key.toString()),
            CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:stone"),
        ), "b".repeat(64))
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        every { snapshot.blocks() } returns listOf(
            ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), firstMaterial.key.toString(), false, false),
            ArcSchematicBlock(ArcSchematicPoint(1, 0, 0), "minecraft:stone", false, false),
        )
        val placement = ServicePlacement(ready)
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { store.deletePlan(order.id) } returns CompletableFuture.completedFuture(true)
        every { store.deleteSchematic(order.id) } returns CompletableFuture.completedFuture(true)
        every { blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.revalidate(plan, owner) } returns placement.validation
        every { placement.operation.completion } returns placement.pasted
        every { blueprints.paste(snapshot, world.uid, building.anchor, any(), any()) } returns placement.operation
        val repair = mockk<ArcSchematicOperation>(relaxed = true)
        every { repair.completion } returns placement.repaired
        every { blueprints.repair(any(), any()) } returns repair
        world.loadChunk(0, 0); world.loadChunk(1, 0)
        return placement
    }
    override fun close() { service.close(); tasks.close() }
}

internal data class ServiceQueuedUpgrade(val city: Settlement, val building: CivBuilding, val design: CivPlacementPlan,
    val fresh: CivPlacementPlan, val capture: CompletableFuture<CivPlacementPlan>)

internal class ServicePlacement(val city: Settlement) {
    val validation = CompletableFuture<Unit>()
    val pasted = CompletableFuture<ArcSchematicMutationResult>()
    val repaired = CompletableFuture<ArcSchematicMutationResult>()
    val operation = mockk<ArcSchematicOperation>(relaxed = true)
}

internal fun autonomousDevelopment(service: CivsService): CivsAutonomousDevelopment =
    CivsService::class.java.getDeclaredField("autonomousDevelopment").also { it.isAccessible = true }
        .get(service) as CivsAutonomousDevelopment

internal fun captureServicePlanningStorage(service: CivsService, city: Settlement) {
    val storage = CivsService::class.java.getDeclaredField("storage").also { it.isAccessible = true }
        .get(service) as CivsStorage
    storage.capturePlanningStorage(city)
}

/** Private callback tests resolve their exact canonical owner after service decomposition. */
internal fun java.lang.reflect.Method.invokeService(service: CivsService, vararg arguments: Any?): Any? {
    val ownerField = when (declaringClass) {
        CivsResidentSupplies::class.java -> "residentSupplies"
        CivsCargoRequests::class.java -> "cargoRequests"
        CivsStorageActions::class.java -> "storageActions"
        CivsCityResearch::class.java -> "cityResearch"
        CivsBuildingRequests::class.java -> "buildingRequests"
        CivsPlacementSession::class.java -> "placement"
        CivsService::class.java -> null
        else -> error("Missing service callback owner for $declaringClass")
    }
    val owner = ownerField?.let { field ->
        CivsService::class.java.getDeclaredField(field).also { it.isAccessible = true }.get(service)
    } ?: service
    return invoke(owner, *arguments)
}
