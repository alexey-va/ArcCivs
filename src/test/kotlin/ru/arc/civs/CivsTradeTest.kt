package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import java.util.UUID

class CivsTradeTest : FunSpec({
    test("public emerald trade conserves native goods and recovery cannot pay twice") {
        val paper = MockBukkitTestRuntime.open()
        val world = paper.addSimpleWorld("trade")
        world.loadChunk(0, 0)
        val plugin = paper.createSimplePlugin("CivsTradeTest")
        val player = paper.addPlayer("MerchantVisitor")
        player.addAttachment(plugin, "arccivs.use", true)
        player.teleport(Location(world, 3.0, 64.0, 3.0))
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        val barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3),
            BuildingPhase.ACTIVE, control = CivPosition(0, 64, 0))
        val city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Trade", world.uid,
            CivPosition(0, 64, 0), 16, "nordic", hall.copy(phase = BuildingPhase.SITE), 1_000).copy(
            buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL))
        var persists = 0
        val persistence = object : PaperPlayerDataPersistence {
            override fun persist(player: org.bukkit.entity.Player) { persists++ }
        }
        val storage = CivsStorage(plugin, playerDataPersistence = persistence) { emptyArray() }
        try {
            barrel.setItem(0, ItemStack(Material.EMERALD, 2))
            player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 64))
            val id = UUID.randomUUID()
            storage.reserveTrade(city, id, player)
            val plan = storage.prepareTrade(city, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64))
            val tx = CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
                plan.changes, trade = plan.trade).validated()
            storage.seal(tx)
            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            barrel.storageContents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 64
            barrel.storageContents.filterNotNull().filter { it.type == Material.EMERALD }.sumOf { it.amount } shouldBe 1
            player.inventory.storageContents.filterNotNull().filter { it.type == Material.EMERALD }.sumOf { it.amount } shouldBe 1
            player.inventory.storageContents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 0
            (persists > 0) shouldBe true
            storage.unlock(id)

            // A buyer returns the same physical emerald; the town only releases half the bid bundle.
            val next = tx.after
            val buyId = UUID.randomUUID()
            storage.reserveTrade(next, buyId, player)
            val buyPlan = storage.prepareTrade(next, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32))
            val buy = CivInventoryTransaction(buyId, next.id, next.revision, next.copy(revision = next.revision + 1),
                buyPlan.changes, trade = buyPlan.trade).validated()
            storage.seal(buy)
            storage.reconcile(buy) shouldBe CivInventoryApplyResult.Applied
            player.inventory.storageContents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 32
            barrel.storageContents.filterNotNull().filter { it.type == Material.EMERALD }.sumOf { it.amount } shouldBe 2
            storage.unlock(buyId)

            shouldThrow<IllegalArgumentException> { buy.copy(trade = buy.trade!!.copy(offer = buy.trade.offer.copy(amount = 64))).validated() }
            shouldThrow<IllegalArgumentException> { buy.copy(changes = buy.changes.dropLast(1)).validated() }
            val loaded = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(buy), CivInventoryTransaction::class.java)
            loaded.validated() shouldBe buy

            val shortage = UUID.randomUUID()
            storage.reserveTrade(buy.after, shortage, player)
            shouldThrow<CivsRejection> {
                storage.prepareTrade(buy.after, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64))
            }.reason shouldBe "trade-player-goods-missing"
            storage.unlock(shortage)

            // Planning a rejected exchange must leave both inventories unchanged.
            player.inventory.clear()
            player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 64))
            barrel.clear()
            val poor = UUID.randomUUID()
            storage.reserveTrade(buy.after, poor, player)
            shouldThrow<CivsRejection> {
                storage.prepareTrade(buy.after, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64))
            }.reason shouldBe "trade-stock-changed"
            player.inventory.getItem(0)!!.amount shouldBe 64
            barrel.isEmpty shouldBe true
            storage.unlock(poor)

            player.inventory.storageContents = Array(36) { ItemStack(Material.DIRT, 64) }
            player.inventory.setItem(0, ItemStack(Material.EMERALD, 64))
            barrel.setItem(0, ItemStack(Material.COBBLESTONE, 64))
            val full = UUID.randomUUID()
            storage.reserveTrade(buy.after, full, player)
            shouldThrow<CivsRejection> {
                storage.prepareTrade(buy.after, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32))
            }.reason shouldBe "trade-player-full"
            barrel.getItem(0)!!.amount shouldBe 64
            player.inventory.getItem(0)!!.amount shouldBe 64
            storage.unlock(full)
        } finally { storage.close(); paper.close() }
    }
})
