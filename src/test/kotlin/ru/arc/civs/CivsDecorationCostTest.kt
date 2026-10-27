package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsDecorationCostTest : FunSpec({
    test("the complete captured bill projects exact timber units and every other billed unit separately") {
        val bill = mapOf("OAK_PLANKS" to 12L, "ACACIA_STAIRS" to 5L, "MANGROVE_LOG" to 3L,
            "COBBLESTONE" to 17L, "GLASS" to 4L, "CHEST" to 2L)
        CivsDecorationCost.cost(bill) shouldBe Stock(20, 23, 0)
        CivsAutonomy.cost(BuildingType.DECORATION, 1, bill) shouldBe Stock(20, 23, 0)
        CivsRules.cost(BuildingType.DECORATION, 1) shouldBe Stock()
    }
    test("missing and invalid bills never become free automatic decoration") {
        shouldThrowAny { CivsAutonomy.cost(BuildingType.DECORATION, 1) }
        shouldThrowAny { CivsDecorationCost.cost(mapOf("STONE" to -1L)) }
        CivsDecorationCost.cost(emptyMap()) shouldBe Stock()
        CivsDecorationCost.cost(mapOf("STONE" to 1L)) shouldBe Stock(stone = 1)
    }
})
