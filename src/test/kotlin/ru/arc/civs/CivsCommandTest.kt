package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsCommandTest : FunSpec({
    test("suggestions stay within exact syntax and never request Bukkit player fallback") {
        CivsCommand.completions(false, true, arrayOf("")) shouldBe emptyList()
        CivsCommand.completions(true, false, arrayOf("")) shouldBe listOf("preview")
        CivsCommand.completions(true, true, arrayOf("r")) shouldBe listOf("reload")
        CivsCommand.completions(true, true, arrayOf("status", "")) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("unknown")) shouldBe emptyList()
    }
})
