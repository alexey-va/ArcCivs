package ru.arc.civs

import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

internal const val MATERIALIZATION_SLOT_CHANGES = 1_024

internal data class CivStorageSource(val position: CivPosition, val type: String, val inventory: Inventory)
internal data class CivStorageSnapshot(val source: CivStorageSource, val before: List<ItemStack?>, val after: MutableList<ItemStack?>)

/** Detached plain-slot edits shared by debit, credit and native receipt classification. */
internal object CivsStorageItems {
    fun debit(snapshots: Sequence<CivStorageSnapshot>, missing: MutableMap<String, Long>, candidate: ((CivPosition) -> Boolean)? = null) {
        var changedSlots = 0
        val iterator = snapshots.iterator()
        while (missing.isNotEmpty() && iterator.hasNext()) {
            val snapshot = iterator.next()
            var contributed = false
            snapshot.after.indices.forEach { slot ->
                val item = plain(snapshot.after[slot])
                val remaining = item?.let { missing[it.material] } ?: 0L
                if (remaining > 0 && item != null) {
                    reject(changedSlots == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                    val taken = minOf(remaining, item.amount.toLong()).toInt()
                    putAmount(missing, item.material, remaining - taken)
                    snapshot.after[slot] = stack(item.material, item.amount - taken)
                    changedSlots++
                    contributed = true
                }
            }
            if (contributed) candidate?.invoke(snapshot.source.position)
        }
    }

    fun snapshot(source: CivStorageSource): CivStorageSnapshot {
        val before = source.inventory.storageContents.map { it?.clone() }
        require(before.size == STORAGE_SLOTS)
        return CivStorageSnapshot(source, before, before.map { it?.clone() }.toMutableList())
    }

    fun plan(inventory: CivInventory, snapshots: List<CivStorageSnapshot>): CivStoragePlan {
        val changes = mutableListOf<CivInventorySlotChange>()
        snapshots.forEach { snapshot ->
            snapshot.before.indices.forEach { slot ->
                val before = plain(snapshot.before[slot])
                val after = plain(snapshot.after[slot])
                if (before != after) {
                    reject(changes.size == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                    changes += CivInventorySlotChange(snapshot.source.position, snapshot.source.type, slot, before, after)
                }
            }
        }
        return CivStoragePlan(inventory.validated(), changes.toList())
    }

    fun insert(snapshots: List<CivStorageSnapshot>, material: String, amount: Long): Long = insert(snapshots.asSequence(), material, amount)

    fun insert(snapshots: Sequence<CivStorageSnapshot>, material: String, amount: Long,
        changed: MutableSet<Pair<CivPosition, Int>>? = null): Long {
        var remaining = amount
        var writes = 0
        val limit = requireNotNull(stack(material, 1)).maxStackSize
        // Finish each source before proceeding: the control remains primary even when another chest has a partial stack.
        for (snapshot in snapshots) for (empty in listOf(false, true)) for (slot in snapshot.after.indices) {
            if (remaining == 0L) return 0
            val current = snapshot.after[slot]
            val item = plain(current)
            if (if (empty) !isEmpty(current) else item?.material != material) continue
            val count = if (empty) 0 else requireNotNull(item).amount
            val added = minOf(remaining, (limit - count).coerceAtLeast(0).toLong()).toInt()
            if (added > 0) {
                if (changed != null) {
                    val key = snapshot.source.position to slot
                    if (key !in changed && changed.size >= MATERIALIZATION_SLOT_CHANGES) return remaining
                    changed += key
                } else reject(writes == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                snapshot.after[slot] = stack(material, count + added)
                writes++
            }
            remaining -= added
        }
        return remaining
    }

    fun validateAmounts(amounts: Map<String, Long>) {
        require(amounts.size <= CivInventory.MAX_MATERIALS)
        amounts.forEach { (material, count) ->
            require(count in 1..CivInventory.MAX_AMOUNT)
            requireNotNull(stack(material, 1))
        }
    }

    fun putAmount(amounts: MutableMap<String, Long>, material: String, amount: Long) {
        require(amount in 0..CivInventory.MAX_AMOUNT)
        if (amount == 0L) amounts.remove(material) else amounts[material] = amount
    }

    fun plain(item: ItemStack?): CivPlainItem? {
        // Equality with the native default prototype covers metadata/components without relying on empty-meta representation.
        if (isEmpty(item) || !item!!.isSimilar(ItemStack(item.type))) return null
        if (item.amount !in 1..minOf(64, item.maxStackSize)) return null
        return CivPlainItem(item.type.name, item.amount)
    }

    fun matches(item: ItemStack?, expected: CivPlainItem?): Boolean = if (expected == null) isEmpty(item) else plain(item) == expected
    fun isEmpty(item: ItemStack?): Boolean = item == null || item.type.isAir || item.amount <= 0
    fun stack(material: String, count: Int): ItemStack? {
        if (count == 0) return null
        val type = Material.getMaterial(material)?.takeIf { it.isItem && !it.isAir }
            ?: throw CivsRejection("unsupported-material", mapOf("material" to material))
        return ItemStack(type, count)
    }
    fun itemDescription(item: ItemStack?): String = if (isEmpty(item)) "AIR" else "${item!!.type.name}:${item.amount}${if (plain(item) == null) ":custom" else ""}"
    fun matchesNative(item: ItemStack?, expected: CivResidentItem?): Boolean =
        if (expected == null) isEmpty(item) else !isEmpty(item) && item!!.amount == expected.amount && item.isSimilar(expected.stack())

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
