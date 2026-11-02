package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsControlRelocationTest : FunSpec({
    test("a fresh authored half slab candidate is admitted without relaxing resident support") {
        ControlRelocationFixture().use { h ->
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.original, h.hall)))
            h.storage.updateCity(h.city)
            val support = civsControlSupport(h.replacement, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")!!
            h.block(support.position).blockData = org.bukkit.Bukkit.createBlockData(support.before)
            citizenStandable(h.world, h.replacement) shouldBe false
            h.durable()
            var now = 0L
            h.relocations(candidates = { CompletableFuture.completedFuture(listOf(h.candidate(h.replacement, false).copy(support = support))) },
                clock = { now }).use { owner ->
                repeat(4) { owner.pulse(); now += 1_000L }
                h.city.pendingControls!!.single().approach shouldBe listOf(support)
                h.block(support.position).blockData.asString shouldBe support.after
                h.stagingBarriers shouldBe 1
                h.stagingFence.complete(Unit); h.ticks()
                h.fence.complete(Unit); h.ticks(); owner.drained().join()
                h.building.control shouldBe h.replacement
                h.block(h.old).type shouldBe Material.AIR
            }
        }
    }

    test("matching half slab support uses the durable control receipt and preserves exact cargo on replay") {
        ControlRelocationFixture().use { h ->
            val support = civsControlSupport(h.replacement, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")!!
            h.block(support.position).blockData = org.bukkit.Bukkit.createBlockData(support.before)
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            h.durable()
            h.acceptIntent(staging = true)
            h.mutate(h.city.id) { city -> CivsRules.validate(city.copy(revision = city.revision + 1,
                pendingControls = listOf(city.pendingControls!!.single().copy(approach = listOf(support))))) }.join()
            h.reload()
            val operation = h.installations.install(h.city.id, h.original.id)
            h.block(support.position).blockData.asString shouldBe support.after
            h.block(h.replacement).type shouldBe Material.BARREL
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.building.control shouldBe h.old
            // The unchanged after-image is accepted after a restart; no second slab or item is issued.
            h.reload()
            h.stagingFence.complete(Unit); h.ticks(); operation.join()
            h.relocations().use { owner ->
                owner.pulse(); h.ticks()
                h.fence.complete(Unit); h.ticks(); owner.drained().join()
            }
            h.block(support.position).blockData.asString shouldBe support.after
            h.building.control shouldBe h.replacement
            h.replacementInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.block(h.old).type shouldBe Material.AIR
            h.city.pendingControls shouldBe emptyList()
        }
    }

    test("support drift and unsupported floor changes cannot mutate the receiver") {
        ControlRelocationFixture().use { h ->
            val support = civsControlSupport(h.replacement, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")!!
            h.acceptIntent(staging = true)
            h.city = CivsRules.validate(h.city.copy(pendingControls = listOf(h.city.pendingControls!!.single().copy(approach = listOf(support)))))
            h.block(support.position).type = Material.DIAMOND_BLOCK
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.original.id) }.reason shouldBe "world-changed"
            h.block(h.replacement).type shouldBe Material.AIR
            for (bad in listOf(support.copy(after = "minecraft:cobblestone"),
                support.copy(position = support.position.copy(x = support.position.x + 1))))
                shouldThrow<CivsRejection> { CivsRules.validate(h.city.copy(pendingControls = listOf(
                    h.city.pendingControls!!.single().copy(approach = listOf(bad))))) }.reason shouldBe "invalid-state"
        }
    }

    test("a viewer closing the old inventory cannot bypass support state or occupancy readback") {
        for (occupied in listOf(false, true)) ControlRelocationFixture().use { h ->
            val support = civsControlSupport(h.replacement, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")!!
            h.block(support.position).blockData = org.bukkit.Bukkit.createBlockData(support.before)
            h.acceptIntent(staging = true)
            h.city = CivsRules.validate(h.city.copy(pendingControls = listOf(h.city.pendingControls!!.single().copy(approach = listOf(support)))))
            val viewer = mockk<org.bukkit.entity.HumanEntity>()
            every { viewer.closeInventory() } answers {
                if (occupied) h.installations.controlOccupied = { _, p -> p == support.position }
                else h.block(support.position).type = Material.DIAMOND_BLOCK
            }
            (h.oldInventory as org.mockbukkit.mockbukkit.inventory.InventoryMock).addViewer(viewer)
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.original.id) }.reason shouldBe
                if (occupied) "worksite-blocked" else "world-changed"
            h.block(h.replacement).type shouldBe Material.AIR
            h.block(support.position).blockData.asString shouldBe if (occupied) support.before else "minecraft:diamond_block"
            h.block(h.old).type shouldBe Material.BARREL
            io.mockk.verify(exactly = 1) { viewer.closeInventory() }
        }
    }

    test("completed asynchronous sources survive more than 64 current control identities") {
        ControlRelocationFixture().use { h ->
            val cities = List(67) { index ->
                CivsRules.validate(h.city.copy(id = UUID(182, index.toLong()), buildings = listOf(
                    h.hall.copy(id = UUID(183, index.toLong())))))
            }
            val requests = mutableMapOf<UUID, Int>()
            val observed = mutableSetOf<UUID>()
            val outstanding = mutableListOf<Pair<CivBuilding, CompletableFuture<List<CivControlCandidate>>>>()
            var now = 0L
            CivsControlRelocations(h.plugin, h.tasks, h.worldAdapter, h.storage, h.installations, { building ->
                requests.merge(building.id, 1, Int::plus)
                object : CompletableFuture<List<CivControlCandidate>>() {
                    override fun getNow(valueIfAbsent: List<CivControlCandidate>): List<CivControlCandidate> {
                        observed += building.id
                        return super.getNow(valueIfAbsent)
                    }
                }.also { outstanding += building to it }
            }, { cities }, { true }, { _, _, _, _ -> error("An existing interior marker must stay") },
                { _, _ -> error("An existing interior marker must stay") }, { now }).use { owner ->
                repeat(cities.size * 3) {
                    owner.pulse()
                    // Decoding finishes after this pulse, before the next one. No native arrival is simulated.
                    outstanding.forEach { (building, future) -> future.complete(listOf(CivControlCandidate(
                        building.control!!, building.control!!.copy(z = building.control!!.z + 1), existing = true))) }
                    outstanding.clear()
                    now += 5_000L
                }
                requests.values.maxOrNull() shouldBe 1
                observed shouldBe cities.map { it.buildings.single().id }.toSet()
            }
        }
    }

    test("a selected async source starts its real relocation on the next pulses and keeps one active lifecycle") {
        ControlRelocationFixture().use { h ->
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.original, h.hall)))
            h.storage.updateCity(h.city)
            h.durable()
            val item = ItemStack(Material.DIAMOND, 7).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Original selected control")) }
            }
            h.oldInventory.setItem(0, item)
            val decoded = CompletableFuture<List<CivControlCandidate>>()
            var requests = 0
            var now = 0L
            h.relocations(candidates = { building ->
                building.id shouldBe h.original.id
                requests++
                decoded
            }, clock = { now }).use { owner ->
                owner.pulse()
                val standing = h.replacement.copy(z = h.replacement.z + 1)
                decoded.complete(listOf(CivControlCandidate(h.replacement, standing, existing = false,
                    route = listOf(standing.copy(z = standing.z + 1), standing))))
                now = 1_000L; owner.pulse()
                h.mutations shouldBe 0
                now = 2_000L; owner.pulse()
                // The native proof may yield after its first step at the existing 2 ms budget.
                if (h.stagingBarriers == 0) { now = 3_000L; owner.pulse() }
                h.stagingBarriers shouldBe 1
                h.city.pendingControls!!.size shouldBe 1
                h.building.control shouldBe h.old
                repeat(10) { now += 1_000L; owner.pulse() }
                requests shouldBe 1
                h.stagingBarriers shouldBe 1
                h.transactions shouldBe 0
                h.oldInventory.getItem(0) shouldBe item
                h.stagingFence.complete(Unit); h.ticks()
                h.fence.complete(Unit); h.ticks()
                owner.drained().join()
                h.transactions shouldBe 1
                h.building.control shouldBe h.replacement
                h.block(h.old).type shouldBe Material.AIR
                h.replacementInventory.getItem(0) shouldBe item
                h.city.pendingControls shouldBe emptyList()
            }
        }
    }

    test("unfinished source work does not stall other buildings and is retired only when its identity disappears") {
        ControlRelocationFixture().use { h ->
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.original, h.hall)))
            val sources = mutableMapOf<CivBuilding, CompletableFuture<List<CivControlCandidate>>>()
            var now = 0L
            h.relocations(candidates = { building ->
                CompletableFuture<List<CivControlCandidate>>().also { sources[building] = it }
            }, clock = { now }).use { owner ->
                owner.pulse()
                now = 1_000L; owner.pulse()
                sources.keys shouldBe setOf(h.original, h.hall)
                val changed = h.original.copy(schematicId = "fixture/replaced-source")
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(changed, h.hall)))
                now = 2_000L; owner.pulse()
                sources.getValue(h.original).isCancelled shouldBe true
                sources.getValue(h.hall).isCancelled shouldBe false
                sources.containsKey(changed) shouldBe true
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.hall)))
                now = 3_000L; owner.pulse()
                sources.getValue(changed).isCancelled shouldBe true
                sources.getValue(h.hall).isCancelled shouldBe false
                h.mutations shouldBe 0
            }
            sources.getValue(h.hall).isCancelled shouldBe true
        }
    }

    test("full authored receivers cannot crowd a spare air control out of the bounded native selection") {
        ControlRelocationFixture().use { h ->
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.original, h.hall)))
            h.storage.updateCity(h.city)
            h.durable()
            val items = List(STORAGE_SLOTS) { slot -> ItemStack(Material.DIAMOND, slot + 1).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Crowded source slot $slot")) }
            } }
            items.forEachIndexed { slot, item -> h.oldInventory.setItem(slot, item) }
            val authored = (2..9).map { CivPosition(it, 64, 2) }
            authored.forEach { point ->
                h.block(point).type = Material.BARREL
                val inventory = (h.block(point).state as Barrel).inventory
                repeat(STORAGE_SLOTS) { inventory.setItem(it, ItemStack(Material.STONE, 64)) }
            }
            val fullBefore = authored.map { (h.block(it).state as Barrel).inventory.storageContents.toList() }
            var now = 0L
            h.relocations(candidates = { building -> CompletableFuture.completedFuture(
                if (building.id == h.original.id) authored.map { h.candidate(it, true) } + h.candidate(h.replacement, false)
                else listOf(h.candidate(building.control!!, true))) }, clock = { now }).use { owner ->
                repeat(4) { owner.pulse(); now += 1_000L }
                h.city.pendingControls!!.single().position shouldBe h.replacement
                h.oldInventory.storageContents.toList() shouldBe items
                h.transactions shouldBe 0
                h.stagingFence.complete(Unit); h.ticks()
                h.fence.complete(Unit); h.ticks(); owner.drained().join()
                h.building.control shouldBe h.replacement
                h.replacementInventory.storageContents.toList() shouldBe items
                h.block(h.old).type shouldBe Material.AIR
                h.transactions shouldBe 1
                authored.map { (h.block(it).state as Barrel).inventory.storageContents.toList() } shouldBe fullBefore
            }
        }
    }

    test("a persisted full authored receiver replans metadata before the same exact-slot transfer") {
        ControlRelocationFixture().use { h ->
            h.durable()
            val items = List(STORAGE_SLOTS) { slot -> ItemStack(Material.DIAMOND, slot + 1).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Pending source slot $slot")) }
            } }
            items.forEachIndexed { slot, item -> h.oldInventory.setItem(slot, item) }
            h.block(h.replacement).type = Material.BARREL
            repeat(STORAGE_SLOTS) { h.replacementInventory.setItem(it, ItemStack(Material.STONE, 64)) }
            h.block(h.receiver).type = Material.AIR
            h.storage.refreshBuilding(h.city, h.building)
            val fullBefore = h.replacementInventory.storageContents.toList()
            h.acceptIntent(staging = true, existingReceiver = true)
            h.reload()
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            h.fence = CompletableFuture.completedFuture(Unit)
            var now = 0L
            val choices: (CivBuilding) -> CompletableFuture<List<CivControlCandidate>> = { building ->
                CompletableFuture.completedFuture(if (building.id == h.original.id)
                    listOf(h.candidate(h.replacement, true), h.candidate(h.receiver, false))
                else listOf(h.candidate(building.control!!, true)))
            }
            h.relocations(candidates = choices, clock = { now }).use { owner ->
                owner.pulse(); h.ticks()
                h.city.pendingControls!!.single().position shouldBe h.replacement
                h.transactions shouldBe 0
                now = 5_000L; owner.pulse(); h.ticks()
                h.city.pendingControls shouldBe emptyList()
                h.building.control shouldBe h.old
                h.oldInventory.storageContents.toList() shouldBe items
                h.replacementInventory.storageContents.toList() shouldBe fullBefore
                h.block(h.receiver).type shouldBe Material.AIR
            }
            h.reload()
            h.relocations(candidates = choices, clock = { now }).use { owner ->
                repeat(4) { now += 1_000L; owner.pulse(); h.ticks() }
                owner.drained().join()
            }
            h.building.control shouldBe h.receiver
            (h.block(h.receiver).state as Barrel).inventory.storageContents.toList() shouldBe items
            h.replacementInventory.storageContents.toList() shouldBe fullBefore
            h.block(h.old).type shouldBe Material.AIR
            h.city.pendingControls shouldBe emptyList()
            h.transactions shouldBe 1
        }
    }

    for (condition in listOf("created-receiver", "changed-authored-receiver", "approach"))
    test("a blocked $condition intent retains its protected receiver and all original items") {
        ControlRelocationFixture().use { h ->
            h.durable()
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 64))
            h.block(h.replacement).type = Material.BARREL
            h.block(h.replacement).blockData = org.bukkit.Bukkit.createBlockData("minecraft:barrel[facing=north,open=false]")
            repeat(STORAGE_SLOTS) { h.replacementInventory.setItem(it, ItemStack(Material.STONE, 64)) }
            repeat(STORAGE_SLOTS) { h.receiverInventory.setItem(it, ItemStack(Material.STONE, 64)) }
            h.acceptIntent(staging = true, existingReceiver = condition != "created-receiver")
            if (condition == "approach") {
                val pending = h.city.pendingControls!!.single()
                h.city = CivsRules.validate(h.city.copy(pendingControls = listOf(pending.copy(approach = listOf(
                    CivPlanBlock(h.original.entrance, "minecraft:stone", "minecraft:air"))))))
            }
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            val spare = CivPosition(8, 64, 8)
            var now = 0L
            h.relocations(candidates = { CompletableFuture.completedFuture(listOf(h.candidate(spare, false))) },
                clock = { now }).use { owner ->
                owner.pulse(); h.ticks()
                if (condition == "changed-authored-receiver") h.block(h.replacement).blockData =
                    org.bukkit.Bukkit.createBlockData("minecraft:barrel[facing=south,open=false]")
                val pending = h.city.pendingControls
                now = 5_000L; owner.pulse(); h.ticks()
                h.city.pendingControls shouldBe pending
                h.building.control shouldBe h.old
                h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 64)
                h.replacementInventory.storageContents.filterNotNull().sumOf { it.amount } shouldBe STORAGE_SLOTS * 64
                h.block(spare).type shouldBe Material.AIR
                h.transactions shouldBe 0
            }
        }
    }

    test("a busy retirement commit waits five seconds then resumes the same receipt without transferring again") {
        ControlRelocationFixture().use { h ->
            h.durable()
            val item = ItemStack(Material.DIAMOND, 7)
            h.oldInventory.setItem(0, item)
            h.acceptIntent(staging = true)
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            h.fence = CompletableFuture.completedFuture(Unit)
            h.rejectRetirementOnce = true
            var now = 0L
            h.relocations(candidates = { building -> CompletableFuture.completedFuture(listOf(CivControlCandidate(
                building.control!!, building.control!!.copy(z = building.control!!.z + 1), existing = true)))
            }, clock = { now }).use { owner ->
                owner.pulse(); h.ticks()
                h.rejectRetirementOnce shouldBe false
                h.city.pendingControls!!.size shouldBe 1
                h.building.control shouldBe h.replacement
                h.block(h.old).type shouldBe Material.AIR
                h.replacementInventory.getItem(0) shouldBe item
                h.transactions shouldBe 1
                val attempts = h.retirementAttempts
                for (time in 1_000L..4_000L step 1_000L) { now = time; owner.pulse(); h.ticks() }
                h.retirementAttempts shouldBe attempts
                h.city.pendingControls!!.size shouldBe 1
                now = 5_000L; owner.pulse(); h.ticks(); owner.drained().join()
                h.city.pendingControls shouldBe emptyList()
                h.retirementAttempts shouldBe attempts + 1
                h.transactions shouldBe 1
                h.replacementInventory.getItem(0) shouldBe item
            }
        }
    }

    test("a full control stages its own empty receiver and moves all 27 exact slots without another container") {
        ControlRelocationFixture(BuildingType.TOWN_HALL).use { h ->
            h.durable()
            h.block(h.receiver).type = Material.AIR
            val items = List(STORAGE_SLOTS) { slot -> ItemStack(Material.DIAMOND, slot + 1).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Original slot $slot")) }
            } }
            items.forEachIndexed { slot, item -> h.oldInventory.setItem(slot, item) }
            h.acceptIntent(staging = true)
            h.storage.refreshBuilding(h.city, h.building)
            val saved = h.store.load().join().single()
            saved.building(h.original.id)!!.control shouldBe h.old
            saved.pendingControls shouldBe h.city.pendingControls
            h.relocations().use { owner ->
                owner.pulse()
                h.building.control shouldBe h.old
                h.oldInventory.storageContents.toList() shouldBe items
                h.replacementInventory.storageContents.toList() shouldBe List(STORAGE_SLOTS) { null }
                h.transactions shouldBe 0
                h.stagingBarriers shouldBe 1
                h.stagingFence.complete(Unit)
                h.ticks()
                h.lastTransaction!!.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(h.old, h.replacement)
                h.transactions shouldBe 1
                h.building.control shouldBe h.replacement
                h.block(h.old).type shouldBe Material.AIR
                h.replacementInventory.storageContents.toList() shouldBe items
                h.city.pendingControls!!.size shouldBe 1
                h.fence.complete(Unit)
                h.ticks()
                owner.drained().join()
            }
            h.store.load().join().single() shouldBe h.city
            h.city.pendingControls shouldBe emptyList()
            h.barriers shouldBe 1
            h.replacementInventory.storageContents.toList() shouldBe items
        }
    }

    test("failed staging acknowledgement restarts from the same protected receiver while the old control retains its goods") {
        ControlRelocationFixture().use { h ->
            h.durable()
            val item = ItemStack(Material.BREAD, 64)
            h.oldInventory.setItem(26, item)
            h.acceptIntent(staging = true)
            val first = h.installations.install(h.city.id, h.building.id)
            h.stagingFence.completeExceptionally(IOException("receiver save acknowledgement lost"))
            shouldThrow<CompletionException> { first.join() }
            h.oldInventory.getItem(26) shouldBe item
            h.building.control shouldBe h.old
            h.reload()
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            h.fence = CompletableFuture.completedFuture(Unit)
            h.relocations().use { owner -> owner.pulse(); h.ticks(); owner.drained().join() }
            h.building.control shouldBe h.replacement
            h.block(h.old).type shouldBe Material.AIR
            h.replacementInventory.storageContents.filterNotNull() shouldBe listOf(item)
            h.city.pendingControls shouldBe emptyList()
            h.transactions shouldBe 1
            h.stagingBarriers shouldBe 2
        }
    }

    test("restart reconciles mixed old and new slots before publishing the new control and never credits twice") {
        ControlRelocationFixture().use { h ->
            h.durable()
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            h.oldInventory.setItem(26, ItemStack(Material.BREAD, 64))
            h.acceptIntent(staging = true)
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            h.installations.install(h.city.id, h.building.id).join()
            val tx = h.prepare()
            h.store.saveInventoryTransaction(tx).join()
            val destination = tx.containerRelocation!!.containers.single { it.position == h.replacement }
            h.oldInventory.setItem(0, null)
            h.replacementInventory.setItem(0, destination.after[0]!!.stack())
            h.storage.unlock(tx.id)
            h.reload()
            val bootstrap = h.journal.bootstrap()
            h.ticks(); bootstrap.join()
            h.journal.pending(h.city.id) shouldBe true
            h.relocations().use { owner -> owner.pulse(); owner.drained().join() }
            h.building.control shouldBe h.old
            h.oldInventory.getItem(26) shouldBe ItemStack(Material.BREAD, 64)
            h.journal.recover(); h.ticks()
            h.journal.pending(h.city.id) shouldBe false
            h.building.control shouldBe h.old
            h.oldInventory.storageContents.toList() shouldBe List(STORAGE_SLOTS) { null }
            h.replacementInventory.storageContents.map(CivResidentItem::of) shouldBe destination.after
            // Another restart after the exact transfer, but before the control-reference commit.
            h.reload()
            h.fence = CompletableFuture.completedFuture(Unit)
            h.relocations().use { owner -> owner.pulse(); h.ticks(); owner.drained().join() }
            h.journal.recover(); h.ticks()
            h.transactions shouldBe 0
            h.building.control shouldBe h.replacement
            h.city.pendingControls shouldBe emptyList()
            h.replacementInventory.storageContents.map(CivResidentItem::of) shouldBe destination.after
            h.store.loadInventoryTransactions().join() shouldBe emptyList()
        }
    }

    test("staging never clears an existing native receiver and malformed staged references cannot be persisted") {
        ControlRelocationFixture().use { h ->
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            h.acceptIntent(staging = true)
            h.block(h.replacement).type = Material.BARREL
            val native = ItemStack(Material.EMERALD, 5).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Existing native goods")) }
            }
            h.replacementInventory.setItem(20, native)
            h.stagingFence = CompletableFuture.completedFuture(Unit)
            h.installations.install(h.city.id, h.building.id).join()
            h.replacementInventory.getItem(20) shouldBe native
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.building.control shouldBe h.old
            val pending = h.city.pendingControls!!.single()
            shouldThrow<CivsRejection> { CivsRules.validate(h.city.copy(pendingControls = listOf(pending.copy(
                position = h.old.copy(x = 30))))) }.reason shouldBe "invalid-state"
            shouldThrow<CivsRejection> { CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id == h.original.id) it.copy(control = h.receiver) else it })) }.reason shouldBe "invalid-state"
        }
    }

    test("every functional type accepts conserved native evacuation and a recoverable control retirement") {
        for (type in BuildingType.entries.filter(CivsBuildingControls::supported)) {
            ControlRelocationFixture(type).use { h ->
                h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
                val tx = h.prepare()
                h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
                h.oldInventory.getItem(0) shouldBe null
                h.receiverInventory.contents.filterNotNull().filter { it.type == Material.DIAMOND }.sumOf { it.amount } shouldBe 7
                h.storage.unlock(tx.id)
                h.acceptIntent()
                h.city.pendingControls!!.single().retire!!.position shouldBe h.old
                h.block(h.old).type shouldBe Material.BARREL
            }
        }
    }

    test("same-level evacuation preserves exact native metadata in existing holders and never writes an uncreated control") {
        ControlRelocationFixture().use { h ->
            val named = ItemStack(Material.DIAMOND, 7).apply { itemMeta = itemMeta.apply { displayName(Component.text("Keep exact metadata")) } }
            h.oldInventory.setItem(0, named)
            h.receiverInventory.setItem(0, ItemStack(Material.EMERALD, 5))
            val tx = h.prepare()
            h.block(h.replacement).type shouldBe Material.AIR
            h.oldInventory.getItem(0) shouldBe named
            tx.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(h.old, h.receiver)
            h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
            h.oldInventory.getItem(0) shouldBe null
            h.receiverInventory.contents.filterNotNull().single { it.type == Material.DIAMOND } shouldBe named
            h.receiverInventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 5)
            h.block(h.replacement).type shouldBe Material.AIR
            // Replaying the same accepted exact receipt cannot add a second copy.
            h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
            h.receiverInventory.contents.filterNotNull().filter { it.type == Material.DIAMOND }.sumOf { it.amount } shouldBe 7
            h.storage.unlock(tx.id)
        }
    }

    test("a full existing receiver leaves every source item and native block untouched") {
        ControlRelocationFixture().use { h ->
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            repeat(STORAGE_SLOTS) { h.receiverInventory.setItem(it, ItemStack(Material.STONE, 64)) }
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            shouldThrow<CivsRejection> { h.storage.prepareControlRelocation(h.city, h.building, h.old, h.replacement) }.reason shouldBe "storage-full"
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.receiverInventory.contents.filterNotNull().sumOf { it.amount } shouldBe STORAGE_SLOTS * 64
            h.block(h.replacement).type shouldBe Material.AIR
            h.storage.unlock(id)
        }
    }

    test("pending retirement protects both native blocks and coalesces the native persistence barrier") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val player = h.paper.addPlayer("ControlObserver")
            val oldBreak = BlockBreakEvent(h.block(h.old), player)
            h.storage.onBreak(oldBreak)
            oldBreak.isCancelled shouldBe true
            val first = h.installations.install(h.city.id, h.building.id)
            val second = h.installations.install(h.city.id, h.building.id)
            (first === second) shouldBe true
            h.barriers shouldBe 1
            h.mutations shouldBe 0
            h.block(h.old).type shouldBe Material.AIR
            h.block(h.replacement).type shouldBe Material.BARREL
            h.city.pendingControls!!.size shouldBe 1
            val replacementBreak = BlockBreakEvent(h.block(h.replacement), player)
            h.storage.onBreak(replacementBreak)
            replacementBreak.isCancelled shouldBe true
            h.fence.complete(Unit)
            first.join().pendingControls shouldBe emptyList()
            h.mutations shouldBe 1
            h.barriers shouldBe 1
        }
    }

    // Pinned MockBukkit InventoryMock.getHolder(boolean) throws UnimplementedOperationException;
    // BarrelInventoryMock inherits it. Keep native hopper insertion as a separate uncovered scenario.
    test("pending retirement cancels native InventoryMove inserts - MockBukkit getHolder(boolean) unsupported").config(enabled = false) {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val late = InventoryMoveItemEvent(h.oldInventory, ItemStack(Material.STONE), h.receiverInventory, true)
            h.storage.onMove(late)
            late.isCancelled shouldBe true
            h.installations.install(h.city.id, h.building.id)
            val newInventory = (h.block(h.replacement).state as Barrel).inventory
            val insert = InventoryMoveItemEvent(h.receiverInventory, ItemStack(Material.STONE), newInventory, true)
            h.storage.onMove(insert)
            insert.isCancelled shouldBe true
        }
    }

    test("unknown retirement persistence survives restart and retries only the AIR fence without debiting inventories") {
        ControlRelocationFixture().use { h ->
            h.receiverInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            h.acceptIntent()
            val first = h.installations.install(h.city.id, h.building.id)
            h.fence.completeExceptionally(IOException("unknown chunk acknowledgement"))
            shouldThrow<CompletionException> { first.join() }
            h.city.pendingControls!!.size shouldBe 1
            h.block(h.old).type shouldBe Material.AIR
            val unrelated = controlTestOrder(h.hall.copy(level = 2))
            h.city = CivsRules.validate(h.city.copy(construction = unrelated))
            h.city = CivsRules.validate(Gson().fromJson(Gson().toJson(h.city), Settlement::class.java))
            h.storage.updateCity(h.city)
            h.fence = CompletableFuture.completedFuture(Unit)
            var debits = 0
            CivsControlRelocations(h.plugin, h.tasks, h.worldAdapter, h.storage, h.installations,
                { CompletableFuture.completedFuture(emptyList()) }, { listOf(h.city) }, { true },
                { _, _, _, _ -> debits++; error("An absent old barrel must never be debited again") }, h::mutate, { 10_000L }).use { owner ->
                owner.pulse()
                owner.drained().join()
            }
            debits shouldBe 0
            h.city.pendingControls shouldBe emptyList()
            h.city.construction shouldBe unrelated
            h.receiverInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.barriers shouldBe 2
        }
    }

    test("late source items permissions and occupied replacement cells defer retirement without dropping items") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "storage-busy"
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
            h.block(h.old).type shouldBe Material.BARREL
            h.city.pendingControls!!.size shouldBe 1
            h.barriers shouldBe 0
        }
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.installations.controlOccupied = { _, point -> point == h.replacement }
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "worksite-blocked"
            h.block(h.replacement).type shouldBe Material.AIR
            h.block(h.old).type shouldBe Material.BARREL
            h.barriers shouldBe 0
        }
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.denied = h.old
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "lands-denied"
            h.block(h.old).type shouldBe Material.BARREL
            h.barriers shouldBe 0
        }
    }

    test("the retiring building cannot queue or reserve its own upgrade while unrelated work remains legal") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val city = h.city.copy(buildings = h.city.buildings.map { if (it.id == h.hall.id) it.copy(level = 2) else it })
            shouldThrow<CivsRejection> { CivsRules.enqueue(city, city.owner, h.original.id) }.reason shouldBe "city-busy"
            val clear = city.copy(pendingControls = emptyList())
            val queued = CivsRules.enqueue(clear, clear.owner, h.original.id)
            queued.constructionQueue shouldBe listOf(h.original.id)
            val upgrade = h.building.copy(level = 2, schematicId = "fixture/smeltery2")
            val order = controlTestOrder(upgrade)
            val pending = city.copy(constructionQueue = queued.constructionQueue)
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(pending, pending.owner, upgrade, order) }.reason shouldBe "city-busy"
            CivsRules.reserveBuild(queued, queued.owner, upgrade, order).construction shouldBe order
        }
    }
})

private fun controlTestOrder(building: CivBuilding): CivConstruction = CivConstruction(UUID.randomUUID(), building.id, building.level,
    1, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
    batches = CivConstructionBatches(1, 1), materials = emptyMap(), payment = CivConstructionPayment())

private class ControlRelocationFixture(type: BuildingType = BuildingType.SMELTERY) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("ControlRelocationTest")
    val world = CivsWorksiteWorldFixture("control-relocation").also(paper.server::addWorld)
        .also { it.loadChunk(0, 0); it.loadChunk(1, 0) }
    val old = CivPosition(11, 64, 10)
    val replacement = CivPosition(4, 64, 4)
    val receiver = CivPosition(6, 64, 6)
    val original = CivBuilding(UUID(0, 2), type, 1, CivPosition(10, 63, 10), 0, "fixture/smeltery",
        CivBox(CivPosition(0, 63, 0), CivPosition(20, 70, 10)), CivPosition(10, 64, 11), BuildingPhase.ACTIVE, old)
    val hall = CivBuilding(UUID(0, 3), if (type == BuildingType.TOWN_HALL) BuildingType.BUILDER_HUT else BuildingType.TOWN_HALL,
        1, CivPosition(24, 63, 12), 0, "fixture/hall",
        CivBox(CivPosition(23, 63, 11), CivPosition(26, 68, 14)), CivPosition(24, 64, 15), BuildingPhase.ACTIVE, CivPosition(24, 64, 12))
    var city = CivsRules.validate(Settlement(UUID(0, 4), UUID(0, 5), "Controls", world.uid, CivPosition(10, 64, 10), 96,
        "fixture", Stock(), listOf(hall, original), emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
    val building get() = requireNotNull(city.building(original.id))
    var denied: CivPosition? = null
    var fence = CompletableFuture<Unit>()
    var stagingFence = CompletableFuture<Unit>()
    var barriers = 0
    var stagingBarriers = 0
    var mutations = 0
    var transactions = 0
    var rejectRetirementOnce = false
    var retirementAttempts = 0
    var lastTransaction: CivInventoryTransaction? = null
    private val busy = mutableSetOf<UUID>()
    private val unhealthy = mutableSetOf<UUID>()
    private var durableRoot: java.nio.file.Path? = null
    val store by lazy { CivsStore(Files.createTempDirectory("control-relocation-").also { durableRoot = it }, Executor { it.run() }) }
    val journal by lazy {
        CivsInventoryJournal(plugin, tasks, store, storage, { mapOf(city.id to city) }, busy, unhealthy, { false },
            { saved -> city = saved; storage.updateCity(saved); CompletableFuture.completedFuture(Unit) },
            ::mutate, {}, {}, { _, _, _ -> null }, {}, { _, _ -> city }, {}, { 0L })
    }
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val worldAdapter = mockk<CivsWorld>(relaxed = true).also { adapter -> every { adapter.canModify(any(), any(), any()) } answers {
        val block = secondArg<org.bukkit.block.Block>()
        CivPosition(block.x, block.y, block.z) != denied
    } }
    val storage = CivsStorage(plugin, tileEntities = { chunk -> listOf(old, receiver, hall.control!!, replacement)
        .filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z }.map(::block).filter { it.type.name in CIVS_STORAGE_TYPES }
        .map { it.state }.toTypedArray() })
    val installations = CivsControlInstallations(plugin, worldAdapter, storage, mockk(relaxed = true), { mapOf(city.id to city) },
        busy, unhealthy, { true }, ::mutate)
    val oldInventory get() = (block(old).state as Barrel).inventory
    val replacementInventory get() = (block(replacement).state as Barrel).inventory
    val receiverInventory get() = (block(receiver).state as Chest).blockInventory
    init {
        block(old).type = Material.BARREL
        block(receiver).type = Material.CHEST
        block(requireNotNull(hall.control)).type = Material.BARREL
        storage.foodCanModify = { _, point -> point != denied }
        storage.relocationCurrentCity = { city.takeIf { candidate -> candidate.id == it } }
        storage.persistControlRetirementNative = { _, receipt ->
            receipt.retire!!.position shouldBe old
            block(old).type shouldBe Material.AIR
            block(replacement).type shouldBe Material.BARREL
            barriers++
            fence
        }
        storage.persistControlStagingNative = { _, receipt ->
            receipt.retire!!.position shouldBe old
            building.control shouldBe old
            block(old).type shouldBe Material.BARREL
            block(replacement).type shouldBe Material.BARREL
            stagingBarriers++
            stagingFence
        }
        storage.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
        storage.replaceCities(listOf(city))
    }
    fun block(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z)
    fun mutate(id: UUID, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
        id shouldBe city.id
        val next = transform(city)
        if (city.pendingControls.orEmpty().isNotEmpty() && next.pendingControls.orEmpty().isEmpty()) {
            retirementAttempts++
            if (rejectRetirementOnce) {
                rejectRetirementOnce = false
                return CompletableFuture.failedFuture(CivsRejection("city-busy"))
            }
        }
        city = if (durableRoot == null) next else store.save(next).join()
        storage.updateCity(city)
        mutations++
        return CompletableFuture.completedFuture(city)
    }
    fun acceptIntent(staging: Boolean = false, existingReceiver: Boolean = false) {
        val receipt = CivControlPlacement(original.id, replacement, if (existingReceiver) block(replacement).blockData.asString else "minecraft:air", retire = CivPlanBlock(old,
            block(old).blockData.asString, "minecraft:air"))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == original.id && !staging) it.copy(control = replacement) else it },
            pendingControls = listOf(receipt), revision = city.revision + 1))
        if (durableRoot != null) store.save(city).join()
        storage.updateCity(city)
    }
    fun prepare(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val move = requireNotNull(storage.prepareControlRelocation(city, building, old, replacement))
        return CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1), emptyList(),
            containerRelocation = move).also { storage.seal(it) }
    }
    fun durable() { store.save(city).join() }
    fun reload() { city = store.load().join().single(); storage.updateCity(city) }
    fun ticks() { paper.performTicks(20) }
    fun candidate(point: CivPosition, existing: Boolean): CivControlCandidate {
        val standing = point.copy(z = point.z + 1)
        return CivControlCandidate(point, standing, existing, route = listOf(standing.copy(z = standing.z + 1), standing))
    }
    fun relocations(candidates: (CivBuilding) -> CompletableFuture<List<CivControlCandidate>> = { CompletableFuture.completedFuture(emptyList()) },
        clock: () -> Long = { 10_000L }): CivsControlRelocations = CivsControlRelocations(plugin, tasks, worldAdapter, storage, installations,
        candidates, { listOf(city) },
        { it.id !in busy && it.id !in unhealthy && !journal.pending(it.id) }, { saved, current, from, to ->
            val id = UUID.randomUUID()
            storage.reserve(saved, id)
            val move = try { storage.prepareControlRelocation(saved, current, from, to) }
                catch (failure: Throwable) { storage.unlock(id); return@CivsControlRelocations CompletableFuture.failedFuture(failure) }
            if (move == null) {
                storage.unlock(id)
                CompletableFuture.completedFuture(saved)
            } else {
                val tx = CivInventoryTransaction(id, saved.id, saved.revision, saved.copy(revision = saved.revision + 1),
                    emptyList(), containerRelocation = move).also(storage::seal)
                transactions++; lastTransaction = tx
                busy += saved.id
                journal.submitRelocation(saved, tx)
            }
        }, ::mutate, clock)
    override fun close() {
        tasks.close(); storage.close(); paper.close()
        durableRoot?.let { root -> store.close(); root.toFile().deleteRecursively() }
    }
}
