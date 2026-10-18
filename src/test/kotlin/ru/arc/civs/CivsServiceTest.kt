package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.util.RayTraceResult
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsServiceTest : FunSpec({
    for (allowedWorld in listOf("vanilla", "survival")) test("founding rejects a disallowed world and accepts a numeric name in $allowedWorld") {
        val config = YamlConfiguration().apply {
            loadFromString(requireNotNull(javaClass.getResourceAsStream("/config.yml"))
                .bufferedReader().use { it.readText() })
        }
        val settings = CivsSettings.read(config)
        settings.worlds shouldBe setOf("vanilla", "survival")
        serviceScenario(empty = true, worldName = allowedWorld, allowedWorlds = settings.worlds) { h ->
            h.start()
            every { h.player.world } returns h.paper.addSimpleWorld("world")
            val rejected = h.service.prepareFounding(h.player, "123123", "nordic")
            (shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection).reason shouldBe "wrong-world"
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            verify(exactly = 0) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
            every { h.player.world } returns h.world
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.service.prepareFounding(h.player, "123123", "nordic")
            h.tick()
            retry.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "123123"
        }
    }

    test("replacement keeps the prior placement and rejects confirmation until async work resolves") {
        serviceScenario(empty = true) { h ->
            h.start()
            val firstBlueprint = h.previewBlueprint()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(firstBlueprint)
            val first = h.service.prepareFounding(h.player, "Oak Town", "nordic")
            h.tick(); first.join()
            val original = h.service.preview(h.player)!!
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
            val rotated = h.service.rotatePreview(h.player, -90)
            h.service.preview(h.player) shouldBe original.copy(busy = true)
            h.service.confirmPreview(h.player).isCompletedExceptionally shouldBe true
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
            pending.completeExceptionally(CivsRejection("source-load-failed"))
            h.tick()
            rotated.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe original
            verify(exactly = 1) { h.renderer.show(any(), any(), any()) }
            verify(exactly = 0) { h.renderer.clear(any()) }
        }
    }

    test("move and both rotations preserve the normalized entrance relation and replace the context") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(h.previewBlueprint(arg(5), arg<Int?>(7) ?: 0))
            }
            val first = h.service.prepareFounding(h.player, "Oak Town", "nordic"); h.tick(); first.join()
            val before = h.service.preview(h.player)!!
            val left = h.service.rotatePreview(h.player, -90); h.tick(); left.join()
            h.service.preview(h.player)!!.rotation shouldBe 270
            val nudged = h.service.nudgePreview(h.player, CivsPreviewMove.RIGHT); h.tick(); nudged.join()
            val after = h.service.preview(h.player)!!
            after.entrance shouldBe CivPosition(before.entrance.x - 1, before.entrance.y, before.entrance.z)
            after.anchor shouldBe after.entrance.copy(y = after.entrance.y - 1)
            (after.id != before.id) shouldBe true
            val right = h.service.rotatePreview(h.player, 90); h.tick(); right.join()
            h.service.preview(h.player)!!.rotation shouldBe 0
        }
    }

    test("cancelled async placement preparation cannot publish a late preview") {
        serviceScenario(empty = true) { h ->
            h.start()
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
            val preparing = h.service.prepareFounding(h.player, "Oak Town", "nordic")
            h.service.cancelPreview(h.player)
            h.service.prepareFounding(h.player, "Another Town", "nordic").isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
            pending.complete(h.previewBlueprint()); h.tick()
            preparing.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val next = h.service.prepareFounding(h.player, "Another Town", "nordic"); h.tick()
            next.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "Another Town"
        }
    }

    test("one Core heartbeat activates navigation every tick only while the service is ready") {
        serviceScenario { h ->
            var activations = 0
            every { h.citizens.activateNavigating() } answers { activations++; Unit }
            h.paper.performTicks(3)
            activations shouldBe 0
            h.start()
            val started = activations
            h.paper.performTicks(5)
            activations shouldBe started + 5
            h.service.close()
            h.paper.performTicks(5)
            activations shouldBe started + 5
        }
    }

    test("reload and lifecycle cancellation cannot keep an old navigation heartbeat alive") {
        serviceScenario { h ->
            h.start()
            var activations = 0
            every { h.citizens.activateNavigating() } answers { activations++; Unit }
            val pending = CompletableFuture<List<Settlement>>()
            every { h.store.load() } returns pending
            val reload = h.service.reload(h.player)
            h.paper.performTicks(5)
            activations shouldBe 0
            pending.complete(listOf(h.city))
            h.tick()
            reload.join()
            val resumed = activations
            (resumed > 0) shouldBe true
            h.tasks.close()
            h.paper.performTicks(5)
            activations shouldBe resumed
        }
    }

    test("shared protection follows loaded geometry and unregisters at shutdown") {
        serviceScenario { h ->
            val services = h.plugin.server.servicesManager
            val protection = services.load(ArcBlockProtectionService::class.java)!!
            protection.isProtected(h.world.uid, 40, 64, 40) shouldBe true
            h.start()
            protection.isProtected(h.world.uid, 0, 64, 0) shouldBe true
            protection.isProtected(h.world.uid, 40, 64, 40) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            val shifted = h.city.copy(buildings = listOf(h.city.buildings.first().copy(
                bounds = CivBox(CivPosition(30, 63, 30), CivPosition(35, 68, 35)),
            )))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(shifted))
            val reload = h.service.reload(h.player)
            h.tick(); reload.join()
            protection.isProtected(h.world.uid, 0, 64, 0) shouldBe false
            protection.isProtected(h.world.uid, 32, 64, 32) shouldBe true
            h.service.close()
            services.load(ArcBlockProtectionService::class.java) shouldBe null
        }
    }

    test("a recruitment becomes visible only after its exact durable acknowledgement") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)!!
            val result = h.service.recruit(h.player)
            result.isDone shouldBe false
            h.service.city(h.player) shouldBe before
            h.saves.size shouldBe 1
            h.saves.single().first.stock.food shouldBe before.stock.food - 12
            h.commit()
            result.join() shouldBe Unit
            h.service.city(h.player)!!.workers.size shouldBe 5
            h.service.city(h.player)!!.stock.food shouldBe 12
        }
    }

    test("an unknown write outcome blocks further mutations until authoritative reload") {
        serviceScenario { h ->
            h.start()
            val first = h.service.recruit(h.player)
            val persisted = h.saves.single().first
            h.saves.single().second.completeExceptionally(IllegalStateException("acknowledgement lost"))
            h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.recruit(h.player).isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(persisted))
            val reload = h.service.reload(h.player)
            h.tick()
            reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe persisted
            h.service.city(h.player)!!.stock.food shouldBe 12
        }
    }

    test("stale persistence completion after shutdown cannot publish a replacement state") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)
            val result = h.service.recruit(h.player)
            h.tasks.close()
            h.service.close()
            h.commit()
            result.isCompletedExceptionally shouldBe true
            h.service.city(h.player) shouldBe before
            h.service.ready() shouldBe false
        }
    }

    test("the final founding slot is usable and an uncertain first commit closes founding") {
        serviceScenario(empty = true, maximumCities = 1) { h ->
            h.start()
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.fileSha256 } returns "a".repeat(64)
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
            val blueprint = CivBlueprint(hall, h.world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, hall, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val preparing = h.service.prepareFounding(h.player, "First Town", "nordic")
            h.tick()
            preparing.join() shouldBe Unit
            val confirm = h.service.confirmPreview(h.player)
            h.tick()
            h.saves.size shouldBe 1
            h.saves.single().first.name shouldBe "First Town"
            h.saves.single().second.completeExceptionally(IllegalStateException("unknown atomic write outcome"))
            h.tick()
            confirm.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.prepareFounding(h.player, "Retry", "nordic").isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
        }
    }

    test("recovered PLACING validates before paste and commits without charging resources again") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
            val orderId = UUID.randomUUID()
            val recovering = CivsRules.found(h.city.id, h.city.owner, "Recovered", h.world.uid,
                hall.entrance, 96, "nordic", hall, (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, orderId, 1_000)
                .let { CivsRules.beginPlacement(it, it.owner, orderId) }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(recovering))
            val plan = CivPlacementPlan(orderId, recovering.id, h.world.uid, hall,
                listOf(CivPlanBlock(hall.anchor, "minecraft:air", "minecraft:stone")), "b".repeat(64))
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.blocks() } returns emptyList()
            every { h.store.loadPlan(orderId) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            val validation = CompletableFuture<Unit>()
            every { h.worldAdapter.revalidate(plan, recovering.owner) } returns validation
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            every { operation.completion } returns pasted
            every { h.blueprints.paste(snapshot, h.world.uid, hall.anchor) } returns operation
            val repaired = mockk<ArcSchematicOperation>(relaxed = true)
            every { repaired.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repaired
            h.world.getChunkAt(hall.entrance.x shr 4, hall.entrance.z shr 4)
            h.start()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any()) }
            validation.complete(Unit); h.tick()
            verify(exactly = 1) { h.blueprints.paste(snapshot, h.world.uid, hall.anchor) }
            h.saves.size shouldBe 0
            pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.single().first.stock shouldBe CivsRules.STARTING_STOCK
            h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.buildings.single().phase shouldBe BuildingPhase.ACTIVE
            h.service.city(h.player)!!.stock shouldBe CivsRules.STARTING_STOCK
        }
    }

    for (writesBeforeRevocation in 0..1) test("Lands revocation after preflight stops write ${writesBeforeRevocation + 1} and pauses the same paid construction") {
        serviceScenario { h ->
            val placement = h.placement()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            placement.validation.complete(Unit); h.tick()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            if (writesBeforeRevocation == 0) allowed = false
            h.commit()
            if (writesBeforeRevocation == 1) {
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(15, 64, 0), Material.OAK_PLANKS) }
                h.world.getBlockAt(15, 64, 0).type = Material.OAK_PLANKS
                allowed = false
            }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15 + writesBeforeRevocation, 64, 0) shouldBe false
            placement.pasted.completeExceptionally(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "Lands denied the next write"))
            h.tick()
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PAUSED
            h.commit()
            val paused = h.service.city(h.player)!!
            paused.construction!!.id shouldBe placement.city.construction!!.id
            paused.construction.failure shouldBe "protected-area"
            paused.stock shouldBe placement.city.stock
            h.world.getBlockAt(15, 64, 0).type shouldBe if (writesBeforeRevocation == 1) Material.OAK_PLANKS else Material.AIR
            h.world.getBlockAt(16, 64, 0).type shouldBe Material.AIR
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("placement guards reject unloaded chunks and asynchronous calls without touching Lands or loading chunks") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            h.world.unloadChunk(1, 0)
            h.world.isChunkLoaded(1, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 16, 64, 0) shouldBe false
            h.world.isChunkLoaded(1, 0) shouldBe false
            val worldId = h.world.uid
            CompletableFuture.supplyAsync {
                protection.permitsSchematicMutation("arccivs-construction", worldId, 15, 64, 0)
            }.join() shouldBe false
            verify(exactly = 0) { h.worldAdapter.canModify(any(), any(), any()) }
            h.world.loadChunk(1, 0)
            protection.permitsSchematicMutation("arccivs-construction", worldId, 16, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(16, 64, 0), Material.STONE) }
        }
    }

    test("repair retains only its planned footprint and checks live neighbor materials without an unprotected fallback") {
        serviceScenario { h ->
            val placement = h.placement()
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            val neighbor = h.world.getBlockAt(18, 64, 0).also { it.type = Material.OAK_FENCE }
            protection.isProtected(h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, neighbor, Material.OAK_FENCE) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 19, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            allowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            allowed = true
            placement.repaired.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("cancelled placement releases its guard and retains the paid paused order") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            placement.pasted.complete(ArcSchematicMutationResult(1, 0, true)); h.tick(); h.commit()
            val city = h.service.city(h.player)!!
            city.construction!!.phase shouldBe ConstructionPhase.PAUSED
            city.construction.failure shouldBe "placement-cancelled"
            city.stock shouldBe placement.city.stock
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("closing a service observes actual citizen retirement and duplicate close keeps that barrier") {
        serviceScenario { h ->
            val terminal = CompletableFuture<Void>()
            every { h.citizens.closeAsync() } returns terminal
            h.service.close()
            h.service.close()
            verify(exactly = 1) { h.citizens.closeAsync() }
            h.service.drained().isDone shouldBe false
            terminal.complete(null)
            h.service.drained().join()
        }
    }

    test("closing an active placement releases its guard and a late completion cannot start repair") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            h.service.close()
            verify(exactly = 1) { placement.operation.cancel() }
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick()
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.repair(any(), any()) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }
})

private fun serviceScenario(empty: Boolean = false, maximumCities: Int = 500, worldName: String = "vanilla",
    allowedWorlds: Set<String> = setOf("vanilla"), action: (ServiceScenario) -> Unit) =
    MockBukkitTestRuntime.open().use { paper -> ServiceScenario(paper, empty, maximumCities, worldName, allowedWorlds).use(action) }

private class ServiceScenario(val paper: MockBukkitTestRuntime, empty: Boolean, maximumCities: Int,
    worldName: String, allowedWorlds: Set<String>) : AutoCloseable {
    val plugin = paper.createSimplePlugin("CivsServiceTest")
    val world = paper.addSimpleWorld(worldName)
    val player = mockk<Player>(relaxed = true)
    val store = mockk<CivsStore>()
    val blueprints = mockk<CivsBlueprints>()
    val worldAdapter = mockk<CivsWorld>(relaxed = true)
    val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
    val citizens = mockk<CivsCitizens>(relaxed = true)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val saves = mutableListOf<Pair<Settlement, CompletableFuture<Settlement>>>()
    val owner = UUID.randomUUID()
    val city: Settlement
    val service: CivsService

    init {
        every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        every { player.uniqueId } returns owner
        every { player.world } returns world
        every { player.location } returns Location(world, 0.0, 65.0, 0.0)
        every { player.hasPermission(any<String>()) } returns true
        every { player.isOnline } returns true
        val ground = world.getBlockAt(0, 63, 0).also { it.type = Material.GRASS_BLOCK }
        every { player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER) } returns RayTraceResult(ground.location.toVector(), ground, BlockFace.UP)
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 2, CivPosition(0, 64, 0), 0,
            "minecolonies/nordic/fundamentals/townhall2", CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)),
            CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
        val home = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, level = 1, anchor = CivPosition(10, 64, 0),
            bounds = CivBox(CivPosition(8, 63, -2), CivPosition(12, 68, 2)), entrance = CivPosition(10, 64, -2))
        city = CivsRules.validate(Settlement(UUID.randomUUID(), owner, "Oak Town", world.uid, CivPosition(0, 64, 0), 96,
            "nordic", Stock(96, 96, 24), listOf(hall, home), (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }))
        every { store.load() } returns CompletableFuture.completedFuture(if (empty) emptyList() else listOf(city))
        every { store.save(any()) } answers {
            CompletableFuture<Settlement>().also { saves += firstArg<Settlement>() to it }
        }
        service = CivsService(plugin, tasks, CivsSettings(Path.of("/tmp/civs-unused"), allowedWorlds, 96,
            maximumCities, 50_000, 4, 120, 48, 96), store, blueprints, Executor(Runnable::run), worldAdapter, renderer, citizens)
    }
    fun start() { val loaded = service.start(); tick(); loaded.join() shouldBe Unit }
    fun tick() = paper.performTicks(8)
    fun previewBlueprint(entrance: CivPosition = CivPosition(0, 64, 0), rotation: Int = 0): CivBlueprint {
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val anchor = entrance.copy(y = entrance.y - 1)
        val hall = city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, anchor = anchor,
            entrance = entrance, rotation = rotation,
            bounds = CivBox(CivPosition(anchor.x - 2, anchor.y, anchor.z - 5), CivPosition(anchor.x + 2, anchor.y + 5, anchor.z - 1)))
        return CivBlueprint(hall, world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
    }
    fun commit() { saves.last().let { it.second.complete(it.first) }; tick() }
    fun placement(): ServicePlacement {
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
            anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
            entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), building.id, 1, 3, 0, ConstructionPhase.CONSTRUCTING,
            CivsRules.cost(building.type, 1), plannedBuilding = building)
        val reserved = CivsRules.reserveBuild(city, owner, building, order)
        val ready = CivsRules.validate(reserved.copy(construction = order.copy(workDone = 3, phase = ConstructionPhase.READY_TO_PLACE)))
        val plan = CivPlacementPlan(order.id, city.id, world.uid, building, listOf(
            CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:oak_planks"),
            CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:stone"),
        ), "b".repeat(64))
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        every { snapshot.blocks() } returns listOf(
            ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:oak_planks", false, false),
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
        every { blueprints.paste(snapshot, world.uid, building.anchor) } returns placement.operation
        val repair = mockk<ArcSchematicOperation>(relaxed = true)
        every { repair.completion } returns placement.repaired
        every { blueprints.repair(any(), any()) } returns repair
        world.loadChunk(0, 0); world.loadChunk(1, 0)
        return placement
    }
    override fun close() { service.close(); tasks.close() }
}

private class ServicePlacement(val city: Settlement) {
    val validation = CompletableFuture<Unit>()
    val pasted = CompletableFuture<ArcSchematicMutationResult>()
    val repaired = CompletableFuture<ArcSchematicMutationResult>()
    val operation = mockk<ArcSchematicOperation>(relaxed = true)
}
