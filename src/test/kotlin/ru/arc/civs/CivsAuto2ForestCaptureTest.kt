package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.civs.scenarios.RecordedNativeMap
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Local, opt-in production-derived evidence. Unknown cells are never treated as air. */
class CivsAuto2ForestCaptureTest : FunSpec({
    val statePath = System.getenv("CIVS_AUTO2_FOREST_CITY")
    val stateHash = System.getenv("CIVS_AUTO2_FOREST_CITY_SHA256")
    val terrainPath = System.getenv("CIVS_AUTO2_FOREST_TERRAIN")
    val terrainHash = System.getenv("CIVS_AUTO2_FOREST_TERRAIN_SHA256")
    test("current forester and the observed part of its field load with unknown cells retained")
        .config(enabled = statePath != null && stateHash != null && terrainPath != null && terrainHash != null) {
            val state = Files.readAllBytes(Path.of(requireNotNull(statePath)))
            val actual = MessageDigest.getInstance("SHA-256").digest(state).joinToString("") { "%02x".format(it) }
            check(actual == stateHash) { "Settlement snapshot hash mismatch" }
            val city = Gson().fromJson(state.toString(Charsets.UTF_8), Settlement::class.java)
            CivsRules.validate(city)
            val worker = city.workers.single { resident -> resident.assignment?.let(city::building)?.type == BuildingType.FORESTER }
            val building = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val capture = RecordedNativeMap.fromRleFile(Path.of(requireNotNull(terrainPath)), requireNotNull(terrainHash))
            val bounds = requireNotNull(CivsWorksiteLayouts.site(building)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val low = CivsWorksiteLayouts.vector(bounds["min"])
            val high = CivsWorksiteLayouts.vector(bounds["max"])
            val field = (low.z..high.z).flatMap { z -> (low.x..high.x).map { x ->
                CivsWorksiteLayouts.point(building, CivPosition(x, 0, z)).let { it.x to it.z }
            } }.toSet()
            val observedSaplings = capture.states.filter { (point, state) ->
                (point.x to point.z) in field && state.substringBefore('[').endsWith("_sapling")
            }
            check(observedSaplings.size >= 2) { "Captured area has fewer than two observed saplings: ${observedSaplings.size}" }
            check(observedSaplings.values.all { it.contains("[stage=") })
            val observedColumns = capture.states.keys.mapTo(hashSetOf()) { it.x to it.z }
            val missingColumns = field - observedColumns
            check(missingColumns.isNotEmpty()) { "This specifically recorded partial fixture must retain its unsampled field columns" }
            (city.autonomy != null) shouldBe true
            println("Auto2 forest capture: field=${field.size} observed=${field.size - missingColumns.size} " +
                "unknown=${missingColumns.size} observedSaplings=${observedSaplings.size}; no unknown terrain was inferred")
        }
})
