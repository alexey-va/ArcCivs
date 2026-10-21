package ru.arc.civs

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.util.Base64

/** Plain stacks keep the small legacy representation; custom components retain native bytes. */
data class CivResidentItem(val material: String, val amount: Int, val data: String? = null) {
    fun validated(): CivResidentItem = apply {
        require(material.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) && amount in 1..64)
        data?.let { require(it.length <= 32_768 && Base64.getDecoder().decode(it).size <= 24_576) }
    }

    fun stack(): ItemStack {
        validated()
        val type = Material.getMaterial(material)?.takeIf { it.isItem && !it.isAir }
            ?: throw CivsRejection("unsupported-material", mapOf("material" to material))
        val item = data?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) } ?: ItemStack(type)
        require(item.type == type && amount <= item.maxStackSize)
        return item.also { it.amount = amount }
    }

    companion object {
        fun of(item: ItemStack?): CivResidentItem? {
            if (item == null || item.type.isAir || item.amount <= 0) return null
            require(item.amount <= item.maxStackSize)
            val data = if (item.isSimilar(ItemStack(item.type))) null else
                Base64.getEncoder().encodeToString(item.clone().also { it.amount = 1 }.serializeAsBytes())
            return CivResidentItem(item.type.name, item.amount, data).validated()
        }
    }
}

internal object CivsEquipment {
    const val SLOTS = 33
    const val BAG = 6

    fun slots(worker: CivWorker): List<CivResidentItem?> = worker.items ?: MutableList<CivResidentItem?>(SLOTS) { null }.also { slots ->
        worker.inventory.orEmpty().forEach { (material, amount) ->
            val item = CivResidentItem(material, amount.toInt())
            val gear = armorSlot(material) ?: if (material != "BREAD" && slots[4] == null) 4 else null
            val slot = gear ?: (BAG until SLOTS).first { slots[it] == null }
            slots[slot] = item
        }
    }

    fun armorSlot(material: String): Int? = when {
        material.endsWith("_HELMET") || material in setOf("CARVED_PUMPKIN", "PLAYER_HEAD", "TURTLE_HELMET") -> 0
        material.endsWith("_CHESTPLATE") || material == "ELYTRA" -> 1
        material.endsWith("_LEGGINGS") -> 2
        material.endsWith("_BOOTS") -> 3
        else -> null
    }

    fun materials(worker: CivWorker): Map<String, Long> = slots(worker).filterNotNull()
        .groupingBy(CivResidentItem::material).fold(0L) { count, item -> count + item.amount }

    fun replace(worker: CivWorker, slots: List<CivResidentItem?>): CivWorker {
        validate(slots)
        return worker.copy(inventory = null, items = slots.toList())
    }

    fun plainCount(worker: CivWorker, material: String): Int = slots(worker).filterNotNull()
        .filter { it.material == material && it.data == null }.sumOf(CivResidentItem::amount)

    fun available(worker: CivWorker, stock: Map<String, Long>): Map<String, Long> = stock.toMutableMap().also { total ->
        slots(worker).filterNotNull().filter { it.data == null }.forEach { total.merge(it.material, it.amount.toLong(), Long::plus) }
    }

    /** Carried plain supplies are consumed first; native storage owns only the remainder. */
    fun storageInputs(worker: CivWorker, inputs: Map<String, Long>): Map<String, Long> = inputs.mapValues { (material, count) ->
        (count - plainCount(worker, material)).coerceAtLeast(0)
    }.filterValues { it > 0 }

    fun consumeInputs(worker: CivWorker, inputs: Map<String, Long>): CivWorker = inputs.entries.fold(worker) { current, (material, count) ->
        val carried = minOf(count, plainCount(current, material).toLong()).toInt()
        if (carried == 0) current else consume(current, material, carried)
    }

    fun supplyRoom(worker: CivWorker, material: Material): Int = slots(worker).drop(BAG).sumOf {
        when { it == null -> material.maxStackSize; it.material == material.name && it.data == null -> material.maxStackSize - it.amount; else -> 0 }
    }

    /** Plan a complete storage pickup; a full bag leaves the original equipment untouched. */
    fun collectSupplies(worker: CivWorker, supplies: Map<String, Long>): CivWorker {
        val items = slots(worker).toMutableList()
        supplies.forEach { (material, amount) ->
            val maximum = Material.valueOf(material).maxStackSize
            require(amount in 1..maximum.toLong())
            if (insert(items, CivResidentItem(material, amount.toInt()), amount.toInt(), maximum) != amount.toInt())
                throw CivsRejection("resident-inventory-full")
        }
        return replace(worker, items)
    }

    fun giveRequestedSupply(worker: CivWorker, player: Player, material: Material, amount: Long): Pair<CivWorker, List<CivNativePlayerSlot>> {
        require(amount in 1..material.maxStackSize.toLong())
        val items = slots(worker).toMutableList()
        val changes = mutableListOf<CivNativePlayerSlot>()
        var remaining = amount.toInt()
        for (slot in 0..35) {
            if (remaining == 0) break
            val native = player.inventory.getItem(slot)?.takeIf { it.type == material && it.amount > 0 && it.isSimilar(ItemStack(material)) } ?: continue
            val before = requireNotNull(CivResidentItem.of(native))
            val moved = insert(items, before, minOf(remaining, before.amount), material.maxStackSize)
            if (moved == 0) break
            changes += CivNativePlayerSlot(slot, before, before.copy(amount = before.amount - moved).takeIf { it.amount > 0 })
            remaining -= moved
        }
        if (changes.isEmpty()) throw CivsRejection(if (supplyRoom(worker, material) == 0) "resident-inventory-full" else "resident-item-missing")
        return replace(worker, items) to changes
    }

    fun requestedTool(city: Settlement, worker: CivWorker): Material? {
        val building = worker.assignment?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE } ?: return null
        val type = CivsRules.effectiveWorkType(city, building)
        if (type !in CivsWorksites.TYPES) return null
        val material = Material.valueOf(requireNotNull(CivsResidents.tool(type)))
        val suffix = material.name.substringAfter('_')
        return material.takeUnless { slots(worker)[4]?.material?.endsWith("_$suffix") == true }
    }

    /** Lowest native tier, then enchantments, remaining durability and stable player slot order. */
    fun cheapestTool(player: Player, requested: Material): Int? {
        val suffix = requested.name.substringAfter('_')
        val tiers = listOf("WOODEN", "GOLDEN", "STONE", "COPPER", "IRON", "DIAMOND", "NETHERITE")
        return (0..35).filter { index -> player.inventory.getItem(index)?.let {
            it.amount > 0 && it.type.name.substringAfter('_') == suffix && it.type.name.substringBefore('_') in tiers
        } == true }.minWithOrNull(compareBy<Int>(
            { tiers.indexOf(player.inventory.getItem(it)!!.type.name.substringBefore('_')) },
            { player.inventory.getItem(it)!!.enchantments.values.sum() + if (player.inventory.getItem(it)!!.itemMeta.isUnbreakable) 1000 else 0 },
            { index -> player.inventory.getItem(index)!!.let { it.type.maxDurability.toInt() - ((it.itemMeta as? org.bukkit.inventory.meta.Damageable)?.damage ?: 0) } },
            { it }))
    }

    fun canEquipRequestedTool(worker: CivWorker): Boolean = slots(worker).let { slots ->
        slots[4] == null || (BAG until SLOTS).any { slots[it] == null }
    }

    fun giveRequestedTool(worker: CivWorker, player: Player, requested: Material): Pair<CivWorker, List<CivNativePlayerSlot>> {
        val index = cheapestTool(player, requested) ?: throw CivsRejection("resident-item-missing")
        val before = requireNotNull(CivResidentItem.of(player.inventory.getItem(index)))
        val items = slots(worker).toMutableList()
        items[4]?.let { previous ->
            val free = (BAG until SLOTS).firstOrNull { items[it] == null } ?: throw CivsRejection("resident-inventory-full")
            items[free] = previous
        }
        items[4] = before.copy(amount = 1)
        return replace(worker, items) to listOf(CivNativePlayerSlot(index, before,
            before.copy(amount = before.amount - 1).takeIf { it.amount > 0 }))
    }

    fun supply(worker: CivWorker, material: String, returning: Boolean): CivWorker {
        if (returning) return consume(worker, material, 1)
        val slots = slots(worker).toMutableList()
        if (insert(slots, CivResidentItem(material, 1), 1, if (material == "BREAD") 16 else 1) == 0)
            throw CivsRejection("resident-inventory-full")
        return replace(worker, slots)
    }

    /** Plans only touched player slots; the resident's 33-slot record is already the city-owned state. */
    fun transfer(worker: CivWorker, player: Player, npcSlot: Int?, playerSlot: Int?, click: ClickType): Pair<CivWorker, List<CivNativePlayerSlot>> {
        require((npcSlot != null) != (playerSlot != null))
        require(click in setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT))
        val slots = slots(worker).toMutableList()
        val changes = mutableListOf<CivNativePlayerSlot>()
        if (playerSlot != null) {
            require(playerSlot in 0..35)
            val native = player.inventory.getItem(playerSlot)
            val item = CivResidentItem.of(native) ?: throw CivsRejection("resident-item-missing")
            val amount = if (click == ClickType.RIGHT) 1 else item.amount
            val moved = insert(slots, item, amount, requireNotNull(native).maxStackSize)
            if (moved == 0) throw CivsRejection("resident-inventory-full")
            changes += CivNativePlayerSlot(playerSlot, item, item.copy(amount = item.amount - moved).takeIf { it.amount > 0 })
        } else {
            val index = requireNotNull(npcSlot)
            require(index in 0 until SLOTS)
            val item = slots[index] ?: throw CivsRejection("resident-item-missing")
            var remaining = if (click == ClickType.RIGHT) 1 else item.amount
            val source = item.stack()
            val maximum = source.maxStackSize
            // Merge exact native payloads first, then fill empty player storage cells.
            for (empty in listOf(false, true)) for (slot in 0..35) {
                if (remaining == 0) break
                val native = player.inventory.getItem(slot)?.takeUnless { it.type.isAir || it.amount <= 0 }
                if ((native == null) != empty || native != null &&
                    (native.amount >= maximum || !native.isSimilar(source))) continue
                val before = CivResidentItem.of(native)
                if ((before == null) != empty || before != null && (before.material != item.material || before.data != item.data)) continue
                val moved = minOf(remaining, maximum - (before?.amount ?: 0))
                if (moved <= 0) continue
                changes += CivNativePlayerSlot(slot, before, item.copy(amount = (before?.amount ?: 0) + moved))
                remaining -= moved
            }
            val moved = (if (click == ClickType.RIGHT) 1 else item.amount) - remaining
            if (moved == 0) throw CivsRejection("storage-full")
            slots[index] = item.copy(amount = item.amount - moved).takeIf { it.amount > 0 }
        }
        return replace(worker, slots) to changes
    }

    private fun insert(slots: MutableList<CivResidentItem?>, item: CivResidentItem, amount: Int, maximum: Int): Int {
        var remaining = amount
        val gear = armorSlot(item.material) ?: if (maximum == 1) { if (item.material == "SHIELD") 5 else 4 } else null
        if (gear != null && slots[gear] == null) {
            slots[gear] = item.copy(amount = 1)
            remaining--
        }
        for (empty in listOf(false, true)) for (slot in BAG until SLOTS) {
            if (remaining == 0) break
            val before = slots[slot]
            if ((before == null) != empty || before != null && (before.material != item.material || before.data != item.data)) continue
            val moved = minOf(remaining, maximum - (before?.amount ?: 0))
            if (moved <= 0) continue
            slots[slot] = item.copy(amount = (before?.amount ?: 0) + moved)
            remaining -= moved
        }
        return amount - remaining
    }

    fun validate(slots: List<CivResidentItem?>) {
        require(slots.size == SLOTS)
        slots.forEachIndexed { index, item ->
            item?.validated()
            if (index < 4 && item != null) require(armorSlot(item.material) == index && item.amount == 1)
            if (index in 4..5 && item != null) require(item.amount == 1)
        }
    }

    fun consume(worker: CivWorker, material: String, amount: Int): CivWorker {
        require(amount > 0)
        val slots = slots(worker).toMutableList()
        var remaining = amount
        for (index in slots.indices) {
            val item = slots[index]?.takeIf { it.material == material && it.data == null } ?: continue
            val used = minOf(item.amount, remaining)
            slots[index] = if (used == item.amount) null else item.copy(amount = item.amount - used)
            remaining -= used
            if (remaining == 0) break
        }
        if (remaining > 0) throw CivsRejection("resident-item-missing")
        return replace(worker, slots)
    }
}
