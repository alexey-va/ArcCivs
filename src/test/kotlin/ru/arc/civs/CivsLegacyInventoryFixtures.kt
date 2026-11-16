package ru.arc.civs

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/** Pre-upgrade disk fixtures; production no longer writes a receipt for each new inventory operation. */
internal fun writeLegacyInventoryReceipt(root: Path, tx: CivInventoryTransaction, completed: Boolean = false) {
    val file = root.resolve("inventory-transactions/${tx.id}.json")
    Files.createDirectories(file.parent)
    Files.writeString(file, Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "transaction" to tx)))
    if (completed) {
        val head = root.resolve("inventory-completed/${tx.cityId}.json")
        Files.createDirectories(head.parent)
        Files.writeString(head, Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION,
            "cityId" to tx.cityId, "transactionId" to tx.id, "afterRevision" to tx.after.revision)))
    }
}
