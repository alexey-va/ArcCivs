package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsResidentFoodPropertiesTest : FunSpec({
    test("native component adapter preserves apple nutrition and duration while retaining the existing bread rule") {
        MockBukkitTestRuntime.open().use {
            val properties = mockk<FoodProperties> { every { nutrition() } returns 4 }
            val consume = mockk<Consumable> {
                every { animation() } returns ItemUseAnimation.EAT
                every { consumeSeconds() } returns 1.6f
                every { consumeEffects() } returns emptyList()
            }
            // MockBukkit omits vanilla prototype FOOD defaults; inject the exact native read boundary.
            val stack = mockk<ItemStack> {
                every { type } returns Material.APPLE
                every { getData(DataComponentTypes.FOOD) } returns properties
                every { getData(DataComponentTypes.CONSUMABLE) } returns consume
                every { hasData(DataComponentTypes.USE_REMAINDER) } returns false
            }
            CivsResidentFoods.fromNative(stack) shouldBe CivResidentFoodDefinition(4)
            every { stack.type } returns Material.BREAD
            every { properties.nutrition() } returns 5
            CivsResidentFoods.fromNative(stack) shouldBe CivResidentFoodDefinition(6)
            every { stack.hasData(DataComponentTypes.USE_REMAINDER) } returns true
            CivsResidentFoods.fromNative(stack) shouldBe null
            every { stack.hasData(DataComponentTypes.USE_REMAINDER) } returns false
            every { consume.consumeEffects() } returns listOf(mockk())
            CivsResidentFoods.fromNative(stack) shouldBe null
        }
    }
})
