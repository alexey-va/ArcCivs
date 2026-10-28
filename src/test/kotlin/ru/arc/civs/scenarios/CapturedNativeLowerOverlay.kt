package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ru.arc.civs.CivBox
import ru.arc.civs.CivPosition
import java.util.UUID

/** Test-only join of explicit operator observations below the public Auto2 window.
 * Stage the small lower layer before mutating the encoded fixture, then decode it only once.
 * No missing cell, block identity or collider is inferred. */
internal fun appendNativeLowerCaptures(fixture: JsonObject, captures: List<JsonObject>,
    expectedCity: UUID, expectedWorld: UUID): Int {
    if (captures.isEmpty()) return 0
    val bounds = Gson().fromJson(fixture["bounds"], CivBox::class.java)
    check(bounds.min.y == 56) { "Native lower capture requires the public Auto2 Y56 boundary" }
    val observed = linkedMapOf<CivPosition, String>()
    val shapes = linkedMapOf<String, JsonObject>()
    for (capture in captures) {
        val legacyResident = !capture.has("state") && capture["focus_origin"]?.asString == "resident-feet"
        check(capture["source"]?.asString == "fresh-native-command" && (legacyResident ||
            capture["state"]?.asString == "captured" && capture["focus_origin"]?.asString == "operator-request")) {
            "Not a successful explicit native capture"
        }
        check(capture["city"]?.asString == expectedCity.toString() && capture["world"]?.asString == expectedWorld.toString()) {
            "Native lower capture city/world mismatch"
        }
        check(capture["captured_at_ms"].asLong > 0 && capture["unavailable_count"].asInt == 0 &&
            capture["required_load_level"]?.asString == "ENTITY_TICKING") { "Native lower capture was not fully available" }
        check(capture["voxel_coordinates"]?.asString == "offset-from-bounds-min" &&
            capture["collision_coordinates"]?.asString == "block-local" &&
            capture.getAsJsonArray("voxel_fields").map { it.asString } == listOf("x", "y", "z", "palette", "available") &&
            capture.getAsJsonArray("collision_fields").map { it.asString } == listOf("min_x", "min_y", "min_z", "max_x", "max_y", "max_z")) {
            "Native capture coordinate convention mismatch"
        }
        val nativeBounds = Gson().fromJson(capture["bounds"], CivBox::class.java)
        check(nativeBounds.min.x >= bounds.min.x && nativeBounds.max.x <= bounds.max.x &&
            nativeBounds.min.z >= bounds.min.z && nativeBounds.max.z <= bounds.max.z) { "Native lower capture leaves the public X/Z crop" }
        val width = nativeBounds.max.x.toLong() - nativeBounds.min.x + 1
        val height = nativeBounds.max.y.toLong() - nativeBounds.min.y + 1
        val depth = nativeBounds.max.z.toLong() - nativeBounds.min.z + 1
        val voxels = capture.getAsJsonArray("voxels")
        check(listOf(width, height, depth) == listOf(9L, 11L, 9L) && voxels.size() == 891 &&
            capture["voxel_count"].asInt == 891) { "Incomplete fixed native capture window" }
        val center = CivPosition(nativeBounds.min.x + 4, nativeBounds.min.y + 5, nativeBounds.min.z + 4)
        if (legacyResident) {
            val actual = capture.getAsJsonArray("actual")
            check(actual != null && actual.size() == 3 && actual.all {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()
            }) { "Legacy native capture has no finite resident position" }
            check(actual.map { kotlin.math.floor(it.asDouble) } == listOf(center.x, center.y, center.z).map { it.toDouble() }) {
                "Legacy native resident position/bounds mismatch"
            }
        }
        if (!legacyResident || capture.has("capture_center")) check(Gson().fromJson(capture["capture_center"], CivPosition::class.java) == center) {
            "Native capture center/bounds mismatch"
        }
        val chunks = capture.getAsJsonArray("chunks").associate { encoded -> encoded.asJsonObject.let {
            check(it["loaded"].asBoolean && it["load_level"].asString == "ENTITY_TICKING") { "Native capture contains a non-ticking chunk" }
            (it["x"].asInt to it["z"].asInt) to it
        } }
        val expectedChunks = ((nativeBounds.min.x shr 4)..(nativeBounds.max.x shr 4)).flatMap { x ->
            ((nativeBounds.min.z shr 4)..(nativeBounds.max.z shr 4)).map { z -> x to z }
        }.toSet()
        check(chunks.keys == expectedChunks) { "Native capture is missing chunk availability evidence" }
        val palette = capture.getAsJsonArray("palette")
        val offsets = hashSetOf<CivPosition>()
        for (encoded in voxels) {
            val cell = encoded.asJsonArray
            check(cell.size() == 5 && cell[4].asBoolean) { "Unavailable native lower voxel" }
            val offset = CivPosition(cell[0].asInt, cell[1].asInt, cell[2].asInt)
            check(offset.x in 0 until width.toInt() && offset.y in 0 until height.toInt() &&
                offset.z in 0 until depth.toInt() && offsets.add(offset)) { "Invalid or repeated native voxel coordinate" }
            val point = CivPosition(nativeBounds.min.x + offset.x, nativeBounds.min.y + offset.y, nativeBounds.min.z + offset.z)
            if (point.y >= bounds.min.y) continue // Never replace a public cell, even with a later observation.
            val shape = palette[cell[3].asInt].asJsonObject
            val state = shape["block_data"].asString
            check(state.isNotBlank()) { "Native lower voxel has no state" }
            val boxes = nativeCollisionBoxes(shape)
            val previousShape = shapes.putIfAbsent(state, shape)
            check(previousShape == null || nativeCollisionBoxes(previousShape) == boxes) { "Conflicting native colliders for $state" }
            val previous = observed.putIfAbsent(point, state)
            check(previous == null || previous == state) { "Conflicting native observations at $point" }
        }
    }
    check(observed.isNotEmpty()) { "Native capture series contains no cells below public Y56" }
    val palette = fixture.getAsJsonArray("palette")
    val indices = palette.withIndex().associate { (index, shape) -> shape.asJsonObject["block_data"].asString to index }.toMutableMap()
    // A state already used in the public layer must retain exactly its existing known collider.
    for ((state, shape) in shapes) indices[state]?.let { index ->
        check(nativeCollisionBoxes(palette[index].asJsonObject) == nativeCollisionBoxes(shape)) {
            "Native lower collider conflicts with the covered public state $state"
        }
    }
    for ((state, shape) in shapes) if (state !in indices) {
        indices[state] = palette.size()
        palette.add(shape.deepCopy())
    }
    val voxels = fixture.getAsJsonArray("voxels")
    for ((point, state) in observed) voxels.add(JsonArray().also { cell ->
        cell.add(point.x - bounds.min.x); cell.add(point.y - bounds.min.y); cell.add(point.z - bounds.min.z)
        cell.add(indices.getValue(state)); cell.add(true); cell.add(false) // Exact identity, not opaque-fill inference.
    })
    return observed.size
}

private fun nativeCollisionBoxes(shape: JsonObject): Set<List<Double>> =
    requireNotNull(shape.getAsJsonArray("collision")) { "Missing exact native collision for ${shape["block_data"]}" }.map { encoded ->
        val box = encoded.asJsonArray
        check(box.size() == 6) { "Incomplete native collision box" }
        box.map { it.asDouble }.also { values ->
            check(values.all(Double::isFinite) && (0..2).all { values[it] <= values[it + 3] }) { "Invalid native collision box" }
        }
    }.toSet()
