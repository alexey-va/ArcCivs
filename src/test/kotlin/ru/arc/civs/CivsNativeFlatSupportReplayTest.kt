package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

/** Opt-in exact native geometry; private blocks/coordinates stay outside the repository. */
class CivsNativeFlatSupportReplayTest : FunSpec({
    val input = System.getenv("CIVS_FLAT_SUPPORT_NATIVE_INPUT")
    test("captured offset feet walk around the low half tread instead of repeatedly accepting an unsupported flat edge")
        .config(enabled = input != null) {
            val data = JsonParser.parseString(Files.readString(Path.of(requireNotNull(input)))).asJsonObject
            data["unavailable_count"].asInt shouldBe 0
            data["voxel_count"].asInt shouldBe 891
            val gson = GsonBuilder().setPrettyPrinting().create()
            val bounds = gson.fromJson(data["bounds"], CivBox::class.java)
            val palette = data.getAsJsonArray("palette").map { it.asJsonObject }
            val native = data.getAsJsonArray("voxels").associate { value ->
                val row = value.asJsonArray
                row[4].asBoolean shouldBe true
                CivPosition(bounds.min.x + row[0].asInt, bounds.min.y + row[1].asInt, bounds.min.z + row[2].asInt) to
                    palette[row[3].asInt]["block_data"].asString
            }
            val collisions = palette.associate { state -> state["block_data"].asString to
                state.getAsJsonArray("collision").map { value ->
                    val b = value.asJsonArray
                    BoundingBox(b[0].asDouble, b[1].asDouble, b[2].asDouble,
                        b[3].asDouble, b[4].asDouble, b[5].asDouble)
                }
            }
            MockBukkitTestRuntime.open().use { paper ->
                var strict = false
                val world = object : CivsWorksiteWorldFixture("native-flat-support", Material.AIR, { state ->
                    requireNotNull(collisions[state.asString]) { "Uncaptured native block state" }.map(BoundingBox::clone)
                }) {
                    override fun getBlockAt(point: Coordinate): BlockMock {
                        if (strict) require(CivPosition(point.x, point.y, point.z) in native) { "Uncaptured native cell" }
                        return super.getBlockAt(point)
                    }
                }.also(paper.server::addWorld)
                for (x in (bounds.min.x shr 4)..(bounds.max.x shr 4))
                    for (z in (bounds.min.z shr 4)..(bounds.max.z shr 4)) world.loadChunk(x, z)
                native.forEach { (point, state) -> world.getBlockAt(point.x, point.y, point.z)
                    .setBlockData(Bukkit.createBlockData(state), false) }
                strict = true
                val actual = data.getAsJsonArray("actual")
                var at = Location(world, actual[0].asDouble, actual[1].asDouble, actual[2].asDouble, 135f, 0f)
                val origin = at.clone()
                val goal = Location(world, floor(at.x) - .5, at.y, floor(at.z) - .5)
                // This is the captured local exit, not a fabricated replay of the uncaptured warehouse trip.
                val route = CitizenPacketRoute(world, at, goal,
                    { point -> bounds.contains(point.x, point.y, point.z) }, { _, _ -> true }, { 1.0 })
                var operations = 0
                while (route.searching && operations++ < CitizenNavigationSearch.MAXIMUM_NODES * 64) route.expand()
                route.searching shouldBe false
                route.failure shouldBe null
                citizenPacketBodyClear(world, at.point(), false) shouldBe true
                citizenPacketSupported(world, at.point()) shouldBe true
                val frames = mutableListOf(at.point())
                var ticks = 0
                while (!route.finished && route.failure == null && ticks++ < 160) {
                    route.move(at, .8) { true }?.let { next ->
                        (next.distance(at) <= .172001) shouldBe true
                        next.y shouldBe origin.y
                        citizenPacketBodyClear(world, next.point(), false) shouldBe true
                        citizenPacketSupported(world, next.point()) shouldBe true
                        citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
                        at = next
                        frames += next.point()
                    }
                }
                System.getenv("CIVS_FLAT_SUPPORT_REPLAY_OUT")?.let { output ->
                    Files.writeString(Path.of(output), gson.toJson(mapOf(
                        "kind" to "exact-native-local-flat-support-replay-not-live-or-complete-warehouse-route",
                        "captured_at_ms" to data["captured_at_ms"].asLong, "native_cells" to native.size,
                        "failure" to route.failure, "finished" to route.finished, "ticks" to ticks,
                        "nodes" to route.nodeCount, "frames" to frames, "goal" to goal.point(),
                    )))
                }
                withClue("failure=${route.failure} ticks=$ticks nodes=${route.nodeCount} frames=${frames.size}") {
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(goal) shouldBe 0.0
                    (frames.size > 1) shouldBe true
                }
            }
        }
})
