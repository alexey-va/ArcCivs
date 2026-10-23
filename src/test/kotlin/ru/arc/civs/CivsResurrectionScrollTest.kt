package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.Locale

class CivsResurrectionScrollTest : FunSpec({
    test("issued scroll is a single tagged item with readable purpose and one isolated native action") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val player = paper.addPlayer("Reader")
                val text = CivsText.fromYaml(civsLocaleSource())
                for ((language, name, action) in listOf(
                    Triple("ru", "Свиток воскрешения", "[▶] ПКМ — по телу жителя"),
                    Triple("en", "Resurrection scroll", "[▶] Right-click a resident’s body"),
                )) {
                    player.setLocale(Locale.forLanguageTag(language))
                    val item = CivsResurrectionScroll.create(text, player)
                    item.type shouldBe Material.PAPER
                    item.amount shouldBe 1
                    CivsResurrectionScroll.matches(item) shouldBe true
                    val meta = item.itemMeta
                    plainText(meta.displayName()!!) shouldBe name
                    meta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                    val lore = meta.lore()!!
                    lore.map(::plainText).mapIndexedNotNull { index, value -> index.takeIf { value.isEmpty() } } shouldBe listOf(0, 3)
                    lore.all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE } shouldBe true
                    plainText(lore.last()) shouldBe action
                    meta.enchantmentGlintOverride shouldBe true
                    CivsResurrectionScroll.matches(ItemStack.deserializeBytes(item.serializeAsBytes())) shouldBe true
                }
            }
        }
    }

    test("names and wrong typed or versioned tags cannot forge a scroll") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use {
                val text = CivsText.fromYaml(civsLocaleSource())
                val key = NamespacedKey("arccivs", "resurrection_scroll")
                val renamed = ItemStack(Material.PAPER).apply { editMeta { it.displayName(Component.text("Свиток воскрешения")) } }
                CivsResurrectionScroll.matches(renamed) shouldBe false
                for (invalid in listOf(
                    CivsResurrectionScroll.create(text, null).apply { type = Material.BOOK },
                    CivsResurrectionScroll.create(text, null).apply { editMeta { it.persistentDataContainer.set(key, PersistentDataType.INTEGER, 1) } },
                    CivsResurrectionScroll.create(text, null).apply { editMeta { it.persistentDataContainer.set(key, PersistentDataType.BYTE, 2.toByte()) } },
                    CivsResurrectionScroll.create(text, null).apply { amount = 0 },
                )) CivsResurrectionScroll.matches(invalid) shouldBe false
                CivsResurrectionScroll.matches(null) shouldBe false
                val genuine = CivsResurrectionScroll.create(text, null).apply {
                    amount = 16
                    editMeta { it.displayName(Component.text("Подарок")) }
                }
                CivsResurrectionScroll.matches(genuine) shouldBe true
            }
        }
    }
})
