package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Item identity is a typed version tag; translated names and lore are presentation only. */
internal object CivsResurrectionScroll {
    private val key = NamespacedKey("arccivs", "resurrection_scroll")

    fun create(text: CivsText, player: Player?): ItemStack = ItemStack(Material.PAPER, 1).apply {
        editMeta { meta ->
            meta.displayName(text.render(player, "resurrection.item-name").color(TextColor.color(0x9BD48D)))
            meta.lore(listOf(
                Component.empty().decoration(TextDecoration.ITALIC, false),
                text.render(player, "resurrection.item-purpose"),
                text.render(player, "resurrection.item-condition").color(TextColor.color(0xB8B8B8)),
                Component.empty().decoration(TextDecoration.ITALIC, false),
                text.render(player, "resurrection.item-use"),
            ))
            meta.setEnchantmentGlintOverride(true)
            meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
        }
    }

    fun matches(item: ItemStack?): Boolean = item != null && item.type == Material.PAPER && item.amount > 0 &&
        item.itemMeta.persistentDataContainer.get(key, PersistentDataType.BYTE) == 1.toByte()
}
