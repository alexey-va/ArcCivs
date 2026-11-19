package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import ru.arc.network.BackendServerId
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.network.BackendTransferResult

class CivsSettlementLifecycleServiceTest : FunSpec({
    test("autonomous towns do not hide or migrate the administrator personal settlement on load") {
        serviceScenario { h ->
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val autonomous = h.city.copy(id = UUID.randomUUID(), name = "Independent village", workers = emptyList(),
                autonomy = CivAutonomy(paused = true))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(autonomous, personal))
            h.start()
            h.service.city(h.player)?.id shouldBe personal.id
            h.service.settlements().single { it.id == autonomous.id }.inventory shouldBe null
            h.service.autonomyStatus(autonomous.id)?.stage shouldBe "paused"
            h.saves shouldBe emptyList()
            h.service.refreshCity(h.player).let { lookup -> h.tick(); lookup.join() }
            h.service.ready() shouldBe true
        }
    }

    test("testing settings require the current owner's permission and only appear after the save") {
        serviceScenario { h ->
            h.start()
            every { h.player.hasPermission("arccivs.testing") } returns false
            h.service.canChangeSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS) shouldBe false
            val denied = h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true)
            h.tick()
            (shouldThrow<CompletionException> { denied.join() }.cause as CivsRejection).reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
            every { h.player.hasPermission("arccivs.testing") } returns true
            val changed = h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true)
            h.service.city(h.player)!!.settings shouldBe null
            changed.isDone shouldBe false
            h.commit(); changed.join()
            h.service.city(h.player)!!.settings shouldBe CivSettlementSettings(immortalResidents = true)
            val writes = h.saves.size
            h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true).join()
            h.saves.size shouldBe writes
            every { h.player.hasPermission("arccivs.chunkload") } returns false
            h.service.canChangeSettlementSetting(h.player, CivSettlementSetting.CHUNK_LOADING) shouldBe false
        }
    }

    test("remembering a renamed existing member advances the revision without changing membership") {
        serviceScenario { h ->
            val memberId = UUID.randomUUID()
            val ready = h.city.copy(members = setOf(memberId), memberNames = mapOf(memberId.toString() to "OldName"))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            every { h.player.name } returns "Owner"
            h.start()
            val member = org.mockbukkit.mockbukkit.entity.PlayerMock(h.paper.server, "NewName", memberId).also(h.paper.server::addPlayer)
            val updated = h.service.addMember(h.player, member.name)
            h.tick()
            h.saves.single().first.let {
                it.revision shouldBe ready.revision + 1
                it.members shouldBe ready.members
                it.memberNames!![memberId.toString()] shouldBe "NewName"
            }
            h.commit(); updated.join()
            h.service.city(h.player)!!.memberNames!![memberId.toString()] shouldBe "NewName"
        }
    }

    test("ordinary save leaves menus available and queued assignment uses the committed city") {
        serviceScenario { h ->
            h.start()
            val member = h.paper.addPlayer("QueuedMember")
            val worker = h.city.workers.first()
            val membership = h.service.addMember(h.player, member.name)
            h.service.cityBusy(h.player) shouldBe false
            val assignment = h.service.assign(h.player, worker.id, h.city.buildings.first().id)
            assignment.isDone shouldBe false
            h.saves.size shouldBe 1
            h.commit(); membership.join()
            h.saves.size shouldBe 2
            val next = h.saves.last().first
            (member.uniqueId in next.members) shouldBe true
            next.workers.first().assignment shouldBe h.city.buildings.first().id
            next.revision shouldBe h.saves.first().first.revision + 1
            h.commit(); assignment.join()
        }
    }

    test("a queued action is cancelled after logout without a second save") {
        serviceScenario { h ->
            h.start()
            val member = h.paper.addPlayer("LogoutMember")
            val membership = h.service.addMember(h.player, member.name)
            val assignment = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            val residentActions = listOf(h.service.feedWorker(h.player, h.city.workers.first().id),
                h.service.supplyWorker(h.player, h.city.workers.first().id, "BREAD"),
                h.service.returnWorkerItem(h.player, h.city.workers.first().id, "BREAD"))
            every { h.player.isOnline } returns false
            h.commit(); membership.join()
            shouldThrow<CompletionException> { assignment.join() }.cause.let { (it as CivsRejection).reason } shouldBe "player-offline"
            residentActions.forEach { future ->
                shouldThrow<CompletionException> { future.join() }.cause.let { (it as CivsRejection).reason } shouldBe "player-offline"
            }
            h.saves.size shouldBe 1
        }
    }

    test("city deletion journals intent before retiring controls and drops contents without the free barrel") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            val block = h.world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            val barrel = (block.state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL,
                materials = mapOf("OAK_LOG" to 96L),
                buildingMaterials = mapOf(h.city.buildings.first().id.toString() to mapOf("BREAD" to 24L))),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val durable = CompletableFuture<Unit>()
            every { h.store.delete(any()) } returns durable
            h.start()
            val lookup = CompletableFuture<CivsNetworkCity?>()
            every { h.store.lookup(h.owner) } returns lookup
            val pendingLookup = h.service.refreshCity(h.player)
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            val deleted = h.service.deleteCity(h.player, city.id, city.name)
            deleted.isDone shouldBe false
            h.service.city(h.player) shouldBe city
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe true
            val repeated = h.service.deleteCity(h.player, city.id, city.name)
            repeated.isDone shouldBe false
            block.type shouldBe Material.BARREL
            barrel.getItem(0)!!.amount shouldBe 7
            h.tick()
            h.saves.single().first.pendingDeletion shouldBe CivDeletion(null)
            h.commit()
            (shouldThrow<CompletionException> { repeated.join() }.cause as CivsRejection).reason shouldBe "deletion-pending"
            block.type shouldBe Material.AIR
            h.world.entities.filterIsInstance<org.bukkit.entity.Item>().map { it.itemStack.type to it.itemStack.amount } shouldBe listOf(Material.DIAMOND to 7)
            deleted.isDone shouldBe false
            durable.complete(Unit); h.tick()
            deleted.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
            lookup.complete(CivsNetworkCity(BackendServerId.of("spawn"), city.id, city.owner, city.name, 1L)); h.tick()
            pendingLookup.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe null
            h.service.ready() shouldBe true
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe false
            block.type shouldBe Material.AIR
            verify(exactly = 1) { h.store.delete(h.saves.single().first); h.boundary.clear(h.owner) }
            h.service.beginFoundingSelection(h.player, city.name, city.style).join() shouldBe Unit
        }
    }

    test("deletion guards cannot reach storage with a stale confirmation or wrong name") {
        serviceScenario { h ->
            h.start()
            for ((id, name, reason) in listOf(Triple(UUID.randomUUID(), h.city.name, "delete-city-changed"),
                Triple(h.city.id, "wrong", "delete-name-mismatch"))) {
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
            every { h.store.delete(any()) } returns CompletableFuture.failedFuture(java.io.IOException("ack lost"))
            h.start()
            val deletion = h.service.deleteCity(h.player, city.id, city.name); h.tick(); h.commit()
            (shouldThrow<CompletionException> { deletion.join() }.cause as CivsRejection).reason shouldBe "storage-unavailable"
            h.service.city(h.player) shouldBe h.saves.single().first
            h.service.cityBusy(h.player) shouldBe true
            h.service.deleteCity(h.player, city.id, city.name).isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.delete(h.saves.single().first) }
            every { h.store.load() } returns CompletableFuture.completedFuture(emptyList())
            val reload = h.service.reload(h.player); h.tick(); reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
        }
    }

    test("remote membership never enters local simulation or mutation and uses a typed backend request") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("spawn")
            val record = CivsNetworkCity(BackendServerId.of("survival"), h.city.id, h.city.owner, h.city.name, 1000)
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

    test("fresh network read failure clears remote membership and reports an actionable rejection") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("parkour")
            every { h.store.lookup(h.owner) } returns CompletableFuture.completedFuture(CivsNetworkCity(BackendServerId.of("spawn"), h.city.id, h.city.owner, h.city.name, 1000))
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
            val current = CivsNetworkCity(BackendServerId.of("spawn"), h.city.id, h.city.owner, h.city.name, 2000)
            recent.complete(current); h.tick(); second.join()
            old.complete(CivsNetworkCity(BackendServerId.of("survival"), h.city.id, h.city.owner, h.city.name, 1000)); h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe current
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
            verify(exactly = 0) { h.citizens.advanceNavigationFrame() }
        }
    }

    test("one Core heartbeat activates navigation every tick only while the service is ready") {
        serviceScenario { h ->
            var activations = 0
            every { h.citizens.advanceNavigationFrame() } answers { activations++; emptyList() }
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
            every { h.citizens.advanceNavigationFrame() } answers { activations++; emptyList() }
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

    test("an assignment becomes visible only after its exact durable acknowledgement") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)!!
            val result = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            result.isDone shouldBe false
            h.service.city(h.player) shouldBe before
            h.saves.size shouldBe 1
            h.saves.single().first.workers.first().assignment shouldBe h.city.buildings.first().id
            h.commit()
            result.join() shouldBe Unit
            h.service.city(h.player)!!.workers.first().assignment shouldBe h.city.buildings.first().id
            h.service.city(h.player)!!.stock.food shouldBe 24
        }
    }

    test("an unknown write outcome blocks further mutations until authoritative reload") {
        serviceScenario { h ->
            h.start()
            val first = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            val persisted = h.saves.single().first
            h.saves.single().second.completeExceptionally(IllegalStateException("acknowledgement lost"))
            h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id).isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(persisted))
            val reload = h.service.reload(h.player)
            h.tick()
            reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe persisted
            h.service.city(h.player)!!.stock.food shouldBe 24
        }
    }

    test("stale persistence completion after shutdown cannot publish a replacement state") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)
            val result = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            h.tasks.close()
            h.service.close()
            h.commit()
            result.isCompletedExceptionally shouldBe true
            h.service.city(h.player) shouldBe before
            h.service.ready() shouldBe false
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
})
