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
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.api.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsServiceTest : FunSpec({
    test("new town sites require a player barrel and recheck it after asynchronous capture") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val missing = h.service.confirmPreview(h.player)
            (shouldThrow<CompletionException> { missing.join() }.cause as CivsRejection).reason shouldBe "control-missing"
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()); h.store.save(any()) }
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val capture = CompletableFuture<CivPlacementPlan>()
            var cityId: UUID? = null
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } answers { cityId = firstArg(); capture }
            val confirming = h.service.confirmPreview(h.player)
            block.type = Material.AIR
            capture.complete(CivPlacementPlan(UUID.randomUUID(), cityId!!, h.world.uid, blueprint.building, emptyList()))
            h.tick()
            (shouldThrow<CompletionException> { confirming.join() }.cause as CivsRejection).reason shouldBe "control-missing"
            block.type shouldBe Material.AIR
            h.service.city(h.player) shouldBe null
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("a barrel broken during site commit is never recreated and construction requires its replacement") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, blueprint.building, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val confirming = h.service.confirmPreview(h.player); h.tick()
            h.saves.size shouldBe 1
            block.type = Material.AIR
            h.commit(); confirming.join() shouldBe Unit
            block.type shouldBe Material.AIR
            val city = h.service.city(h.player)!!
            city.buildings.single().phase shouldBe BuildingPhase.SITE
            val start = h.service.startBuilding(h.player, blueprint.building.id)
            (shouldThrow<CompletionException> { start.join() }.cause as CivsRejection).reason shouldBe "control-missing"
            verify(exactly = 0) { h.store.loadPlan(any()) }
            block.type shouldBe Material.AIR
        }
    }

    test("city deletion waits for durable confirmation then removes authority while preserving native blocks and items") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            val block = h.world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            val barrel = (block.state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val durable = CompletableFuture<Unit>()
            every { h.store.delete(city) } returns durable
            h.start()
            val lookup = CompletableFuture<CivsNetworkCity?>()
            every { h.store.lookup(h.owner) } returns lookup
            val pendingLookup = h.service.refreshCity(h.player)
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            val deleted = h.service.deleteCity(h.player, city.id, city.name)
            deleted.isDone shouldBe false
            h.service.city(h.player) shouldBe city
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe true
            h.service.deleteCity(h.player, city.id, city.name).isCompletedExceptionally shouldBe true
            durable.complete(Unit); h.tick()
            deleted.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
            lookup.complete(CivsNetworkCity(BackendServerId.of("spawn"), city, 1L)); h.tick()
            pendingLookup.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe null
            h.service.ready() shouldBe true
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe false
            block.type shouldBe Material.BARREL
            barrel.getItem(0)!!.amount shouldBe 7
            verify(exactly = 1) { h.store.delete(city); h.boundary.clear(h.owner) }
            h.service.beginFoundingSelection(h.player, city.name, city.style).join() shouldBe Unit
        }
    }

    test("deletion guards cannot reach storage with a stale confirmation or remaining stock") {
        serviceScenario { h ->
            h.start()
            for ((id, name, reason) in listOf(Triple(UUID.randomUUID(), h.city.name, "delete-city-changed"),
                Triple(h.city.id, "wrong", "delete-name-mismatch"), Triple(h.city.id, h.city.name, "delete-storage-not-empty"))) {
                val failure = shouldThrow<CompletionException> { h.service.deleteCity(h.player, id, name).join() }
                (failure.cause as CivsRejection).reason shouldBe reason
            }
            verify(exactly = 0) { h.store.delete(any()) }
            h.service.city(h.player) shouldBe h.city
        }
    }

    test("ambiguous deletion freezes the city until canonical reload without retrying a write") {
        serviceScenario { h ->
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.delete(city) } returns CompletableFuture.failedFuture(java.io.IOException("ack lost"))
            h.start()
            val deletion = h.service.deleteCity(h.player, city.id, city.name); h.tick()
            (shouldThrow<CompletionException> { deletion.join() }.cause as CivsRejection).reason shouldBe "storage-unavailable"
            h.service.city(h.player) shouldBe city
            h.service.cityBusy(h.player) shouldBe true
            h.service.deleteCity(h.player, city.id, city.name).isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.delete(city) }
            every { h.store.load() } returns CompletableFuture.completedFuture(emptyList())
            val reload = h.service.reload(h.player); h.tick(); reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
        }
    }

    test("physical recruitment journals before debit and blocks repetition until the receipt is retired") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD, 24))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val retire = CompletableFuture<Boolean>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns retire
            h.start()
            val recruiting = h.service.recruit(h.player)
            barrel.getItem(0)!!.amount shouldBe 24
            h.saves.size shouldBe 0
            h.service.recruit(h.player).isCompletedExceptionally shouldBe true
            journal.complete(receipt!!); h.tick()
            barrel.getItem(0)!!.amount shouldBe 12
            h.service.city(h.player)!!.workers.size shouldBe 4
            h.saves.size shouldBe 1
            h.commit()
            h.service.city(h.player)!!.workers.size shouldBe 5
            recruiting.isDone shouldBe false
            h.service.recruit(h.player).isCompletedExceptionally shouldBe true
            retire.complete(true); h.tick()
            recruiting.join() shouldBe Unit
            barrel.getItem(0)!!.amount shouldBe 12
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("canonical committed inventory recovery retires the journal without paying twice") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD, 12))
            val before = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = control) else b })
            val after = CivsRules.recruitFunded(before, before.owner, UUID.randomUUID(), "Recovered")
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("BREAD", 24), CivPlainItem("BREAD", 12))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(after))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            h.start(); h.paper.performTicks(25)
            h.service.city(h.player) shouldBe after
            barrel.getItem(0)!!.amount shouldBe 12
            h.saves.size shouldBe 0
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
        }
    }

    test("startup locks recovered native inventories before ready and before the first recovery pulse") {
        serviceScenario { h ->
            val point = CivPosition(5, 63, 0)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(point.x, point.y, point.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 64))
            val before = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(point)))
            val after = before.copy(revision = before.revision + 1)
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(point, "BARREL", 0, null, CivPlainItem("COBBLESTONE", 64))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            h.start()
            h.service.ready() shouldBe true
            h.saves.size shouldBe 0
            val view = mockk<org.bukkit.inventory.InventoryView>()
            every { view.topInventory } returns barrel
            every { view.player } returns h.player
            h.paper.callEvent(org.bukkit.event.inventory.InventoryOpenEvent(view)).isCancelled shouldBe true
            barrel.getItem(0)!!.amount shouldBe 64
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
                h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any())
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
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                    CompletableFuture.completedFuture(h.previewBlueprint(arg(5)))
                }
                h.service.beginFoundingSelection(h.player, "123123", "nordic").join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                    CivsGroundTarget(h.world.uid, CivPosition(clicked.x, clicked.y, clicked.z)))
                h.tick(); selected.join()
                h.service.selection(h.player) shouldBe null
                val preview = h.service.preview(h.player)!!
                preview.cityName shouldBe "123123"
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
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.service.selectGround(h.player, selection.id, CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
            h.tick(); retry.join()
            h.service.preview(h.player)!!.cityName shouldBe "Oak Town"
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()); h.store.save(any()) }
        }
    }

    for (exit in listOf("cancel", "menu-reopened", "world-changed", "logout", "expired", "shutdown"))
        test("$exit retires a pending selection before a late blueprint can render") {
            serviceScenario(empty = true) { h ->
                h.start()
                val pending = CompletableFuture<CivBlueprint>()
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
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
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
            val position = CivPosition(6, 63, 6)
            h.world.getBlockAt(position.x, position.y, position.z).type = Material.GRASS_BLOCK
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val blueprint = h.previewBlueprint(arg(5))
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
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
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

    test("remote membership never enters local simulation or mutation and uses a typed backend request") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("spawn")
            val record = CivsNetworkCity(BackendServerId.of("survival"), h.city, 1000)
            every { h.store.lookup(h.owner) } returns CompletableFuture.completedFuture(record)
            val loaded = h.service.refreshCity(h.player); h.tick(); loaded.join()
            h.service.city(h.player) shouldBe null
            h.service.settlements() shouldBe emptyList()
            h.service.remoteCity(h.player) shouldBe record
            val denied = shouldThrow<CompletionException> { h.service.recruit(h.player).join() }.cause as CivsRejection
            denied.reason shouldBe "remote-city"
            val found = shouldThrow<CompletionException> { h.prepareFounding("Second", "nordic").join() }.cause as CivsRejection
            found.reason shouldBe "already-member"
            every { h.transfer.connect(h.player, BackendServerId.of("survival")) } returns BackendTransferResult.SENT
            h.service.transferHome(h.player).join() shouldBe Unit
            verify(exactly = 1) { h.transfer.connect(h.player, BackendServerId.of("survival")) }
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("fresh network read failure clears remote snapshot and reports an actionable rejection") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("parkour")
            every { h.store.lookup(h.owner) } returns CompletableFuture.completedFuture(CivsNetworkCity(BackendServerId.of("spawn"), h.city, 1000))
            val loaded = h.service.refreshCity(h.player); h.tick(); loaded.join()
            every { h.store.lookup(h.owner) } returns CompletableFuture.failedFuture(IllegalStateException("database offline"))
            val failed = h.service.refreshCity(h.player); h.tick()
            (shouldThrow<CompletionException> { failed.join() }.cause as CivsRejection).reason shouldBe "network-unavailable"
            h.service.remoteCity(h.player) shouldBe null
            h.service.settlements() shouldBe emptyList()
        }
    }

    test("a late membership response cannot replace a newer request") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("parkour")
            val old = CompletableFuture<CivsNetworkCity?>()
            val recent = CompletableFuture<CivsNetworkCity?>()
            every { h.store.lookup(h.owner) } returnsMany listOf(old, recent)
            val first = h.service.refreshCity(h.player)
            val second = h.service.refreshCity(h.player)
            val current = CivsNetworkCity(BackendServerId.of("spawn"), h.city, 2000)
            recent.complete(current); h.tick(); second.join()
            old.complete(CivsNetworkCity(BackendServerId.of("survival"), h.city, 1000)); h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe current
        }
    }

    test("view-only config prevents founding before terrain or schematic work") {
        serviceScenario(empty = true, allowedWorlds = emptySet()) { h ->
            h.start()
            h.service.foundingAllowed() shouldBe false
            val reason = shouldThrow<CompletionException> { h.prepareFounding("Test", "nordic").join() }.cause as CivsRejection
            reason.reason shouldBe "founding-disabled"
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    test("startup storage failure keeps simulation closed and exposes failure status") {
        serviceScenario(empty = true) { h ->
            every { h.store.load() } returns CompletableFuture.failedFuture(IllegalStateException("schema mismatch"))
            val started = h.service.start(); h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.unavailableReason() shouldBe "storage-unavailable"
            h.service.settlements() shouldBe emptyList()
            verify(exactly = 0) { h.citizens.activateNavigating() }
        }
    }

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
            val rejected = h.prepareFounding("123123", "nordic")
            val reason = shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection
            reason.reason shouldBe "wrong-world"
            reason.values shouldBe mapOf("world" to "world", "worlds" to "survival, vanilla")
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            verify(exactly = 0) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
            every { h.player.world } returns h.world
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
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
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(firstBlueprint)
            val first = h.prepareFounding("Oak Town", "nordic")
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
            val first = h.prepareFounding("Oak Town", "nordic"); h.tick(); first.join()
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
            val preparing = h.prepareFounding("Oak Town", "nordic")
            h.service.cancelPreview(h.player)
            h.prepareFounding("Another Town", "nordic").isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) }
            pending.complete(h.previewBlueprint()); h.tick()
            preparing.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val next = h.prepareFounding("Another Town", "nordic"); h.tick()
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

    for (knownRejection in listOf(false, true)) test("the final founding slot handles a known rejection=$knownRejection without hiding an uncertain commit") {
        serviceScenario(empty = true, maximumCities = 1) { h ->
            h.start()
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.fileSha256 } returns "a".repeat(64)
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, control = control)
            val blueprint = CivBlueprint(hall, h.world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } answers {
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

    for (cursor in 0..1) test("recovered tile $cursor pastes only its saved layer and waits for another builder trip") {
        serviceScenario { h ->
            val building = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
                anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
                entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
            val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, cursor, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = building, batches = CivConstructionBatches(2, 256), materials = mapOf("STONE" to 2L))
            val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(12, 7, 3)),
                buildings = h.city.buildings + building, construction = order))
            val plan = CivPlacementPlan(order.id, city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 65, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 67, 0), "minecraft:air", "minecraft:air"),
            ), "b".repeat(64))
            val full = mockk<ArcSchematicSnapshot>(relaxed = true)
            val part = mockk<ArcSchematicSnapshot>(relaxed = true)
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val repair = mockk<ArcSchematicOperation>(relaxed = true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(full)
            every { h.worldAdapter.revalidate(any(), h.owner) } answers {
                firstArg<CivPlacementPlan>().blocks.map { it.position.y } shouldBe listOf(64 + cursor)
                CompletableFuture.completedFuture(Unit)
            }
            every { h.blueprints.crop(full, building, any()) } answers {
                arg<CivBuildBatch>(2).minimum.y shouldBe 64 + cursor
                CompletableFuture.completedFuture(part)
            }
            every { operation.completion } returns pasted
            every { h.blueprints.paste(part, h.world.uid, building.anchor) } returns operation
            every { repair.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repair
            h.start()
            verify(exactly = 1) { h.blueprints.paste(part, h.world.uid, building.anchor) }
            verify(exactly = 0) { h.blueprints.paste(full, any(), any()); h.store.saveInventoryTransaction(any()) }
            pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            h.service.city(h.player)!!.construction!!.workDone shouldBe cursor
            h.commit()
            h.service.city(h.player)!!.inventory shouldBe city.inventory
            if (cursor == 0) {
                h.service.city(h.player)!!.construction!!.workDone shouldBe 1
                h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
                h.paper.performTicks(80)
                verify(exactly = 0) { h.blueprints.repair(any(), any()) }
            } else {
                h.service.city(h.player)!!.construction shouldBe null
                h.service.city(h.player)!!.building(building.id)!!.phase shouldBe BuildingPhase.ACTIVE
            }
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any()) }
        }
    }

    test("recovered PLACING validates before paste and commits without charging resources again") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
            val orderId = UUID.randomUUID()
            // Exact legacy paid founding snapshot: the new founding API deliberately creates a SITE.
            val recovering = CivsRules.validate(Settlement(h.city.id, h.city.owner, "Recovered", h.world.uid,
                hall.entrance, 96, "nordic", CivsRules.STARTING_STOCK, listOf(hall),
                (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, construction =
                CivConstruction(orderId, hall.id, 1, 0, 0, ConstructionPhase.PLACING, Stock(), plannedBuilding = hall)))

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
    val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
    val transfer = mockk<BackendTransfer>()
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val saves = mutableListOf<Pair<Settlement, CompletableFuture<Settlement>>>()
    val owner = UUID.randomUUID()
    var time = System.currentTimeMillis()
    val city: Settlement
    val service: CivsService

    init {
        every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        every { boundary.drained() } returns CompletableFuture.completedFuture(null)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
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
            "minecolonies/nordic/fundamentals/townhall2", CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)),
            CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
        val home = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, level = 1, anchor = CivPosition(10, 64, 0),
            bounds = CivBox(CivPosition(8, 63, -2), CivPosition(12, 68, 2)), entrance = CivPosition(10, 64, -2))
        city = CivsRules.validate(Settlement(UUID.randomUUID(), owner, "Oak Town", world.uid, CivPosition(0, 64, 0), 96,
            "nordic", Stock(96, 96, 24), listOf(hall, home), (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }))
        every { store.load() } returns CompletableFuture.completedFuture(if (empty) emptyList() else listOf(city))
        every { store.homeServer() } returns null
        every { store.lookup(any()) } returns CompletableFuture.completedFuture(null)
        every { store.save(any()) } answers {
            CompletableFuture<Settlement>().also { saves += firstArg<Settlement>() to it }
        }
        service = CivsService(plugin, tasks, CivsSettings(Path.of("/tmp/civs-unused"), allowedWorlds, 96,
            maximumCities, 50_000, 4, 120, 48, 96), store, blueprints, Executor(Runnable::run), worldAdapter, renderer, citizens,
            backendTransfer = transfer, clock = { time }, boundaryRenderer = boundary)
    }
    fun prepareFounding(name: String, style: String): CompletableFuture<Unit> =
        service.beginFoundingSelection(player, name, style).thenCompose {
            service.selectGround(player, service.selection(player)!!.id, CivsGroundTarget(world.uid, CivPosition(0, 63, 0)))
        }
    fun start() { val loaded = service.start(); tick(); loaded.join() shouldBe Unit }
    fun tick() = paper.performTicks(8)
    fun previewBlueprint(entrance: CivPosition = CivPosition(0, 64, 0), rotation: Int = 0): CivBlueprint {
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val anchor = entrance.copy(y = entrance.y - 1)
        val hall = city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, anchor = anchor,
            entrance = entrance, rotation = rotation, control = anchor,
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
