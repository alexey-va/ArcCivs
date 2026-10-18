package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CivsDiagnosticsTest : FunSpec({
    test("diagnostic inputs stay on one bounded line and cannot inject control characters") {
        val line = CivsDiagnostics.line("ui_click", "city_name" to "Oak\nTown\r\u0000\u001b\t\"".repeat(1000), "outcome" to "accepted")
        line.startsWith("ARC_CIVS event=ui_click city_name=") shouldBe true
        line.none(Char::isISOControl) shouldBe true
        (line.length < 700) shouldBe true
        line shouldContain "outcome=accepted"
        line shouldContain "\\\""
    }

    test("nested completion failures retain the typed rejection and its literal detail") {
        val rejection = CivsRejection("wrong-world", mapOf("world" to "<red>unsafe</red>"))
        val failure = java.util.concurrent.CompletionException(java.util.concurrent.ExecutionException(rejection))
        CivsDiagnostics.rejection(failure) shouldBe rejection
        CivsDiagnostics.cause(failure) shouldBe rejection
    }
})
