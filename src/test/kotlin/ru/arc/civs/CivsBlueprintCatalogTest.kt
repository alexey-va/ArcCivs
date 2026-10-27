package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsBlueprintCatalogTest : FunSpec({
    test("the inventory records rejections but exposes only complete accepted families") {
        val accepted = catalogFamily("acacia", BuildingType.HOUSE, "fundamentals/citizen")
        val rejected = accepted.first().deepCopy().also { it.addProperty("sourceId", "rejected/source"); it.addProperty("verdict", "REJECTED")
            it.add("reasons", Gson().toJsonTree(listOf("unsafe-source"))) }
        val catalog = parseCatalog(accepted + rejected)
        catalog.sourceCount shouldBe 4
        catalog.rejectedCount shouldBe 1
        catalog.choices(BuildingType.HOUSE).size shouldBe 1
        catalog.choices(BuildingType.HOUSE).single().size shouldBe listOf(7, 8, 9)
        shouldThrowAny { parseCatalog(accepted.dropLast(1)) }
        shouldThrowAny { parseCatalog(accepted + accepted.first()) }
    }
    test("new automatic sites mix styles when the preferred style lacks a capable family") {
        val local = catalogFamily("shire", BuildingType.HOUSE, "fundamentals/small", maximum = 1)
        val fallback = catalogFamily("caledonia", BuildingType.HOUSE, "fundamentals/citizen")
        val catalog = parseCatalog(local + fallback)
        catalog.default("shire", BuildingType.HOUSE, 1)?.style shouldBe "shire"
        catalog.autonomous("shire", BuildingType.HOUSE)?.style shouldBe "caledonia"
        catalog.autonomous("sandstone", BuildingType.HOUSE)?.id shouldBe catalog.autonomous("sandstone", BuildingType.HOUSE)?.id
        catalog.choices(BuildingType.HOUSE, "shire").single().maxLevel shouldBe 1
    }
    test("accepted upgrade keeps its exact family and revision even when a new default sorts first") {
        val old = catalogFamily("acacia", BuildingType.HOUSE, "fundamentals/citizen", revision = "b".repeat(20))
        val current = catalogFamily("acacia", BuildingType.HOUSE, "fundamentals/alternative", revision = "c".repeat(20))
        val catalog = parseCatalog(current, retained = old)
        catalog.choices(BuildingType.HOUSE).single().id shouldBe current.first()["schematicId"].asString
        catalog.upgrade(old.first()["schematicId"].asString, 3)?.id shouldBe old.last()["schematicId"].asString
        catalog.upgrade(old.first()["schematicId"].asString, 4) shouldBe null
    }
    test("frame drift and absent check evidence fail closed before source loading") {
        for (mutation in listOf<(JsonObject) -> Unit>(
            { it.add("origin", Gson().toJsonTree(listOf(2, 2, 9))) },
            { it.add("checks", JsonArray()) },
            { it.addProperty("sha256", "changed") },
        )) {
            val rows = catalogFamily("acacia", BuildingType.HOUSE, "fundamentals/citizen")
            mutation(rows.last())
            shouldThrowAny { parseCatalog(rows) }
        }
    }
    test("decoration variants keep category size and a single supported level") {
        val rows = catalogFamily("acacia", BuildingType.DECORATION, "decorations/fountains/fountain", maximum = 1)
        val catalog = parseCatalog(rows)
        val choice = catalog.choices(BuildingType.DECORATION).single()
        choice.category shouldBe "water"
        choice.maxLevel shouldBe 1
        catalog.smallDecorations("acacia").single().id shouldBe choice.id
        val invalid = rows.single().deepCopy().also { it.addProperty("maxSupportedLevel", 2); it.addProperty("referenceLevel", 2) }
        shouldThrowAny { parseCatalog(listOf(invalid)) }
    }
})

private fun parseCatalog(rows: List<JsonObject>, retained: List<JsonObject> = emptyList()): CivBlueprintCatalog {
    val document = Gson().toJsonTree(mapOf("schemaVersion" to 2, "completeInventory" to true, "privateAssetsOnly" to true,
        "recipeSha256" to "a".repeat(64), "sourceInventorySha256" to "b".repeat(64), "totalSources" to rows.size,
        "entries" to rows, "retainedEntries" to retained)).asJsonObject
    return CivBlueprintCatalog.parse(document, rows.size)
}

private fun catalogFamily(style: String, type: BuildingType, family: String, maximum: Int = 3,
    revision: String = "a".repeat(20)): List<JsonObject> = (1..maximum).map { level ->
    Gson().toJsonTree(mapOf("sourceId" to "source/$style/$family$level", "sourceSha256" to "1".repeat(64),
        "convertedSha256" to "2".repeat(64), "schematicId" to "civs/v2/$style/${family.replace('/', '-')}/$revision/level$level",
        "style" to style, "family" to family, "revision" to revision, "type" to type.name, "level" to level,
        "maxSupportedLevel" to maximum, "referenceLevel" to maximum, "size" to listOf(7, 8, 9),
        "origin" to listOf(3, 1, 9), "control" to listOf(4, 2, 8), "surface" to 100, "sha256" to "3".repeat(64),
        "verdict" to "READY", "reasons" to emptyList<String>(), "dining" to emptyList<List<Int>>(),
        "checks" to listOf("source-hashes", "shared-max-level-frame", "ground-origin", "front-door-route", "one-control",
            "storage-provenance", "fluid-boundary", "full-cell-readback"))).asJsonObject
}
