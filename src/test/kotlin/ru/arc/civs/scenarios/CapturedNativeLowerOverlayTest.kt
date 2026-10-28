package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.civs.CivBox
import ru.arc.civs.CivPosition
import ru.arc.civs.CivsWorksiteServerFixture
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Synthetic capture envelopes verify only the factual-join contract, not live world content. */
class CapturedNativeLowerOverlayTest : FunSpec({
    test("lower native cells join once while the public layer and unknown space remain intact") {
        val fixture = overlayPublicFixture()
        fixture.getAsJsonArray("voxels")[0].asJsonArray.set(5, Gson().toJsonTree(true))
        val publicCells = fixture.getAsJsonArray("voxels").deepCopy().toList()
        val native = overlayWindow()
        // Later upper observations must never replace the separately recorded public layer.
        native.getAsJsonArray("palette").add(overlayShape("minecraft:dirt"))
        native.getAsJsonArray("voxels").filter { it.asJsonArray[1].asInt >= 6 }.forEach { it.asJsonArray.set(3, Gson().toJsonTree(1)) }
        appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) shouldBe 486
        fixture.getAsJsonArray("voxels").take(publicCells.size) shouldBe publicCells
        fixture.getAsJsonArray("palette").size() shouldBe 1
        withOverlayMap(fixture) { capture ->
            capture.states.size shouldBe 891
            capture.states.getValue(CivPosition(0, 55, 0)) shouldBe "minecraft:stone"
            capture.states.getValue(CivPosition(0, 56, 0)) shouldBe "minecraft:stone"
            (CivPosition(0, 49, 0) in capture.states) shouldBe false
            capture.unknownOpaqueIdentity shouldBe setOf(CivPosition(-4, 56, -4))
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CapturedResidentWorld(capture).also(paper.server::addWorld)
                world.strict = true
                world.getBlockAt(0, 55, 0).type shouldBe Material.STONE
                world.getBlockAt(0, 55, 0).collisionShape.boundingBoxes.toList() shouldBe
                    listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                shouldThrow<IllegalStateException> { world.getBlockAt(0, 49, 0) }
            }
        }
    }

    test("duplicate factual windows agree and do not duplicate native cells") {
        val fixture = overlayPublicFixture()
        appendNativeLowerCaptures(fixture, listOf(overlayWindow(), overlayWindow()), OVERLAY_CITY, OVERLAY_WORLD) shouldBe 486
        fixture.getAsJsonArray("voxels").size() shouldBe 891
    }

    test("legacy resident-feet raw captures join unchanged with or without an explicit center") {
        for (explicitCenter in listOf(false, true)) {
            val fixture = overlayPublicFixture()
            val publicCells = fixture.getAsJsonArray("voxels").deepCopy().toList()
            val native = overlayLegacyWindow().also { if (!explicitCenter) it.remove("capture_center") }
            val raw = native.toString()
            appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) shouldBe 486
            native.toString() shouldBe raw
            native.has("state") shouldBe false
            fixture.getAsJsonArray("voxels").take(publicCells.size) shouldBe publicCells
        }
    }

    test("legacy resident-feet actual position must floor to the exact recorded center") {
        val fixture = overlayPublicFixture()
        val before = fixture.toString()
        // Truncating this negative offset would incorrectly accept zero as its block coordinate.
        val native = overlayLegacyWindow().also { it.getAsJsonArray("actual").set(0, Gson().toJsonTree(-.25)) }
        shouldThrow<IllegalStateException> { appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) }
        fixture.toString() shouldBe before
    }

    test("legacy resident-feet captures still require available voxels and ticking chunks") {
        for (unavailableVoxel in listOf(false, true)) {
            val fixture = overlayPublicFixture()
            val before = fixture.toString()
            val native = overlayLegacyWindow()
            if (unavailableVoxel) native.getAsJsonArray("voxels")[0].asJsonArray.set(4, Gson().toJsonTree(false))
            else native.getAsJsonArray("chunks")[0].asJsonObject.addProperty("load_level", "BORDER")
            shouldThrow<IllegalStateException> { appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) }
            fixture.toString() shouldBe before
        }
    }

    test("legacy compatibility never admits errored captures or malformed new operator envelopes") {
        val invalid = listOf(
            overlayLegacyWindow().also { it.addProperty("state", "error") },
            overlayLegacyWindow().also { it.addProperty("state", "captured") },
            overlayLegacyWindow().also { it.addProperty("source", "cached-native") },
            overlayWindow().also { it.remove("capture_center") })
        for (native in invalid) {
            val fixture = overlayPublicFixture()
            val before = fixture.toString()
            shouldThrow<IllegalStateException> { appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) }
            fixture.toString() shouldBe before
        }
    }

    for (field in listOf("city", "world")) test("a mismatched native $field rejects before changing the encoded world") {
        val fixture = overlayPublicFixture()
        val before = fixture.toString()
        val native = overlayWindow().also { it.addProperty(field, UUID(0, 99).toString()) }
        shouldThrow<IllegalStateException> { appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) }
        fixture.toString() shouldBe before
    }

    test("a native window outside the recorded XZ crop is refused") {
        val fixture = overlayPublicFixture()
        val before = fixture.toString()
        shouldThrow<IllegalStateException> {
            appendNativeLowerCaptures(fixture, listOf(overlayWindow(center = CivPosition(1, 55, 0))), OVERLAY_CITY, OVERLAY_WORLD)
        }
        fixture.toString() shouldBe before
    }

    test("unavailable voxels and non-ticking chunks never become native terrain") {
        for (unavailableVoxel in listOf(false, true)) {
            val fixture = overlayPublicFixture()
            val native = overlayWindow()
            if (unavailableVoxel) native.getAsJsonArray("voxels")[0].asJsonArray.set(4, Gson().toJsonTree(false))
            else native.getAsJsonArray("chunks")[0].asJsonObject.addProperty("load_level", "BORDER")
            shouldThrow<IllegalStateException> { appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD) }
            fixture.getAsJsonArray("voxels").size() shouldBe 405
        }
    }

    test("missing native colliders and contradictory covered-state colliders are refused") {
        for (missing in listOf(false, true)) {
            val fixture = overlayPublicFixture()
            val native = overlayWindow()
            val shape = native.getAsJsonArray("palette")[0].asJsonObject
            if (missing) shape.remove("collision") else shape.add("collision", JsonArray())
            if (missing) shouldThrow<IllegalArgumentException> {
                appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD)
            } else shouldThrow<IllegalStateException> {
                appendNativeLowerCaptures(fixture, listOf(native), OVERLAY_CITY, OVERLAY_WORLD)
            }
            fixture.getAsJsonArray("voxels").size() shouldBe 405
        }
    }

    test("conflicting overlapping native windows reject the entire join") {
        val fixture = overlayPublicFixture()
        val before = fixture.toString()
        val second = overlayWindow().also { it.getAsJsonArray("palette")[0].asJsonObject.addProperty("block_data", "minecraft:dirt") }
        shouldThrow<IllegalStateException> {
            appendNativeLowerCaptures(fixture, listOf(overlayWindow(), second), OVERLAY_CITY, OVERLAY_WORLD)
        }
        fixture.toString() shouldBe before
    }

    test("exact supplemental state colliders leave public identities and unknown cells intact") {
        val native = overlayWindow().also { it.getAsJsonArray("palette").set(0,
            overlayShape("minecraft:bush").also { shape -> shape.add("collision", JsonArray()) }) }
        val facts = capturedStateCollisionFacts(listOf(native), OVERLAY_CITY, OVERLAY_WORLD)
        val fixture = overlayPublicFixture().also {
            it.getAsJsonArray("palette")[0].asJsonObject.apply { addProperty("block_data", "minecraft:bush"); remove("collision") }
        }
        withOverlayMap(fixture) { capture ->
            val before = capture.states.toMap()
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CapturedResidentWorld(capture, nativeStateFacts = facts).also(paper.server::addWorld)
                world.strict = true
                world.getBlockAt(0, 56, 0).type shouldBe Material.BUSH
                world.getBlockAt(0, 56, 0).collisionShape.boundingBoxes.toList() shouldBe emptyList()
                shouldThrow<IllegalStateException> { world.getBlockAt(0, 55, 0) }
            }
            capture.states shouldBe before
        }
    }

    test("supplemental collider observations reject the wrong world and contradictory native shapes") {
        val native = overlayWindow()
        shouldThrow<IllegalStateException> { capturedStateCollisionFacts(listOf(native), OVERLAY_CITY, UUID(0, 99)) }
        val contradictory = overlayWindow().also { it.getAsJsonArray("palette")[0].asJsonObject.add("collision", JsonArray()) }
        shouldThrow<IllegalStateException> { capturedStateCollisionFacts(listOf(native, contradictory), OVERLAY_CITY, OVERLAY_WORLD) }
    }

    test("supplemental state facts cannot replace an already known public collider") {
        withOverlayMap(overlayPublicFixture()) { capture ->
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
                shouldThrow<IllegalStateException> { CapturedResidentWorld(capture, nativeStateFacts = mapOf("minecraft:stone" to emptyList())) }
            }
        }
    }
})

private val OVERLAY_CITY = UUID(0, 1)
private val OVERLAY_WORLD = UUID(0, 2)
private fun overlayShape(state: String): JsonObject = Gson().toJsonTree(mapOf("block_data" to state,
    "collision" to listOf(listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))).asJsonObject

private fun overlayPublicFixture(): JsonObject = Gson().toJsonTree(mapOf(
    "bounds" to CivBox(CivPosition(-4, 56, -4), CivPosition(4, 60, 4)),
    "palette" to listOf(overlayShape("minecraft:stone")),
    "voxels" to (-4..4).flatMap { x -> (-4..4).flatMap { z -> (56..60).map { y -> listOf(x + 4, y - 56, z + 4, 0, true, false) } } }
)).asJsonObject

private fun overlayWindow(center: CivPosition = CivPosition(0, 55, 0)): JsonObject {
    val min = CivPosition(center.x - 4, center.y - 5, center.z - 4)
    val max = CivPosition(center.x + 4, center.y + 5, center.z + 4)
    return Gson().toJsonTree(mapOf("state" to "captured", "source" to "fresh-native-command", "focus_origin" to "operator-request",
        "city" to OVERLAY_CITY, "worker" to UUID(0, 3), "world" to OVERLAY_WORLD, "captured_at_ms" to 42_000,
        "capture_center" to center, "bounds" to CivBox(min, max), "voxel_count" to 891, "unavailable_count" to 0,
        "required_load_level" to "ENTITY_TICKING", "voxel_coordinates" to "offset-from-bounds-min", "collision_coordinates" to "block-local",
        "voxel_fields" to listOf("x", "y", "z", "palette", "available"),
        "collision_fields" to listOf("min_x", "min_y", "min_z", "max_x", "max_y", "max_z"),
        "chunks" to ((min.x shr 4)..(max.x shr 4)).flatMap { x -> ((min.z shr 4)..(max.z shr 4)).map { z ->
            mapOf("x" to x, "z" to z, "loaded" to true, "load_level" to "ENTITY_TICKING") } },
        "palette" to listOf(overlayShape("minecraft:stone")),
        "voxels" to (0..8).flatMap { x -> (0..8).flatMap { z -> (0..10).map { y -> listOf(x, y, z, 0, true) } } }
    )).asJsonObject
}

private fun overlayLegacyWindow(): JsonObject = overlayWindow().also {
    it.remove("state")
    it.addProperty("focus_origin", "resident-feet")
    it.add("actual", Gson().toJsonTree(listOf(.75, 55.25, .75)))
}

private fun withOverlayMap(fixture: JsonObject, block: (RecordedNativeMap) -> Unit) {
    val path = Files.createTempFile("arccivs-native-overlay-", ".json")
    try {
        val bytes = fixture.toString().toByteArray(Charsets.UTF_8)
        Files.write(path, bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        block(RecordedNativeMap.fromFile(path, hash))
    } finally { Files.deleteIfExists(path) }
}
