package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.UUID

class CivsNamesTest : FunSpec({
    val empty = Settlement(UUID(0, 1), UUID(0, 2), "Town", UUID(0, 3), CivPosition(0, 64, 0),
        64, "fixture", Stock(), emptyList(), emptyList())

    test("gendered UUID choices stay stable and supply at least eighty distinct names per sex") {
        val bySex = CivSex.entries.map { sex ->
            var city = empty
            repeat(80) { index ->
                val id = UUID(sex.ordinal.toLong(), index.toLong())
                val name = CivsNames.name(city, id, sex)
                CivsNames.name(city, id, sex) shouldBe name
                name.any(Char::isDigit) shouldBe false
                city = city.copy(workers = city.workers + CivWorker(id, name, null, sex = sex))
                CivsNames.name(city, id, sex) shouldBe name
            }
            city.workers.map { it.name }.toSet().also { it.size shouldBe 80 }
        }
        bySex[0].intersect(bySex[1]).isEmpty() shouldBe true
    }

    test("existing names are preserved and new names avoid case-insensitive collisions") {
        val id = UUID(1, 1)
        val saved = CivWorker(id, "Старое имя", null)
        CivsNames.name(empty.copy(workers = listOf(saved)), id, CivSex.FEMALE) shouldBe saved.name
        val next = UUID(2, 2)
        val proposed = CivsNames.name(empty, next, CivSex.MALE)
        val occupied = empty.copy(workers = listOf(saved.copy(name = proposed.uppercase(Locale.ROOT))))
        (CivsNames.name(occupied, next, CivSex.MALE) != proposed) shouldBe true
    }
})
