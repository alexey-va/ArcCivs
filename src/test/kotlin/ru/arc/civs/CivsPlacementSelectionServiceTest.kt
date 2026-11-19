package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogRecord
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

class CivsPlacementSelectionServiceTest : FunSpec({
    test("admin manual founding accepts its chosen site instead of automatic natural terrain admission") {
        serviceScenario(empty = true) { h ->
            h.sitePreview()
            every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-terrain"))
            h.start()
            h.service.beginAutonomousSelection(h.player, "Chosen Village", "nordic").join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0))))
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            confirmed.isCompletedExceptionally shouldBe false
            h.saves.single().first.name shouldBe "Chosen Village"
            h.commit(); h.commit(); h.await(confirmed)
            h.service.settlements().single().name shouldBe "Chosen Village"
            verify(exactly = 1) { h.store.savePlan(match { it.adminPlacement }) }
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
        }
    }

    for (autonomous in listOf(false, true))
    test("admin manual founding rechecks revoked permission after plan persistence autonomous=$autonomous") {
        serviceScenario(empty = true) { h ->
            h.sitePreview()
            val planSaved = CompletableFuture<CivPlacementPlan>()
            var captured: CivPlacementPlan? = null
            every { h.store.savePlan(any()) } answers { captured = firstArg(); planSaved }
            h.start()
            if (autonomous) h.service.beginAutonomousSelection(h.player, "Chosen Village", "nordic").join()
            else h.service.beginFoundingSelection(h.player, "Chosen Village", "nordic").join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0))))
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            requireNotNull(captured).adminPlacement shouldBe true
            every { h.player.hasPermission("arccivs.admin") } returns false
            planSaved.complete(requireNotNull(captured)); h.tick()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { confirmed.join() })?.reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
            h.service.settlements() shouldBe emptyList()
        }
    }

    test("admin manual founding intent survives a waiting autonomous site with no foundation at construction admission") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val site = queued.city.buildings.last()
            val city = queued.city.copy(autonomy = CivAutonomy(paused = true), inventory = CivInventory(CivStorageMode.PHYSICAL),
                stock = Stock(), constructionQueue = emptyList())
            val target = site.copy(phase = BuildingPhase.CONSTRUCTING)
            val design = queued.design.copy(id = site.id, building = target, terrain = null, adminPlacement = true)
            val captured = design.copy(id = UUID.randomUUID())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(site.id) } returns CompletableFuture.completedFuture(design)
            every { h.store.loadSchematic(site.id) } returns CompletableFuture.completedFuture(byteArrayOf(2))
            every { h.blueprints.recover(design) } returns CompletableFuture.completedFuture(mockk<ArcSchematicSnapshot> {
                every { blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
            })
            every { h.worldAdapter.capture(city.id, city.owner, city.worldId, target, any(), site,
                adminPlacement = true, constructionStorage = true, acceptedStorage = emptyList()) } returns
                CompletableFuture.completedFuture(captured)
            h.world.loadChunk(site.control!!.x shr 4, site.control.z shr 4)
            h.world.getBlockAt(site.control.x, site.control.y, site.control.z).type = Material.BARREL
            h.start()
            val started = h.buildingRequests.activateBuilding(city, site)
            h.tick()
            started.isCompletedExceptionally shouldBe false
            verify(exactly = 1) { h.worldAdapter.captureManualFoundation(city.id, city.owner, city.worldId,
                target, any(), any(), site, false, true) }
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
            h.saves.single().first.construction!!.id shouldBe captured.id
            h.commit(); h.commit(); h.await(started)
            verify(exactly = 1) { h.store.savePlan(match { it.id == captured.id && it.adminPlacement }) }
        }
    }

    test("admin can start autonomous placement while retaining a personal town and cannot bypass revoked admin permission") {
        serviceScenario { h ->
            h.start()
            h.service.beginAutonomousSelection(h.player, "Free Village", "nordic").join()
            h.service.selection(h.player)?.type shouldBe BuildingType.TOWN_HALL
            h.service.city(h.player)?.id shouldBe h.city.id
            every { h.player.hasPermission("arccivs.admin") } returns false
            val denied = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { denied.join() })?.reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
        }
    }

    test("admin plan board placement keeps its explicit autonomous city through preview edits and queues one site without grants") {
        serviceScenario { h ->
            val board = ServiceAutoBoard(h)
            h.start()
            h.service.beginPlanBoardSelection(h.player, board.city.id).join()
            verify(exactly = 1) { h.boundary.pulse(h.player, board.city, true, null, null,
                board.city.buildings.associate { it.id to h.service.buildingStatus(it.id) }, board.city) }
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target)
            h.await(selected)
            h.service.preview(h.player)!!.autonomous shouldBe true
            h.await(h.service.rotatePreview(h.player, 90))
            h.await(h.service.nudgePreview(h.player, CivsPreviewMove.RIGHT))
            h.await(h.service.movePreview(h.player, board.target))
            h.service.city(h.player) shouldBe h.city
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            h.saves shouldBe emptyList()
            board.capture.complete(board.plan())
            h.tickUntil("autonomous plan board city commit") { h.saves.isNotEmpty() }
            val proposed = h.saves.single().first
            proposed.id shouldBe board.city.id
            proposed.owner shouldBe board.city.owner
            proposed.buildings.last().type shouldBe BuildingType.PLAN_BOARD
            proposed.buildings.last().phase shouldBe BuildingPhase.SITE
            proposed.constructionQueue shouldBe listOf(board.blueprint.building.id)
            proposed.revision shouldBe board.city.revision + 1
            proposed.stock shouldBe board.city.stock
            proposed.inventory shouldBe board.city.inventory
            proposed.workers shouldBe board.city.workers
            proposed.immigration shouldBe board.city.immigration
            proposed.forestrySeeded shouldBe board.city.forestrySeeded
            proposed.settings shouldBe board.city.settings
            h.commit() // Durable SITE precedes the existing one-time native control grant.
            h.saves.size shouldBe 2
            h.commit()
            h.await(confirmed)
            h.service.city(h.player) shouldBe h.city
            h.service.settlements().size shouldBe 2
            val placed = h.service.settlements().single { it.id == board.city.id }
            placed.pendingControls shouldBe emptyList()
            placed.constructionQueue shouldBe listOf(board.blueprint.building.id)
            placed.stock shouldBe board.city.stock
            placed.workers shouldBe board.city.workers
            h.time += 10_000
            h.paper.performTicks(40)
            h.saves.size shouldBe 2 // Paused Auto defers the ordinary paid construction queue.
            verify(exactly = 1) { h.worldAdapter.captureAutonomousSite(board.city.id, board.city.owner, h.world.uid,
                board.blueprint.building, any(), any()) }
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), h.owner, any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.save(match { it.id == h.city.id }) }
        }
    }

    for (denial in listOf("admin", "use", "world", "foreign-personal", "missing-city"))
        test("explicit autonomous board entry rejects $denial without falling back to a personal city") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                h.start()
                var cityId = board.city.id
                when (denial) {
                    "admin" -> every { h.player.hasPermission("arccivs.admin") } returns false
                    "use" -> every { h.player.hasPermission("arccivs.use") } returns false
                    "world" -> every { h.player.world } returns h.paper.addSimpleWorld("other")
                    "foreign-personal" -> {
                        // The selected Auto must not become a generic administrative building bypass.
                        cityId = UUID.randomUUID()
                        val foreign = h.city.copy(id = cityId, owner = UUID.randomUUID(), workers = emptyList(),
                            buildings = h.city.buildings.map { it.copy(id = UUID.randomUUID()) })
                        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city, board.city, foreign))
                        h.await(h.service.reload(h.player))
                    }
                    "missing-city" -> cityId = UUID.randomUUID()
                }
                val denied = h.service.beginPlanBoardSelection(h.player, cityId)
                denied.isCompletedExceptionally shouldBe true
                h.service.selection(h.player) shouldBe null
                h.service.city(h.player) shouldBe h.city
                h.saves shouldBe emptyList()
            }
        }

    for (changed in listOf("admin", "use", "world"))
        test("autonomous board preparation rechecks $changed after the asynchronous blueprint load") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                val prepared = CompletableFuture<CivBlueprint>()
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns prepared
                h.start()
                h.service.beginPlanBoardSelection(h.player, board.city.id).join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target)
                when (changed) {
                    "admin" -> every { h.player.hasPermission("arccivs.admin") } returns false
                    "use" -> every { h.player.hasPermission("arccivs.use") } returns false
                    "world" -> every { h.player.world } returns h.paper.addSimpleWorld("other")
                }
                prepared.complete(board.blueprint)
                h.tick()
                selected.isCompletedExceptionally shouldBe true
                h.service.preview(h.player) shouldBe null
                h.saves shouldBe emptyList()
                verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            }
        }

    for (stage in listOf("preview", "capture", "file-write"))
        test("autonomous board confirmation rejects revoked admin authority at $stage before its city commit") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                h.start()
                h.service.beginPlanBoardSelection(h.player, board.city.id).join()
                h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target))
                val fileWrite = CompletableFuture<Unit>()
                if (stage == "file-write") every { h.store.saveSchematic(any(), any()) } returns fileWrite
                if (stage == "preview") every { h.player.hasPermission("arccivs.admin") } returns false
                val confirmed = h.service.confirmPreview(h.player)
                if (stage == "capture") {
                    every { h.player.hasPermission("arccivs.admin") } returns false
                    board.capture.complete(board.plan())
                } else if (stage == "file-write") {
                    board.capture.complete(board.plan())
                    h.tick()
                    verify(exactly = 1) { h.store.saveSchematic(board.blueprint.building.id, any()) }
                    every { h.player.hasPermission("arccivs.admin") } returns false
                    fileWrite.complete(Unit)
                }
                h.tick()
                CivsDiagnostics.rejection(shouldThrow<CompletionException> { confirmed.join() })?.reason shouldBe "not-authorized"
                h.saves shouldBe emptyList()
                h.service.city(h.player) shouldBe h.city
                h.service.settlements().single { it.id == board.city.id } shouldBe board.city
                val control = board.blueprint.building.control!!
                h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
                // The MockK actor is absent from onlinePlayers, so timer pulses cannot visit it.
                h.paper.server.onlinePlayers.contains(h.player) shouldBe false
                CivsPlacementSession::class.java.getDeclaredMethod("refreshBoundary", Player::class.java)
                    .also { it.isAccessible = true }.invokeService(h.service, h.player)
                verify(atLeast = 1) { h.boundary.pulse(h.player, null, false, null, null, emptyMap(), null) }
                verify(exactly = 0) { h.boundary.pulse(h.player, match<Settlement> { it.id == h.city.id }, any(), any(), any(), any(), any()) }
            }
        }

    test("personal catalog plan board selection keeps ordinary site and player authority semantics") {
        serviceScenario { h ->
            val board = ServiceAutoBoard(h)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city))
            val point = CivPosition(6, 63, 10)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.GRASS_BLOCK
            every { h.player.location } returns Location(h.world, 6.0, 65.0, 6.0)
            every { h.player.eyeLocation } returns Location(h.world, 6.0, 66.62, 6.0)
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.PLAN_BOARD).join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id, CivsGroundTarget(h.world.uid, point)))
            h.service.preview(h.player)!!.autonomous shouldBe false
            val confirmed = h.service.confirmPreview(h.player)
            h.tickUntil("personal board site commit") { h.saves.isNotEmpty() }
            h.saves.first().first.let {
                it.id shouldBe h.city.id
                it.constructionQueue shouldBe null
                it.stock shouldBe h.city.stock
                it.workers shouldBe h.city.workers
            }
            h.commit(); h.commit(); h.await(confirmed)
            h.service.city(h.player)!!.buildings.last().type shouldBe BuildingType.PLAN_BOARD
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { h.worldAdapter.captureSite(h.city.id, h.owner, h.world.uid, board.blueprint.building, any(), any(), any()) }
        }
    }

    test("clicking a barrel and ground uses the same placement point while preserving the barrel contents") {
        serviceScenario(empty = true) { h ->
            val point = CivPosition(0, 63, 0)
            val barrel = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val inventory = (barrel.state as org.bukkit.block.Barrel).inventory
            inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<CivPosition>(5) shouldBe point
                CompletableFuture.completedFuture(h.previewBlueprint(point))
            }
            h.start()
            val prepared = h.prepareFounding("Exact barrel", "caledonia"); h.tick(); prepared.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe point
                it.entrance shouldBe point.copy(y = point.y + 1)
                it.control shouldBe CivPosition(0, 64, -5)
            }
            inventory.getItem(0)!!.amount shouldBe 3
            h.service.cancelPreview(h.player)
            barrel.type = Material.GRASS_BLOCK
            val onGround = h.prepareFounding("Same ground", "caledonia"); h.tick(); onGround.join()
            h.service.preview(h.player)!!.anchor shouldBe point
            h.service.preview(h.player)!!.control shouldBe CivPosition(0, 64, -5)
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("founding starts ground selection without reading the player's aim or changing the world") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.player.rayTraceBlocks(any(), any()) } returns null
            h.service.beginFoundingSelection(h.player, "123123", "nordic").join() shouldBe Unit
            h.service.selection(h.player)!!.type shouldBe BuildingType.TOWN_HALL
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) {
                h.player.rayTraceBlocks(any(), any())
                h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any())
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any())
                h.store.save(any())
            }
        }
    }

    for (plant in listOf(Material.SHORT_GRASS, Material.FERN, Material.LARGE_FERN))
        test("explicit click on $plant uses its highlighted support and preserves the founding draft") {
            serviceScenario(empty = true) { h ->
                h.start()
                val support = h.world.getBlockAt(3, 63, 4).also { it.type = Material.GRASS_BLOCK }
                h.world.getBlockAt(3, 64, 4).type = plant
                val clicked = if (plant == Material.LARGE_FERN) h.world.getBlockAt(3, 65, 4).also { it.type = plant }
                    else h.world.getBlockAt(3, 64, 4)
                civsGroundSupport(clicked) shouldBe CivPosition(3, 63, 4)
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                    CompletableFuture.completedFuture(h.previewBlueprint(arg<CivPosition>(5)))
                }
                h.service.beginFoundingSelection(h.player, "123123", "nordic").join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                    CivsGroundTarget(h.world.uid, CivPosition(clicked.x, clicked.y, clicked.z)))
                h.tick(); selected.join()
                h.service.selection(h.player) shouldBe null
                val preview = h.service.preview(h.player)!!
                preview.cityName shouldBe "123123"
                preview.control shouldBe CivPosition(3, 64, -1)
                preview.entrance shouldBe CivPosition(3, 64, 4)
                preview.anchor shouldBe CivPosition(3, 63, 4)
                support.type shouldBe Material.GRASS_BLOCK
                clicked.type shouldBe plant
                verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.store.save(any()) }
            }
        }

    test("an invalid ground click retains the same selection and a later click can prepare the preview") {
        serviceScenario(empty = true) { h ->
            h.start()
            h.service.beginFoundingSelection(h.player, "Oak Town", "nordic").join()
            val selection = h.service.selection(h.player)!!
            val invalid = h.service.selectGround(h.player, selection.id, CivsGroundTarget(h.world.uid, CivPosition(2, 80, 2)))
            (shouldThrow<CompletionException> { invalid.join() }.cause as CivsRejection).reason shouldBe "too-far"
            h.service.selection(h.player) shouldBe selection
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.service.selectGround(h.player, selection.id, CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
            h.tick(); retry.join()
            h.service.preview(h.player)!!.cityName shouldBe "Oak Town"
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()); h.store.save(any()) }
        }
    }

    for (exit in listOf("cancel", "world-changed", "logout", "expired", "shutdown"))
        test("$exit retires a pending selection before a late blueprint can render") {
            serviceScenario(empty = true) { h ->
                h.start()
                val pending = CompletableFuture<CivBlueprint>()
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
                h.service.beginFoundingSelection(h.player, "First", "nordic").join()
                val id = h.service.selection(h.player)!!.id
                val loading = h.service.selectGround(h.player, id, CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
                when (exit) {
                    "world-changed" -> h.service.worldChanged(org.bukkit.event.player.PlayerChangedWorldEvent(h.player, h.world))
                    "logout" -> h.service.quit(mockk { every { player } returns h.player })
                    "expired" -> { h.time += 120_001; h.service.selection(h.player) shouldBe null }
                    "shutdown" -> h.service.close()
                    else -> h.service.cancelSelection(h.player, exit)
                }
                pending.complete(h.previewBlueprint()); h.tick()
                loading.isCompletedExceptionally shouldBe true
                h.service.preview(h.player) shouldBe null
                h.service.selection(h.player) shouldBe null
                verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
                if (exit != "shutdown") {
                    val retry = h.prepareFounding("Retry", "nordic"); h.tick(); retry.join()
                    h.service.preview(h.player)!!.cityName shouldBe "Retry"
                }
            }
        }

    test("new buildings also wait for an explicit ground click and keep current city ownership") {
        serviceScenario { h ->
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            h.service.selection(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            verify(exactly = 1) { h.boundary.pulse(h.player, h.city, true, null, null, h.city.buildings.associate { it.id to h.service.buildingStatus(it.id) }, h.city) }
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            val position = CivPosition(6, 63, 6)
            h.world.getBlockAt(position.x, position.y, position.z).type = Material.GRASS_BLOCK
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val blueprint = h.previewBlueprint(arg<CivPosition>(5))
                CompletableFuture.completedFuture(blueprint.copy(building = blueprint.building.copy(type = BuildingType.WAREHOUSE)))
            }
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, CivsGroundTarget(h.world.uid, position))
            h.tick(); selected.join()
            h.service.preview(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            h.service.city(h.player) shouldBe h.city
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("unexpected preparation failure records the operation request and full exception") {
        serviceScenario(empty = true) { h ->
            h.start()
            val records = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.plugin.logger.addHandler(handler)
            try {
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    CompletableFuture.completedFuture(h.previewBlueprint())
                val cause = IllegalStateException("preview transport unavailable")
                every { h.renderer.show(any(), any(), any()) } throws cause
                val request = h.prepareFounding("Test", "nordic"); h.tick()
                request.isCompletedExceptionally shouldBe true
                records.any { it.message.startsWith("ArcCivs operation select_ground failed (request=") &&
                    generateSequence(it.thrown) { error -> error.cause }.any { error -> error === cause } } shouldBe true
            } finally { h.plugin.logger.removeHandler(handler) }
        }
    }

    test("view-only config prevents founding before terrain or schematic work") {
        serviceScenario(empty = true, allowedWorlds = emptySet()) { h ->
            h.start()
            h.service.foundingAllowed() shouldBe false
            val reason = shouldThrow<CompletionException> { h.prepareFounding("Test", "nordic").join() }.cause as CivsRejection
            reason.reason shouldBe "founding-disabled"
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    test("founding rejects a disallowed world and accepts a numeric name in survival") {
        val config = YamlConfiguration().apply {
            loadFromString(requireNotNull(javaClass.getResourceAsStream("/config.yml"))
                .bufferedReader().use { it.readText() })
        }
        val settings = CivsSettings.read(config)
        settings.worlds shouldBe setOf("vanilla", "survival")
        serviceScenario(empty = true, worldName = "survival", allowedWorlds = settings.worlds) { h ->
            h.start()
            every { h.player.world } returns h.paper.addSimpleWorld("world")
            val rejected = h.prepareFounding("123123", "nordic")
            val reason = shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection
            reason.reason shouldBe "wrong-world"
            reason.values shouldBe mapOf("world" to "world", "worlds" to "survival, vanilla")
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            verify(exactly = 0) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            every { h.player.world } returns h.world
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.prepareFounding("123123", "nordic")
            h.tick()
            retry.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "123123"
        }
    }

    test("world rejection reports the configured world names instead of bundled defaults") {
        serviceScenario(empty = true, allowedWorlds = setOf("custom-realm", "builder-world")) { h ->
            h.start()
            val rejected = h.prepareFounding("Oak Town", "nordic")
            val reason = shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection
            reason.reason shouldBe "wrong-world"
            reason.values shouldBe mapOf("world" to "vanilla", "worlds" to "builder-world, custom-realm")
        }
    }

    test("replacement keeps the prior placement and rejects confirmation until async work resolves") {
        serviceScenario(empty = true) { h ->
            h.start()
            val firstBlueprint = h.previewBlueprint()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(firstBlueprint)
            val first = h.prepareFounding("Oak Town", "nordic")
            h.tick(); first.join()
            val original = h.service.preview(h.player)!!
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
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

    test("four rotations keep the selected placement pivot while the internal control rotates around it") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(h.previewBlueprint(arg<CivPosition>(5), arg<Int?>(7) ?: 0))
            }
            val first = h.prepareFounding("Oak Town", "nordic"); h.tick(); first.join()
            val before = h.service.preview(h.player)!!
            before.anchor shouldBe CivPosition(0, 63, 0)
            before.control shouldBe CivPosition(0, 64, -5)
            val controls = listOf(CivPosition(5, 64, 0), CivPosition(0, 64, 5),
                CivPosition(-5, 64, 0), CivPosition(0, 64, -5))
            for ((index, control) in controls.withIndex()) {
                val rotated = h.service.rotatePreview(h.player, 90); h.tick(); rotated.join()
                h.service.preview(h.player)!!.let {
                    it.anchor shouldBe before.anchor
                    it.entrance shouldBe before.entrance
                    it.rotation shouldBe (index + 1) * 90 % 360
                    it.control shouldBe control
                    (it.id != before.id) shouldBe true
                }
            }
            val left = h.service.rotatePreview(h.player, -90); h.tick(); left.join()
            val nudged = h.service.nudgePreview(h.player, CivsPreviewMove.RIGHT); h.tick(); nudged.join()
            val after = h.service.preview(h.player)!!
            after.anchor shouldBe CivPosition(-1, 63, 0)
            after.control shouldBe CivPosition(-6, 64, 0)
            after.entrance shouldBe CivPosition(-1, 64, 0)
            val right = h.service.rotatePreview(h.player, 90); h.tick(); right.join()
            h.service.preview(h.player)!!.let {
                it.rotation shouldBe 0
                it.anchor shouldBe after.anchor
                it.control shouldBe CivPosition(-1, 64, -5)
            }
        }
    }

    test("explicit and aimed moves replace the placement point without using the internal control as a pivot") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(h.previewBlueprint(arg(5), arg<Int?>(7) ?: 0))
            }
            val first = h.prepareFounding("Oak Town", "nordic"); h.tick(); first.join()
            val rotation = h.service.rotatePreview(h.player, 90); h.tick(); rotation.join()
            val point = CivPosition(4, 62, 3)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.GRASS_BLOCK
            val moved = h.service.movePreview(h.player, CivsGroundTarget(h.world.uid, point)); h.tick(); moved.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe point
                it.entrance shouldBe CivPosition(4, 63, 3)
                it.control shouldBe CivPosition(9, 63, 3)
                it.rotation shouldBe 90
            }
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            val up = h.service.nudgePreview(h.player, CivsPreviewMove.UP); h.tick(); up.join()
            h.service.preview(h.player)!!.anchor shouldBe CivPosition(4, 63, 3)
            h.service.preview(h.player)!!.control shouldBe CivPosition(9, 64, 3)
            val aimed = h.service.movePreview(h.player); h.tick(); aimed.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe CivPosition(0, 63, 0)
                it.control shouldBe CivPosition(5, 64, 0)
                it.rotation shouldBe 90
            }
            verify(exactly = 1) { h.player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER) }
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("cancelled async placement preparation cannot publish a late preview") {
        serviceScenario(empty = true) { h ->
            h.start()
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
            val preparing = h.prepareFounding("Oak Town", "nordic")
            h.service.cancelPreview(h.player)
            h.prepareFounding("Another Town", "nordic").isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            pending.complete(h.previewBlueprint()); h.tick()
            preparing.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val next = h.prepareFounding("Another Town", "nordic"); h.tick()
            next.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "Another Town"
        }
    }

    for (knownRejection in listOf(false, true)) test("the final founding slot handles a known rejection=$knownRejection without hiding an uncertain commit") {
        serviceScenario(empty = true, maximumCities = 1) { h ->
            h.start()
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.fileSha256 } returns "a".repeat(64)
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, control = control)
            val blueprint = CivBlueprint(hall, h.world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, hall, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val preparing = h.prepareFounding("First Town", "nordic")
            h.tick()
            preparing.join() shouldBe Unit
            val confirm = h.service.confirmPreview(h.player)
            h.tick()
            h.saves.size shouldBe 1
            h.saves.single().first.name shouldBe "First Town"
            h.saves.single().second.completeExceptionally(if (knownRejection) CivsRejection("already-member")
                else IllegalStateException("unknown atomic write outcome"))
            h.tick()
            confirm.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe knownRejection
            if (knownRejection) {
                (shouldThrow<CompletionException> { confirm.join() }.cause as CivsRejection).reason shouldBe "already-member"
                h.service.preview(h.player)!!.cityName shouldBe "First Town"
            } else h.prepareFounding("Retry", "nordic").isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
        }
    }
})

class CivsVillagePlanningExecutorServiceTest : FunSpec({
    fun villageExecutor(service: CivsService): Executor {
        val sites = CivsService::class.java.getDeclaredField("villageSites").apply { isAccessible = true }.get(service)
        return CivsVillageSites::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(sites) as Executor
    }
    fun storageExecutor(service: CivsService): Executor =
        CivsService::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(service) as Executor
    fun villageCapture(service: CivsService): Any? {
        val sites = CivsService::class.java.getDeclaredField("villageSites").apply { isAccessible = true }.get(service)
        return CivsVillageSites::class.java.getDeclaredField("capture").apply { isAccessible = true }.get(sites)
    }
    fun placementExecutor(service: CivsService): Executor {
        val placement = CivsService::class.java.getDeclaredField("autonomousPlacement").apply { isAccessible = true }.get(service)
        return CivsAutonomousPlacement::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(placement) as Executor
    }

    test("existing service callers retain the storage executor as their compatible planning default") {
        serviceScenario { h ->
            (villageExecutor(h.service) === storageExecutor(h.service)) shouldBe true
            (placementExecutor(h.service) === storageExecutor(h.service)) shouldBe true
            villageCapture(h.service) shouldBe null
        }
    }

    test("village proof and existing town placement share the planning lane without occupying service IO") {
        serviceScenario { h ->
            h.service.close()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(h.plugin))
            val storage = Executor(Runnable::run)
            val planning = Executor { error("Construction must not submit a village proof") }
            val service = CivsService(h.plugin, tasks,
                CivsSettings(Path.of("/tmp/civs-unused"), setOf("vanilla"), 96, 500, 50_000, 4, 120, 48, 96),
                h.store, h.blueprints, storage, h.worldAdapter, h.renderer, h.citizens,
                backendTransfer = h.transfer, clock = { h.time }, boundaryRenderer = h.boundary,
                storageAdapter = CivsStorage(h.plugin) { emptyArray() }, supplyExecutor = storage,
                planningExecutor = planning)
            try {
                (villageExecutor(service) === planning) shouldBe true
                (placementExecutor(service) === planning) shouldBe true
                (storageExecutor(service) === storage) shouldBe true
                (villageExecutor(service) === storageExecutor(service)) shouldBe false
                (villageCapture(service) is CivsVillageSearchCapture) shouldBe true
            } finally {
                service.close()
                tasks.close()
            }
        }
    }
})

/** A distinct paused Auto city makes the target, domain owner and grant-free admission observable. */
private class ServiceAutoBoard(private val h: ServiceScenario) {
    private val buildingId = UUID.randomUUID()
    private val anchor = CivPosition(262, 63, 10)
    val target = CivsGroundTarget(h.world.uid, anchor)
    val city: Settlement
    var blueprint: CivBlueprint
        private set
    val capture = CompletableFuture<CivPlacementPlan>()

    init {
        fun shifted(point: CivPosition) = point.copy(x = point.x + 256)
        city = CivsRules.validate(h.city.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Auto Board Town",
            center = shifted(h.city.center), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), workers = emptyList(), memberNames = null,
            autonomy = CivAutonomy(paused = true), immigration = CivImmigration(0, h.time),
            buildings = h.city.buildings.map { it.copy(id = UUID.randomUUID(), anchor = shifted(it.anchor), entrance = shifted(it.entrance),
                bounds = CivBox(shifted(it.bounds.min), shifted(it.bounds.max)), control = it.control?.let(::shifted)) }))
        blueprint = blueprint(anchor, 0)
        h.world.loadChunk(anchor.x shr 4, anchor.z shr 4)
        h.world.getBlockAt(anchor.x, anchor.y, anchor.z).type = Material.GRASS_BLOCK
        every { h.player.location } returns Location(h.world, 262.0, 65.0, 6.0)
        every { h.player.eyeLocation } returns Location(h.world, 262.0, 66.62, 6.0)
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city, city))
        every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            blueprint = blueprint(arg(5), arg<Int?>(7) ?: 0)
            CompletableFuture.completedFuture(blueprint)
        }
        every { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val building = arg<CivBuilding>(3)
            CompletableFuture.completedFuture(CivTerraformPlan(building.bounds, emptyList(), emptyList(), 63, 0, 0))
        }
        every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } returns capture
        every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(plan(firstArg(), arg(2), arg(3)))
        }
        every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
    }

    private fun blueprint(at: CivPosition, rotation: Int): CivBlueprint {
        val original = h.previewBlueprint(at, rotation)
        every { original.snapshot.fileSha256 } returns "a".repeat(64)
        return original.copy(building = original.building.copy(id = buildingId, type = BuildingType.PLAN_BOARD,
            schematicId = CivsPlanBoardBlueprint.SCHEMATIC_ID))
    }

    fun plan(cityId: UUID = city.id, worldId: UUID = city.worldId, building: CivBuilding = blueprint.building): CivPlacementPlan =
        CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building,
            listOf(CivPlanBlock(building.bounds.min, "minecraft:air", "minecraft:stone")), "a".repeat(64),
            CivTerraformPlan(building.bounds, emptyList(), emptyList(), 63, 0, 0))
}
