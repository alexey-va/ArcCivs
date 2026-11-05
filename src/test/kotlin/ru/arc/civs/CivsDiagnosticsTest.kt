package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

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

    test("stale worksite source unwraps failures and skips the real worksite guard") {
        val rejection = requireNotNull(runCatching(::staleWorksiteDiagnosticCaller).exceptionOrNull())
        val failure = CompletionException(ExecutionException(rejection))
        val source = requireNotNull(CivsDiagnostics.rejectionSource(failure)).split(':')
        source[0] shouldBe "CivsDiagnosticsTest.kt"
        (source[1].toInt() > 0) shouldBe true
        source[2] shouldBe "staleWorksiteDiagnosticCaller"
    }

    test("source skips exact guard owners and module suffixes but keeps domain methods") {
        val rejection = CivsRejection("stale-worksite").apply {
            stackTrace = arrayOf(
                StackTraceElement("ru.arc.civs.CivsRules", "reject\$ArcCivs", "CivsRules.kt", 742),
                StackTraceElement("ru.arc.civs.CivsRules", "check\$ArcCivs", "CivsRules.kt", 741),
                StackTraceElement("ru.arc.civs.CivsWorksiteContextKt", "requireWorksite", "CivsWorksiteContext.kt", 194),
                StackTraceElement("ru.arc.civs.CivsWorkerCommits", "rejectIf", "CivsWorkerCommits.kt", 259),
                StackTraceElement("ru.arc.civs.CivsAutonomousWorksites", "requireWork", "CivsAutonomousWorksites.kt", 148),
                StackTraceElement("ru.arc.civs.CivsWorldKt", "access\$requireCivs", "CivsWorld.kt", 1246),
                StackTraceElement("ru.arc.civs.CivsRoadWorldKt", "requireCivs", "CivsRoadWorld.kt", 419),
                StackTraceElement("ru.arc.civs.CivsMineWorksite", "check\$ArcCivs", "CivsMineWorksite.kt", 208),
                StackTraceElement("ru.arc.civs.CivsMineWorksite", "validate", "CivsMineWorksite.kt", 300),
            )
        }
        CivsDiagnostics.rejectionSource(rejection) shouldBe "CivsMineWorksite.kt:208:check\$ArcCivs"
    }

    test("source omits other reasons and absent foreign or unusable frames within its limit") {
        val valid = StackTraceElement("ru.arc.civs.CivsMineWorksite", "validate", "CivsMineWorksite.kt", 208)
        val foreign = StackTraceElement("other.Worker", "validate", "Worker.kt", 208)
        CivsDiagnostics.rejectionSource(null) shouldBe null
        CivsDiagnostics.rejectionSource(IllegalStateException("private-exception-payload")) shouldBe null
        CivsDiagnostics.rejectionSource(CivsRejection("wrong-world").apply { stackTrace = arrayOf(valid) }) shouldBe null
        listOf(
            emptyArray(),
            arrayOf(foreign),
            arrayOf(StackTraceElement(valid.className, valid.methodName, null, 208)),
            arrayOf(StackTraceElement(valid.className, valid.methodName, "Unknown Source", 208)),
            arrayOf(StackTraceElement(valid.className, valid.methodName, "dir/CivsMineWorksite.kt", 208)),
            arrayOf(StackTraceElement(valid.className, valid.methodName, valid.fileName, 0)),
            arrayOf(StackTraceElement(valid.className, valid.methodName, valid.fileName, -1)),
            arrayOf(StackTraceElement(valid.className, "", valid.fileName, 208)),
            Array(16) { foreign } + valid,
        ).forEach { frames ->
            CivsDiagnostics.rejectionSource(CivsRejection("stale-worksite").apply { stackTrace = frames }) shouldBe null
        }
    }

    test("source stays bounded and its field exposes no exception payload or control characters") {
        val rejection = CivsRejection("stale-worksite", mapOf("payload" to "private-rejection-payload")).apply {
            stackTrace = arrayOf(StackTraceElement("ru.arc.civs.CivsMineWorksite", "deliver\n" + "x".repeat(200),
                "Civs" + "x".repeat(200) + ".kt", Int.MAX_VALUE))
        }
        val source = requireNotNull(CivsDiagnostics.rejectionSource(CompletionException("private-exception-payload", rejection)))
        (source.length <= 240) shouldBe true
        val parts = source.split(':')
        parts[0].length shouldBe 96
        parts[2].length shouldBe 96
        val line = CivsDiagnostics.line("operation_end", "rejection_source" to source)
        line.none(Char::isISOControl) shouldBe true
        line.contains("private-exception-payload") shouldBe false
        line.contains("private-rejection-payload") shouldBe false
        line shouldContain "rejection_source="
    }
})

private fun staleWorksiteDiagnosticCaller() { requireWorksite(false, "stale-worksite") }
