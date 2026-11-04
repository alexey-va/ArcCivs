package ru.arc.civs

import org.bukkit.entity.Player
import java.util.UUID

enum class CivTradeDirection { BUY, SELL }
data class CivTradeOffer(val material: String, val direction: CivTradeDirection, val amount: Int)
data class CivTradeView(val cityId: UUID, val cityName: String, val marketId: UUID,
    val emeralds: Long, val offers: List<CivTradeOffer>, val refreshing: Boolean = false)

/** Saved terms and exact native slots, replayed by the ordinary inventory journal. */
data class CivTradeReceipt(val playerId: UUID, val marketId: UUID, val offer: CivTradeOffer,
    val changes: List<CivNativePlayerSlot>, val version: Int = 1) {
    fun validated(city: Settlement, slots: List<CivInventorySlotChange>) {
        require(version == 1 && city.inventory?.mode == CivStorageMode.PHYSICAL && city.pendingDeletion == null)
        val market = requireNotNull(city.building(marketId))
        require(market.phase == BuildingPhase.ACTIVE && market.type in setOf(BuildingType.WAREHOUSE, BuildingType.TOWN_HALL))
        require(CivsTrade.bundle(offer.material, offer.direction) == offer.amount)
        require(changes.size in 1..PLAYER_STORAGE_SLOTS && changes.map { it.slot }.distinct().size == changes.size)
        val native = linkedMapOf<String, Long>()
        fun count(target: MutableMap<String, Long>, material: String, amount: Long) {
            target.merge(material, amount, Math::addExact)
        }
        slots.forEach { change ->
            require(change.container == market.control || market.bounds.contains(change.container.x, change.container.y, change.container.z))
            change.before?.let { count(native, it.material, -it.amount.toLong()) }
            change.after?.let { count(native, it.material, it.amount.toLong()) }
        }
        val sign = if (offer.direction == CivTradeDirection.SELL) 1L else -1L
        val expected = mapOf(offer.material to sign * offer.amount, "EMERALD" to -sign)
        require(native.filterValues { it != 0L } == expected) { "Trade must exchange the accepted goods and one emerald" }
        changes.forEach { change ->
            require(change.slot in 0 until PLAYER_STORAGE_SLOTS && change.before != change.after)
            listOfNotNull(change.before, change.after).forEach { require(it.data == null); it.validated() }
            change.before?.let { count(native, it.material, -it.amount.toLong()) }
            change.after?.let { count(native, it.material, it.amount.toLong()) }
        }
        require(native.values.all { it == 0L }) { "Trade must conserve every item" }
    }
}

/** One physical emerald per bundle; the ask supplies half the bid quantity. No money is issued. */
internal object CivsTrade {
    private val bids = buildMap {
        for (wood in listOf("OAK", "SPRUCE", "BIRCH", "DARK_OAK", "ACACIA", "JUNGLE", "MANGROVE", "CHERRY")) {
            put("${wood}_LOG", 32); put("${wood}_PLANKS", 128)
        }
        putAll(mapOf("COBBLESTONE" to 64, "COBBLED_DEEPSLATE" to 64, "STONE" to 64,
            "COAL" to 16, "CHARCOAL" to 16, "RAW_IRON" to 16, "IRON_INGOT" to 8,
            "BREAD" to 16, "WHEAT" to 48, "GLASS" to 32))
    }

    fun bundle(material: String, direction: CivTradeDirection): Int? = bids[material]?.let {
        if (direction == CivTradeDirection.SELL) it else it / 2
    }

    fun market(city: Settlement): CivBuilding = city.buildings.firstOrNull {
        it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null
    } ?: city.buildings.firstOrNull {
        it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE && it.control != null
    } ?: throw CivsRejection("trade-market-unavailable")

    fun checkAccess(city: Settlement, player: Player): CivBuilding {
        if (!player.isOnline || !player.hasPermission("arccivs.use")) throw CivsRejection("not-authorized")
        if (city.pendingDeletion != null || city.inventory?.mode != CivStorageMode.PHYSICAL) throw CivsRejection("trade-market-unavailable")
        val market = market(city)
        val point = market.entrance
        if (player.world.uid != city.worldId || player.location.toVector().distanceSquared(
                org.bukkit.util.Vector(point.x + .5, point.y.toDouble(), point.z + .5)) > 16.0 * 16.0)
            throw CivsRejection("trade-too-far")
        return market
    }

    fun offers(city: Settlement, snapshot: CivSupplySnapshot, stock: Map<String, Long>): List<CivTradeOffer> {
        val desired = linkedMapOf<String, Long>()
        val construction = city.construction?.takeIf { it.payment != null && it.materials != null }
        val buffers = construction?.let { CivsRules.constructionStores(city, it.buildingId).mapTo(hashSetOf(), CivBuilding::id) }.orEmpty()
        snapshot.stores.values.forEach { store ->
            // Every builder buffer protects the same bill locally; commerce reserves it once city-wide.
            val reserve = if (store.id in buffers) CivsLogistics.desiredStock(city, requireNotNull(city.building(store.id)))
                else store.desiredStock
            reserve.forEach { (material, amount) ->
                desired.merge(material, amount, Math::addExact)
            }
        }
        construction?.materials?.let(CivsMaterials::unpaid)?.forEach { (material, amount) ->
            desired.merge(material, amount, Math::addExact)
        }
        val available = linkedMapOf<String, Long>()
        snapshot.stores.values.forEach { store -> store.stock.forEach { (material, amount) -> available.merge(material, amount, Math::addExact) } }
        city.workers.forEach { worker ->
            CivsEquipment.slots(worker).filterNotNull().filter { it.data == null }.forEach {
                available.merge(it.material, it.amount.toLong(), Math::addExact)
            }
        }
        return buildList {
            bids.forEach { (material, bid) ->
                val reserve = desired[material] ?: 0L
                val current = available[material] ?: 0L
                if ((stock["EMERALD"] ?: 0L) > 0 && reserve - current >= bid)
                    add(CivTradeOffer(material, CivTradeDirection.SELL, bid))
                val ask = bid / 2
                if ((stock[material] ?: 0L) >= ask && current - reserve >= ask)
                    add(CivTradeOffer(material, CivTradeDirection.BUY, ask))
            }
        }
    }
}
