package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.BoundingBox
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.UUID

class CivsNavigationSnapshotsTest : FunSpec({
    test("detached support costs keep the weighted route and defer arbitrary or enabled Trails costs to the owner") {
        sharedRouteRuntime { h ->
            h.allowedZ = 0..2
            (1..7).map { CivPosition(it, 63, 1) }.forEach(h.paths.begin(h.city).network::remember)
            var customReads = 0
            val native = h.request(reuseKey = null, surfaceSpeed = {
                customReads++
                1.0
            })
            h.finish(native)
            val nativeReads = h.supportCostReads()
            (nativeReads > 0 && customReads > 0) shouldBe true
            val first = native.destination
            h.walkUntil(native) { it.z > 1.0 }

            val detached = h.request(reuseKey = null, surfaceSpeed = h.footTraffic)
            h.finish(detached)
            detached.destination shouldBe first
            detached.endpoint shouldBe h.target
            detached.nodeCount shouldBe native.nodeCount
            h.walkUntil(detached) { it.z > 1.0 }
            h.supportCostReads() shouldBe nativeReads

            val trails = mockk<TrailsFootTrafficService>()
            var trailsReads = 0
            every { trails.speedMultiplier(any()) } answers {
                trailsReads++
                2.0
            }
            h.enableTrails(trails)
            val recognized = h.request(reuseKey = null, surfaceSpeed = h.footTraffic)
            h.finish(recognized)
            h.walk(recognized)
            (h.supportCostReads() > nativeReads && trailsReads > 0) shouldBe true
        }
    }

    test("shared route geometry is learned only after observed traversal and replays without another A star") {
        sharedRouteRuntime { h ->
            val merelyReady = h.request()
            h.finish(merelyReady)
            h.cache()["entries"] shouldBe 0
            merelyReady.cancel()
            h.cache()["entries"] shouldBe 0
            val walked = h.request()
            h.finish(walked)
            h.walk(walked).distanceSquared(h.target) shouldBe 0.0
            h.cache()["entries"] shouldBe 1
            val warm = h.request(from = CitizenPoint(.7, 64.0, .5))
            h.finish(warm)
            warm.failure shouldBe null
            warm.nodeCount shouldBe 0
            warm.capturedBlocks shouldBe 0
            h.cache()["hits"] shouldBe 1L
            ((h.cache()["native_step_checks"] as Long) > 0) shouldBe true
            val cost = h.cache()["last_validated_cost"] as Double
            (kotlin.math.abs(cost - (h.target.x - .7) * CivsPaths.ORDINARY_COST) < 1e-9) shouldBe true
            h.routeCounters()["exact_candidates"] shouldBe 1L
            h.routeCounters()["exact_hits"] shouldBe 1L
            h.walk(warm, CitizenPoint(.7, 64.0, .5)).distanceSquared(h.target) shouldBe 0.0
            h.routeCounters()["removed_replacement"] shouldBe 1L
        }
    }

    test("shared route diagnostics follow the requesting city and attribute removal to the learned entry") {
        sharedRouteRuntime { h ->
            val other = UUID(0, 3)
            val first = h.request(); h.finish(first); h.walk(first)
            val second = h.request(cityId = other); h.finish(second)
            second.nodeCount shouldBe 0 // City attribution does not enter the existing route identity.
            h.walk(second)
            h.diagnostics()["cities_total"] shouldBe 2
            h.diagnostics(other)["cities_total"] shouldBe 1
            h.routeCounters()["exact_hits"] shouldBe 0L
            h.routeCounters()["removed_replacement"] shouldBe 1L
            h.routeCounters(other)["exact_hits"] shouldBe 1L
            h.routeCounters(other)["removed_replacement"] shouldBe 0L
            h.routeCity()["entries"] shouldBe 0
            h.routeCity(other)["entries"] shouldBe 1

            repeat(2) { h.owner.changed(h.world.uid, CivPosition(4, 63, 0)) }
            h.cache()["entries"] shouldBe 0
            h.cache()["invalidated"] shouldBe 1L
            h.routeCounters(other)["removed_native_change"] shouldBe 1L
            h.routeCounters()["removed_native_change"] shouldBe 0L
            val again = h.request(cityId = other); h.finish(again); h.walk(again)
            h.request(reuseKey = null, cityId = null).cancel()
            val unknown = h.diagnostics()["unknown_city"] as Map<*, *>
            (unknown["counters"] as Map<*, *>)["excluded_no_reuse_policy"] shouldBe 1L

            h.owner.prepareNavigation(listOf(h.city)) { false }
            h.diagnostics(other)["cities_total"] shouldBe 0
            h.cache()["entries"] shouldBe 1
            repeat(2) { h.owner.removeWorld(h.world.uid) }
            (h.diagnostics()["totals"] as Map<*, *>)["removed_unload"] shouldBe 1L
            h.diagnostics(other)["cities_total"] shouldBe 0
            h.routeCounters()["removed_unload"] shouldBe 0L

            h.request(reuseKey = null, cityId = other).cancel()
            h.routeCounters(other)["learned"] shouldBe 0L
            h.routeCounters(other)["excluded_no_reuse_policy"] shouldBe 1L
            val extraCities = (4L..10L).map { UUID(0, it) }
            extraCities.forEach { h.request(reuseKey = null, cityId = it).cancel() }
            val line = CivsDebug.lines(listOf("route-cache")) { _, cityId, _ -> h.diagnostics(cityId) }.single()
            withClue("route-cache line length=${line.length}") { (line.length <= 16_384) shouldBe true }
            val state = JsonParser.parseString(line.removePrefix("ARCCIVS_DEBUG ")).asJsonObject.getAsJsonObject("state")
            state["cities_total"].asInt shouldBe 9
            state["cities_truncated"].asBoolean shouldBe true
            state.getAsJsonArray("cities").size() shouldBe 8
            h.routeCounters(extraCities.last())["excluded_no_reuse_policy"] shouldBe 1L
            h.owner.clear()
            h.diagnostics()["cities_total"] shouldBe 0
            (h.diagnostics()["totals"] as Map<*, *>)["removed_unload"] shouldBe 1L
        }
    }

    test("route cache debug dispatches the city filter through the service") {
        serviceScenario { h ->
            h.start()
            every { h.citizens.debugSharedRoutes(h.city.id) } returns mapOf("maximum_entries" to 64)
            val line = CivsDebug.lines(listOf("route-cache", h.city.id.toString()), h.service::debugState).single()
            val payload = JsonParser.parseString(line.removePrefix("ARCCIVS_DEBUG ")).asJsonObject
            payload["system"].asString shouldBe "route-cache"
            payload["city"].asString shouldBe h.city.id.toString()
            payload.getAsJsonObject("state")["maximum_entries"].asInt shouldBe 64
        }
    }

    test("shared suffix lookup reports one miss per request and examines a route indexed in two sections just once") {
        sharedRouteRuntime { h ->
            val distantOrigin = CitizenPoint(5.5, 64.0, .5)
            val first = h.request(from = distantOrigin); h.finish(first); h.walk(first, distantOrigin)
            h.request(target = h.target.copy(x = 7.5)).cancel()
            h.request(reuseKey = h.key(setOf(UUID(0, 8)))).cancel()
            h.request().cancel() // Indexed from both z sections, with every candidate step over three blocks away.
            h.routeCounters()["miss_no_nearby_entries"] shouldBe 1L
            h.routeCounters()["miss_no_matching_endpoint"] shouldBe 1L
            h.routeCounters()["miss_no_compatible_entry"] shouldBe 1L
            h.routeCounters()["miss_no_join_point"] shouldBe 1L
            h.routeCounters()["suffix_candidate_limit_reached"] shouldBe 0L
            h.cache()["misses"] shouldBe 4L
            h.suffixes()["candidates"] shouldBe 1L

            // Eight distinct cost identities with the same endpoint, geometry and allowed buildings.
            val network = h.paths.begin(h.city).network
            for (x in 1..7) {
                network.remember(CivPosition(x, 63, 0))
                val route = h.request(from = distantOrigin); h.finish(route); h.walk(route, distantOrigin)
            }
            val candidates = h.suffixes()["candidates"] as Long
            h.request().cancel()
            h.cache()["entries"] shouldBe 8
            h.cache()["misses"] shouldBe 5L
            h.routeCounters()["miss_no_join_point"] shouldBe 2L
            h.routeCounters()["suffix_candidate_limit_reached"] shouldBe 1L
            h.suffixes()["candidates"] shouldBe candidates + 8L
        }
    }

    test("a compatible ordinary trip joins a nearby shared suffix with fresh current costs and learns its own identity") {
        sharedRouteRuntime { h ->
            h.allowedZ = 0..2
            val first = h.request(); h.finish(first); h.walk(first)
            h.paths.begin(h.city).network.remember(CivPosition(7, 63, 0))
            val from = CitizenPoint(4.5, 64.0, 2.5)
            val joined = h.request(from = from); h.finish(joined)
            joined.nodeCount shouldBe 0
            joined.capturedBlocks shouldBe 0
            h.suffixes()["hits"] shouldBe 1L
            h.routeCounters()["suffix_candidates"] shouldBe 1L
            h.routeCounters()["suffix_hits"] shouldBe 1L
            ((h.suffixes()["candidates"] as Long) in 1..8) shouldBe true
            ((h.suffixes()["steps_examined"] as Long) <= 8 * 256) shouldBe true
            ((h.cache()["last_validated_cost"] as Double) > 0.0) shouldBe true
            h.walk(joined, from).distanceSquared(h.target) shouldBe 0.0
            h.cache()["entries"] shouldBe 2 // The new C-to-B key does not replace the original A-to-B key.
        }
    }

    for (obstruction in listOf("pit", "wall", "policy"))
    test("a $obstruction in the suffix connector falls back to fresh ordinary search and retains the unaffected old route") {
        sharedRouteRuntime { h ->
            h.allowedZ = 0..2
            val first = h.request(); h.finish(first); h.walk(first)
            when (obstruction) {
                "pit" -> h.world.getBlockAt(5, 63, 1).type = Material.AIR
                "wall" -> for (y in 64..66) h.world.getBlockAt(5, y, 1).type = Material.STONE
            }
            val from = CitizenPoint(4.5, 64.0, 2.5)
            val route = h.request(from = from, allowedPoint = { p ->
                obstruction != "policy" || p.x != 5 || p.z != 1
            })
            h.finish(route)
            h.suffixes()["hits"] shouldBe 0L
            h.suffixes()["rejected_connectors"] shouldBe 1L
            h.routeCounters()["suffix_candidates"] shouldBe 1L
            h.routeCounters()["rejected"] shouldBe 1L
            h.routeCounters()["rejected_connectors"] shouldBe 1L
            h.routeCounters()["removed_replay_or_route_failure"] shouldBe 0L
            h.cache()["misses"] shouldBe 1L
            (route.nodeCount > 0) shouldBe true
            h.cache()["entries"] shouldBe 1
            h.walk(route, from).distanceSquared(h.target) shouldBe 0.0
        }
    }

    test("current tail policy is checked after the join and wrong goals worlds and special routes cannot share it") {
        sharedRouteRuntime { h ->
            h.allowedZ = 0..2
            val first = h.request(); h.finish(first); h.walk(first)
            val from = CitizenPoint(4.5, 64.0, 2.5)
            for (route in listOf(
                h.request(from = from, target = h.target.copy(x = 7.5)),
                h.request(from = from, reuseKey = null),
                h.request(from = from, world = h.otherWorld()),
            )) {
                h.finish(route)
                (route.nodeCount > 0) shouldBe true
            }
            h.suffixes()["hits"] shouldBe 0L
            val rejected = h.request(from = from, allowedPoint = { it.x != 7 || it.z != 0 })
            h.finish(rejected)
            (rejected.nodeCount > 0) shouldBe true
            h.cache()["rejected"] shouldBe 1L
            h.suffixes()["rejected_connectors"] shouldBe 0L
            h.cache()["entries"] shouldBe 0
            h.walk(rejected, from).distanceSquared(h.target) shouldBe 0.0
        }
    }

    test("shared routes separate policy geometry cost target height settings bodies and explicit corridors") {
        sharedRouteRuntime { h ->
            val first = h.request(); h.finish(first); h.walk(first)
            val key = h.key()
            val source = CitizenPoint(.5, 64.0, .5)
            fun identity(policyKey: CitizenRouteReuseKey = key, y: Double = 64.0,
                target: CitizenPoint = h.target, settings: CivsNavigationSettings = CivsNavigationSettings()) =
                CitizenPacketRoute(h.world, Location(h.world, source.x, y, source.z),
                    Location(h.world, target.x, target.y, target.z), { true }, { _, _ -> true }, { 1.0 },
                    settings = settings, snapshotPolicy = CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }, policyKey)).reuseIdentity()
            val original = identity()
            (identity(h.key(setOf(UUID(0, 9)))) != original) shouldBe true
            (identity(y = 64.5) != original) shouldBe true
            (identity(target = h.target.copy(x = h.target.x + .1)) != original) shouldBe true
            (identity(settings = CivsNavigationSettings(maximumNodes = 8192)) != original) shouldBe true
            val changedCity = h.city.copy(radius = 95)
            (identity(CitizenRouteReuseKey.of(CivsPaths().begin(changedCity), emptySet())) != original) shouldBe true
            val trip = h.paths.begin(h.city)
            trip.network.remember(CivPosition(1, 63, 0))
            (identity(h.key()) != original) shouldBe true
            trip.network.forget(CivPosition(1, 63, 0))
            val corridor = requireNotNull(CitizenSearchCorridor.fromWalked((0..8).mapTo(hashSetOf()) {
                CivPosition(it, 63, 0) }, source, h.target))
            val hinted = h.request(corridor = corridor)
            h.finish(hinted)
            (hinted.nodeCount > 0) shouldBe true
            val body = h.owner.replan(hinted, source.location(h.world),
                listOf(BoundingBox(4.0, 64.0, 3.0, 4.6, 65.8, 3.6)), refreshSnapshots = false)
            h.finish(body)
            (body.nodeCount > 0) shouldBe true
            h.request(reuseKey = null, corridor = corridor).cancel()
            h.cache()["hits"] shouldBe 0L
            h.routeCounters()["eligible"] shouldBe 1L
            h.routeCounters()["excluded_explicit_corridor"] shouldBe 2L
            h.routeCounters()["excluded_body_detour"] shouldBe 1L
            h.routeCounters()["excluded_no_reuse_policy"] shouldBe 0L
        }
    }

    test("shared route invalidation is local while physical retries unload and close still retire geometry") {
        sharedRouteRuntime { h ->
            val first = h.request(); h.finish(first); h.walk(first)
            h.world.getBlockAt(4, 95, 0).type = Material.STONE
            h.owner.changed(h.world.uid, CivPosition(4, 95, 0))
            h.cache()["entries"] shouldBe 1
            val warm = h.request(); h.finish(warm)
            h.cache()["hits"] shouldBe 1L
            val bodyRetry = h.owner.replan(warm, CitizenPoint(.5, 64.0, .5).location(h.world), refreshSnapshots = false)
            h.cache()["entries"] shouldBe 1
            h.finish(bodyRetry)
            val nativeRetry = h.owner.replan(bodyRetry, CitizenPoint(.5, 64.0, .5).location(h.world))
            h.cache()["entries"] shouldBe 0
            h.routeCounters()["removed_native_change"] shouldBe 0L
            h.routeCounters()["removed_replan"] shouldBe 1L
            h.finish(nativeRetry); h.walk(nativeRetry)
            h.cache()["entries"] shouldBe 1
            h.owner.removeChunk(h.world.uid, 0, 0)
            h.cache()["entries"] shouldBe 0
            h.routeCounters()["removed_unload"] shouldBe 1L
            val again = h.request(); h.finish(again); h.walk(again)
            h.owner.clear()
            h.cache()["entries"] shouldBe 0
        }
    }

    test("cached geometry rechecks current policy and a wall appearing after installation still stops native movement") {
        sharedRouteRuntime { h ->
            val first = h.request(); h.finish(first); h.walk(first)
            var permitted = true
            val denied = h.request(permitted = { permitted })
            permitted = false
            h.finish(denied)
            denied.failure shouldBe "no-path"
            h.cache()["rejected"] shouldBe 1L
            h.cache()["entries"] shouldBe 0
            h.routeCounters()["removed_replay_or_route_failure"] shouldBe 1L
            val next = h.request(); h.finish(next); h.walk(next)
            val warm = h.request(); h.finish(warm)
            warm.nodeCount shouldBe 0
            for (y in 64..66) h.world.getBlockAt(4, y, 0).type = Material.STONE
            val stopped = h.walk(warm, requireFinished = false)
            (stopped.x < 3.8) shouldBe true
            (warm.failure != null) shouldBe true
            h.cache()["entries"] shouldBe 0
            h.routeCounters()["removed_replay_or_route_failure"] shouldBe 2L
            val ordinary = h.owner.replan(warm, stopped.location(h.world)); h.finish(ordinary)
            (ordinary.failure ?: ordinary.continuationReason) shouldBe "no-path"
            (ordinary.nodeCount > 0) shouldBe true
            if (ordinary.failure == null) {
                ordinary.continuation shouldBe true
                (requireNotNull(ordinary.endpoint).x < 3.8) shouldBe true
            }
        }
    }

    test("shared route storage stays bounded and a discontinuous observed pose never learns a route") {
        sharedRouteRuntime { h ->
            val discontinuous = h.request(); h.finish(discontinuous)
            val from = CitizenPoint(.5, 64.0, .5)
            discontinuous.move(from.location(h.world), 1.0) { true }?.let { next ->
                discontinuous.observeAcceptedMove(from, CitizenPoint(next.x + 4.0, next.y, next.z))
            }
            discontinuous.cancel()
            h.cache()["entries"] shouldBe 0
            val target = CitizenPoint(2.5, 64.0, .5)
            fun routeWith(index: Long) = h.request(target = target, reuseKey = h.key(setOf(UUID(0, index))))
            for (index in 0L..64L) {
                if (index == 64L) {
                    val recentlyUsed = routeWith(0); h.finish(recentlyUsed)
                    recentlyUsed.nodeCount shouldBe 0
                    recentlyUsed.cancel()
                    h.routeCounters()["removed_capacity"] shouldBe 0L
                }
                val route = routeWith(index)
                h.finish(route)
                h.walk(route, target = target)
            }
            h.cache()["entries"] shouldBe 64
            h.cache()["maximum_steps"] shouldBe 256
            h.routeCounters()["removed_capacity"] shouldBe 1L
            val kept = routeWith(0); h.finish(kept)
            kept.nodeCount shouldBe 0
            val evicted = routeWith(1); h.finish(evicted)
            (evicted.nodeCount > 0) shouldBe true
        }
    }

    test("walked geometry remains reusable after native snapshot expiry because every replay is freshly validated") {
        sharedRouteRuntime(CivsNavigationSettings(terrainRefreshTicks = 5)) { h ->
            val first = h.request(); h.finish(first); h.walk(first)
            h.waitTicks(10)
            val later = h.request(); h.finish(later)
            later.nodeCount shouldBe 0
            h.cache()["hits"] shouldBe 1L
            h.walk(later).distanceSquared(h.target) shouldBe 0.0
        }
    }

    test("a complete observed path on fractional native support learns its accepted endpoint without changing requested arrival") {
        sharedRouteRuntime { h ->
            h.allowedY = 63..64
            for (x in 0..8) h.world.getBlockAt(x, 63, 0).type = Material.DIRT_PATH
            val from = CitizenPoint(.5, 63.9375, .5)
            val accepted = h.target.copy(y = 63.9375)
            val first = h.request(from = from); h.finish(first)
            h.walk(first, from, accepted)
            h.cache()["entries"] shouldBe 1
            val later = h.request(from = from); h.finish(later)
            later.nodeCount shouldBe 0
            h.walk(later, from, accepted)
            h.cache()["hits"] shouldBe 1L
        }
    }

    test("native accepted arrival can publish checked geometry before its unwalked tolerance tail is cancelled") {
        sharedRouteRuntime { h ->
            val route = h.request(); h.finish(route)
            val actual = h.walkUntil(route) { it.distanceSquared(h.target) <= .75 * .75 }
            route.finished shouldBe false
            h.cache()["entries"] shouldBe 0
            citizenPacketSupported(h.world, actual) shouldBe true
            route.observeAcceptedArrival(actual)
            route.cancel()
            h.cache()["entries"] shouldBe 1
            val later = h.request(); h.finish(later)
            later.nodeCount shouldBe 0
            h.walk(later).distanceSquared(h.target) shouldBe 0.0
        }
    }

    test("consumed section stamps retain unrelated floors and reject late use of a changed old snapshot section") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-section-stamps").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotSections")))
            val snapshots = CivsNavigationSnapshots(131072, 5, tasks, contextFree = { true })
            try {
                val chunkKey = CitizenSnapshotKey(0, 0)
                val old = requireNotNull(snapshots.extracted(world, chunkKey, 0, setOf(2, 4)))
                val surfacePalette = old.states(64, 79)
                old.states(32, 47).keys.map { it.material } shouldBe listOf(Material.STONE)
                val surface = mapOf(CitizenSnapshotSectionKey(0, 4, 0) to old.revision)
                val mine = mapOf(CitizenSnapshotSectionKey(0, 2, 0) to old.revision)
                world.getBlockAt(8, 37, 8).type = Material.AIR
                repeat(20) { snapshots.changed(world.uid, CivPosition(8, 37, 8)) }
                snapshots.current(world.uid, mapOf(chunkKey to old.revision)) shouldBe false
                snapshots.currentSections(world.uid, surface) shouldBe true
                snapshots.currentSections(world.uid, mine) shouldBe false
                paper.performTicks(2)
                snapshots.currentSections(world.uid, surface) shouldBe true
                val updated = requireNotNull(snapshots.extracted(world, chunkKey, 2, setOf(2, 4)))
                updated.states(64, 79) shouldBeSameInstanceAs surfacePalette
                updated.states(32, 47).keys.map { it.material } shouldBe listOf(Material.STONE, Material.AIR)
                snapshots.debug()["chunk_captures"] shouldBe 2L
                snapshots.debug()["chunk_captures_dirty"] shouldBe 1L
                snapshots.debug()["detached_section_decodes"] shouldBe 3L
                snapshots.debug()["index_payload_bytes"] shouldBe 16384L
                val terrain = CitizenSnapshotTerrain().also { it.chunks[chunkKey] = old }
                val node = CitizenSearchNode(8, 38.0, 8)
                val request = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), terrain,
                    CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }), -64, 320, 32768, 1, false)
                    .advance(CitizenSnapshotInput())
                request.sections[CitizenSnapshotSectionKey(0, 2, 0)] shouldBe old.revision
                snapshots.currentSections(world.uid, request.sections) shouldBe false
                val expired = requireNotNull(snapshots.extracted(world, chunkKey, 5))
                snapshots.currentSections(world.uid, surface) shouldBe false // An unrelated edit cannot extend the geometry TTL.
                (expired.states(64, 79) === surfacePalette) shouldBe false
                snapshots.removeChunk(world.uid, 0, 0)
                val reloaded = requireNotNull(snapshots.extracted(world, chunkKey, 6))
                (reloaded.states(64, 79) === expired.states(64, 79)) shouldBe false
                snapshots.currentSections(world.uid, mapOf(CitizenSnapshotSectionKey(0, 4, 0) to expired.revision)) shouldBe false
                val counts = snapshots.debug()
                counts["chunk_captures"] shouldBe 4L
                counts["chunk_captures_cold"] shouldBe 2L
                counts["chunk_captures_dirty"] shouldBe 1L
                counts["chunk_captures_lru"] shouldBe 0L
                counts["chunk_captures_ttl"] shouldBe 1L
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("obstacle readback invalidates silently changed cells in a retained section") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-obstacle-inheritance").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("InheritedObstacle")))
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            try {
                val key = CitizenSnapshotKey(0, 0)
                val point = CivPosition(8, 64, 8)
                val first = requireNotNull(snapshots.extracted(world, key, 0))
                val palette = first.states(64, 79)
                world.getBlockAt(8, 64, 8).type = Material.STONE // Simulates a plugin edit without an event.
                world.getBlockAt(8, 37, 8).type = Material.AIR
                snapshots.changed(world.uid, CivPosition(8, 37, 8))
                paper.performTicks(2)
                val inherited = requireNotNull(snapshots.extracted(world, key, 2))
                inherited.states(64, 79) shouldBeSameInstanceAs palette
                inherited.data(point).material shouldBe Material.AIR
                snapshots.refreshObstacles(world.uid, listOf(point))
                snapshots.currentSections(world.uid, mapOf(CitizenSnapshotSectionKey(0, 4, 0) to inherited.revision)) shouldBe false
                paper.performTicks(2)
                val updated = requireNotNull(snapshots.extracted(world, key, 4))
                updated.data(point).material shouldBe Material.STONE
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("an ongoing surface search survives a same chunk mine floor edit without restarting its graph") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-unrelated-floor").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("UnrelatedFloor")))
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) }, snapshotContextFree = { true })
            val allowed: (CivPosition) -> Boolean = { it.x in 0..20 && it.y == 64 && it.z == 0 }
            val policy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true })
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 20.5, 64.0, .5),
                allowed, policy.stepAllowed, { 1.0 }, null, policy)
            try {
                var tick = 0
                while (!(route.capturedBlocks > 400 && route.preparationPhase == "searching") && tick++ < 100) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                (route.capturedBlocks > 400) shouldBe true
                world.getBlockAt(8, 37, 0).type = Material.AIR
                owner.changed(world.uid, CivPosition(8, 37, 0))
                owner.pulse()
                owner.debug()["stale_snapshot_restarts"] shouldBe 0L
                while (route.searching && tick++ < 250) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                route.searching shouldBe false
                route.failure shouldBe null
                route.endpoint shouldBe CitizenPoint(20.5, 64.0, .5)
                owner.debug()["stale_snapshot_restarts"] shouldBe 0L
            } finally { owner.clear(); tasks.close() }
        }
    }

    test("snapshot chunk requests preserve footprint order and require a world height overlap") {
        val cases = buildList {
            for (base in listOf(15, 0, -16)) for (y in listOf(-68, -67, -64, 64, 319, 322, 323))
                add(CitizenSearchNode(base, y.toDouble(), base))
            add(CitizenSearchNode(Int.MAX_VALUE, 64.0, 0))
            add(CitizenSearchNode(0, 64.0, Int.MIN_VALUE))
            add(CitizenSearchNode(0, Int.MAX_VALUE.toDouble(), 0))
            add(CitizenSearchNode(0, Int.MIN_VALUE.toDouble(), 0))
        }
        for (node in cases) withClue("node=$node") {
            val y = node.y.toInt()
            val positions = buildList {
                for (x in node.x - 1..node.x + 1) for (z in node.z - 1..node.z + 1)
                    for (by in y - 3..y + 3) add(CivPosition(x, by, z))
            }
            val expected = positions.filter { it.y in -64 until 320 }
                .map { CitizenSnapshotKey(it.x shr 4, it.z shr 4) }.distinct()
            val policy = CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true })
            val request = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), CitizenSnapshotTerrain(),
                policy, -64, 320, 32768, 1, false).advance(CitizenSnapshotInput())
            request.chunks.toList() shouldBe expected
            request.sections shouldBe emptyMap()
            request.states shouldBe emptyMap()
            request.dynamics shouldBe emptyMap()
            if (expected.isEmpty()) {
                val capture = requireNotNull(request.captures).single()
                capture.blocks.keys.toList() shouldBe positions
                capture.blocks.values.all { it == CitizenTerrainBlock.UNAVAILABLE.copy(cost = 1.0) } shouldBe true
                request.capturedBlocks shouldBe positions.size
            } else request.capturedBlocks shouldBe 0
        }
    }

    test("boundary chunk state and dynamic continuations retain samples revisions and ordered captured cells") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val stone = org.bukkit.Bukkit.createBlockData(Material.STONE)
            val air = org.bukkit.Bukkit.createBlockData(Material.AIR)
            val snow = org.bukkit.Bukkit.createBlockData(Material.SNOW)
            val fullBox = listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            for (base in listOf(15, -16)) withClue("base=$base") {
                val node = CitizenSearchNode(base, 64.0, base)
                val probe = CitizenSearchProbe(node, node.point())
                val positions = buildList {
                    for (x in base - 1..base + 1) for (z in base - 1..base + 1)
                        for (y in 61..67) add(CivPosition(x, y, z))
                }
                val keys = positions.map { CitizenSnapshotKey(it.x shr 4, it.z shr 4) }.distinct()
                keys.size shouldBe 4
                val unavailable = keys.last()
                val dynamic = listOf(CivPosition(base - 1, 64, base - 1), CivPosition(base - 1, 65, base - 1))
                val chunks = keys.dropLast(1).mapIndexed { index, key ->
                    val cells = if (index == 0) dynamic.associate { p -> CivPosition(p.x and 15, p.y, p.z and 15) to snow }
                        else emptyMap()
                    key to CitizenSnapshotChunk(CivsNavigationChunkSnapshotFixture(key.x, key.z, -64, 320,
                        "capture-boundary", 0, stone, cells), -64, 320, index + 100L)
                }.toMap()
                val terrain = CitizenSnapshotTerrain()
                val policy = CitizenSnapshotPolicy({ p, material -> material(p) != Material.SNOW }, { _, _ -> true })
                val capture = CitizenSnapshotCapture(listOf(probe), terrain, policy, -64, 320, 32768, 1, false)
                capture.advance(CitizenSnapshotInput()).chunks.toList() shouldBe keys
                var request = capture.advance(CitizenSnapshotInput(chunks = mapOf(keys.first() to chunks.getValue(keys.first())),
                    unavailable = setOf(unavailable)))
                request.chunks.toList() shouldBe keys.subList(1, 3)
                request = capture.advance(CitizenSnapshotInput(chunks = chunks.filterKeys { it != keys.first() }))
                val samples = linkedMapOf(stone to CivPosition(keys.first().x * 16, 48, keys.first().z * 16),
                    air to CivPosition(keys.first().x * 16, 64, keys.first().z * 16), snow to dynamic.first())
                request.states.entries.toList() shouldBe samples.entries.toList()
                request.sections.entries.map { it.key to it.value } shouldBe chunks.flatMap { (key, chunk) ->
                    listOf(CitizenSnapshotSectionKey(key.x, 3, key.z) to chunk.revision,
                        CitizenSnapshotSectionKey(key.x, 4, key.z) to chunk.revision)
                }
                val states = samples.mapValues { (data, _) -> CitizenSnapshotState(
                    CitizenTerrainShape(false, false, false, false, if (data.material.isAir) emptyList() else fullBox), data == snow) }
                request = capture.advance(CitizenSnapshotInput(states = mapOf(stone to states.getValue(stone))))
                request.states.entries.toList() shouldBe samples.filterKeys { it != stone }.entries.toList()
                request.sections shouldBe emptyMap()
                request = capture.advance(CitizenSnapshotInput(states = states.filterKeys { it != stone }))
                request.dynamics.keys.toList() shouldBe dynamic
                val boxes = dynamic.mapIndexed { index, p ->
                    p to listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .25 + index * .5, 1.0))
                }.toMap()
                request = capture.advance(CitizenSnapshotInput(dynamics = mapOf(dynamic.first() to boxes.getValue(dynamic.first()))))
                request.dynamics.keys.toList() shouldBe dynamic.drop(1)
                request = capture.advance(CitizenSnapshotInput(dynamics = mapOf(dynamic.last() to boxes.getValue(dynamic.last()))))
                request.capturedBlocks shouldBe 63
                request.costs.isNotEmpty() shouldBe true
                val costs = request.costs.associateWith { 2.25 }
                val completed = requireNotNull(capture.advance(CitizenSnapshotInput(costs = costs)).captures).single()
                completed.blocks.keys.toList() shouldBe positions
                for ((point, block) in completed.blocks) {
                    if (CitizenSnapshotKey(point.x shr 4, point.z shr 4) == unavailable)
                        block shouldBe CitizenTerrainBlock.UNAVAILABLE.copy(cost = 1.0)
                    else {
                        block.loaded shouldBe true
                        block.allowed shouldBe (point !in dynamic)
                        block.boxes shouldBe (boxes[point] ?: if (point.y <= 63) fullBox else emptyList())
                        block.cost shouldBe (costs[point] ?: 1.0)
                    }
                }
            }
        }
    }

    test("mixed cached cells wait for every dynamic shape before adding new cells and keep shared references") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val dynamic = listOf(CivPosition(4, 64, 1), CivPosition(4, 65, 3))
            val terrain = flatSnapshotTerrain(0, 0, dynamic.associateWith { org.bukkit.Bukkit.createBlockData(Material.SNOW) })
            val first = CitizenSearchNode(2, 64.0, 2)
            completeSnapshotCapture(terrain, listOf(CitizenSearchProbe(first, first.point())), 1.0)
            val cached = terrain.blocks.toMap()
            var allowedReads = 0
            val policy = CitizenSnapshotPolicy({ p, _ -> allowedReads++; p.y == 64 }, { _, _ -> true })
            val node = CitizenSearchNode(3, 64.0, 2)
            val capture = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), terrain,
                policy, -64, 320, 84, 1, false)
            var request = capture.advance(CitizenSnapshotInput())
            request.dynamics.keys.toList() shouldBe dynamic
            terrain.blocks.size shouldBe 63
            allowedReads shouldBe 0
            val shapes = dynamic.mapIndexed { index, point -> point to listOf(
                CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .25 + index * .5, 1.0)) }.toMap()
            request = capture.advance(CitizenSnapshotInput(dynamics = mapOf(dynamic.first() to shapes.getValue(dynamic.first()))))
            request.dynamics.keys.toList() shouldBe dynamic.drop(1)
            terrain.blocks.size shouldBe 63
            allowedReads shouldBe 0
            for ((point, block) in cached) terrain.blocks[point] shouldBeSameInstanceAs block
            request = capture.advance(CitizenSnapshotInput(dynamics = mapOf(dynamic.last() to shapes.getValue(dynamic.last()))))
            terrain.blocks.size shouldBe 84
            allowedReads shouldBe 21
            val completed = requireNotNull(capture.advance(CitizenSnapshotInput(
                costs = request.costs.associateWith { 1.0 })).captures).single()
            completed.blocks.keys.toList() shouldBe buildList {
                for (x in 2..4) for (z in 1..3) for (y in 61..67) add(CivPosition(x, y, z))
            }
            for ((point, block) in cached) if (point in completed.blocks) completed.blocks[point] shouldBeSameInstanceAs block
            dynamic.forEach { completed.blocks.getValue(it).boxes shouldBe shapes.getValue(it) }
        }
    }

    test("cancelling a pending dynamic capture keeps cached cells and rejects late shape input") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val point = CivPosition(4, 64, 2)
            val terrain = flatSnapshotTerrain(0, 0, mapOf(point to org.bukkit.Bukkit.createBlockData(Material.SNOW)))
            val first = CitizenSearchNode(2, 64.0, 2)
            completeSnapshotCapture(terrain, listOf(CitizenSearchProbe(first, first.point())), 1.0)
            val cached = terrain.blocks.toMap()
            val node = CitizenSearchNode(3, 64.0, 2)
            val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
            val capture = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), terrain,
                policy, -64, 320, 84, 1, false)
            capture.advance(CitizenSnapshotInput()).dynamics.keys shouldBe setOf(point)
            capture.cancel()
            shouldThrow<CancellationException> { capture.advance(CitizenSnapshotInput(dynamics = mapOf(point to emptyList()))) }
            terrain.blocks shouldBe cached
            terrain.dynamics shouldBe emptyMap()
        }
    }

    test("a fully cached neighbourhood completes at the block limit without reevaluating cell policy") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(0, 0)
            val node = CitizenSearchNode(2, 64.0, 2)
            val probes = listOf(CitizenSearchProbe(node, node.point()))
            val before = completeSnapshotCapture(terrain, probes, 1.0).single()
            val policy = CitizenSnapshotPolicy({ _, _ -> error("Cached cell policy must remain unchanged") }, { _, _ -> true })
            val request = CitizenSnapshotCapture(probes, terrain, policy, -64, 320, 63, 1, false).advance(CitizenSnapshotInput())
            request.capturedBlocks shouldBe 63
            val completed = requireNotNull(request.captures).single()
            completed.blocks shouldBe before.blocks
            for ((point, block) in before.blocks) completed.blocks[point] shouldBeSameInstanceAs block
        }
    }

    test("job cell candidate slots preserve ordered edges across fractional heights ladders and initial offsets") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val slab = Bukkit.createBlockData(Material.STONE_SLAB)
            val ladder = Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
            fun terrain(): CitizenSnapshotTerrain = flatSnapshotTerrain(0, 0, mapOf(
                CivPosition(3, 62, 2) to Bukkit.createBlockData(Material.AIR), CivPosition(3, 63, 2) to slab,
                CivPosition(4, 64, 2) to ladder, CivPosition(4, 65, 2) to ladder)).also {
                it.states[slab] = CitizenSnapshotState(CitizenTerrainShape(false, false, false, false,
                    listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                        CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .9375, 1.0))), false)
                it.states[ladder] = CitizenSnapshotState(CitizenTerrainShape(false, false, true, false, emptyList()), false)
            }
            fun capture(terrain: CitizenSnapshotTerrain, probe: CitizenSearchProbe): CitizenSearchCapture =
                requireNotNull(CitizenSnapshotCapture(listOf(probe), terrain,
                    CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }, supportCost = { _, _ -> 1.0 }),
                    -64, 320, 32768, 1, false).advance(CitizenSnapshotInput()).captures).single()
            val normal = CitizenSearchNode(2, 64.0, 2)
            val initial = normal.copy(initial = true)
            val probes = listOf(CitizenSearchProbe(normal, normal.point()),
                CitizenSearchProbe(initial, CitizenPoint(2.25, 64.0, 2.75)),
                CitizenSearchProbe(CitizenSearchNode(3, 63.5, 3), CitizenPoint(3.5, 63.5, 3.5)),
                CitizenSearchProbe(CitizenSearchNode(4, 64.0, 2, climbing = true), CitizenPoint(4.5, 64.0, 2.5)))
            val local = terrain()
            val captures = (probes + probes).map { probe -> capture(local, probe).also { got ->
                got.edges shouldBe citizenSearchCandidates(probe, got.blocks.toMap(), -64, 320)
            } }
            val first = captures[0]
            val repeated = captures[4]
            fun standing(capture: CitizenSearchCapture, y: Double) = capture.edges.single {
                it.node.x == 3 && it.node.z == 2 && it.node.y.toBits() == y.toBits() && !it.node.climbing }.node
            standing(first, 63.5) shouldBeSameInstanceAs standing(repeated, 63.5)
            (standing(first, 63.9375) === standing(repeated, 63.9375)) shouldBe false
            (standing(first, 63.5) === standing(first, 63.9375)) shouldBe false
            captures[1].edges.single { it.node.x == 2 && it.node.z == 2 && it.node.y == 64.0 && !it.node.climbing }.node.initial shouldBe false
            val firstClimb = captures[3].edges.first { it.node.climbing }.node
            val nextClimb = captures[7].edges.first { it.node == firstClimb }.node
            (firstClimb === nextClimb) shouldBe false
            val anotherJob = capture(terrain(), probes[0])
            (standing(first, 63.5) === standing(anotherJob, 63.5)) shouldBe false
        }
    }

    test("published search neighbourhoods reuse directed edges and immutable costs only for the same noninitial origin") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            for (height in listOf(64.0, 63.5)) {
                val terrain = flatSnapshotTerrain(0, 0)
                val node = CitizenSearchNode(2, height, 2)
                val probe = CitizenSearchProbe(node, node.point())
                val published = completeSnapshotCapture(terrain, listOf(probe), 2.25).single()
                val initial = probe.copy(node = node.copy(initial = true), from = node.point().copy(x = 2.25, z = 2.75))
                val initialCapture = completeSnapshotCapture(terrain, listOf(initial), 2.25).single()
                val search = CitizenNavigationSearch(initial.from, CitizenPoint(12.5, 64.0, 2.5), -64, 320, emptyList())
                search.advance(listOf(published, initialCapture))
                search.captured(probe) shouldBeSameInstanceAs published
                search.captured(initial) shouldBe null
                search.captured(probe.copy(incoming = true)) shouldBe null
                search.captured(probe.copy(from = initial.from)) shouldBe null
                val later = published.blocks.keys.first { it !in terrain.costs }
                val policy = CitizenSnapshotPolicy({ _, _ -> error("Repeated cell policy") }, { _, _ -> error("Repeated directed policy") })
                val request = CitizenSnapshotCapture(listOf(probe), terrain, policy, -64, 320, terrain.blocks.size, 1, false)
                    .advance(CitizenSnapshotInput(costs = mapOf(later to Double.POSITIVE_INFINITY)), search::captured)
                val reused = requireNotNull(request.captures).single()
                reused.blocks shouldBeSameInstanceAs published.blocks
                reused.edges shouldBeSameInstanceAs published.edges
                reused.blocks.getValue(later).cost shouldBe 1.0
                request.costs shouldBe emptySet()
                request.sections shouldBe emptyMap()
                request.capturedBlocks shouldBe terrain.blocks.size
                val cancelled = CitizenSnapshotCapture(listOf(probe), terrain, policy, -64, 320, terrain.blocks.size, 1, false)
                cancelled.cancel()
                shouldThrow<CancellationException> { cancelled.advance(CitizenSnapshotInput(), search::captured) }
                val fresh = CitizenNavigationSearch(initial.from, CitizenPoint(12.5, 64.0, 2.5), -64, 320, emptyList())
                fresh.captured(probe) shouldBe null
            }
        }
    }

    test("reused queue slots preserve current prefetch bounds and capped tails across native input resumes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            data class Run(val captures: List<CitizenSearchCapture>, val cursor: CitizenNavigationDebugCursor,
                val cells: Set<CivPosition>, val stepChecks: Int, val sections: Map<CitizenSnapshotSectionKey, Long>)
            fun run(reuse: Boolean, maximum: Int, bounded: Boolean): Run {
                val terrain = flatSnapshotTerrain(0, 0)
                val oldNode = CitizenSearchNode(14, 64.0, 3)
                val oldProbe = CitizenSearchProbe(oldNode, oldNode.point(),
                    bounds = CitizenSearchBounds.between(oldNode.point(), oldNode.point(), 0, 0))
                val prior = completeSnapshotCapture(terrain, listOf(oldProbe), 2.25).single()
                val search = CitizenNavigationSearch(oldNode.point(), CitizenPoint(24.5, 64.0, 4.5), -64, 320, emptyList())
                search.advance(listOf(prior))
                val node = CitizenSearchNode(15, 64.0, 4)
                val probe = CitizenSearchProbe(node, node.point(), bounds = if (bounded)
                    CitizenSearchBounds.between(node.point(), node.point(), 1, 0) else null)
                var stepChecks = 0
                val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> stepChecks++; true })
                val cursor = CitizenSnapshotCapture(listOf(probe), terrain, policy, -64, 320, maximum, 12, true)
                val lookup = if (reuse) search::captured else null
                val missing = cursor.advance(CitizenSnapshotInput(), lookup)
                missing.chunks shouldBe setOf(CitizenSnapshotKey(1, 0))
                missing.captures shouldBe null
                val extra = flatSnapshotTerrain(1, 0)
                var request = cursor.advance(CitizenSnapshotInput(chunks = extra.chunks, states = extra.states), lookup)
                val sections = request.sections
                if (request.captures == null) {
                    request.chunks shouldBe emptySet()
                    request.states shouldBe emptyMap()
                    request.dynamics shouldBe emptyMap()
                    request = cursor.advance(CitizenSnapshotInput(costs = request.costs.associateWith { 2.25 }), lookup)
                }
                val captures = requireNotNull(request.captures)
                if (reuse) captures.single { it.probe.node == oldNode }.blocks shouldBeSameInstanceAs prior.blocks
                return Run(captures, cursor.debugCursor(), terrain.blocks.keys.toSet(), stepChecks, sections)
            }
            for (maximum in listOf(99, 32768)) for (bounded in listOf(false, true)) {
                val old = run(false, maximum, bounded)
                val reused = run(true, maximum, bounded)
                reused.captures.map { it.probe.node } shouldBe old.captures.map { it.probe.node }
                reused.captures.map { it.probe.from } shouldBe old.captures.map { it.probe.from }
                reused.captures.map { it.edges } shouldBe old.captures.map { it.edges }
                fun supportCosts(run: Run) = run.captures.map { capture -> capture.edges.map { edge ->
                    capture.blocks[edge.node.x, citizenSupportY(edge.node.y), edge.node.z]?.cost
                } }
                supportCosts(reused) shouldBe supportCosts(old)
                reused.cursor.required shouldBe old.cursor.required
                reused.cursor.next shouldBe old.cursor.next
                reused.cursor.probes.map { it.node } shouldBe old.cursor.probes.map { it.node }
                reused.cells shouldBe old.cells
                reused.sections shouldBe old.sections
                (reused.stepChecks < old.stepChecks) shouldBe true
                if (maximum == 99) reused.captures.size shouldBe 2
            }
        }
    }

    test("a warmed search keeps its progress through repeated unrelated edits in its own section") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-reuse-restart").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("ReuseRestart")))
            val workers = ArrayDeque<Runnable>()
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) },
                settings = CivsNavigationSettings(searchNodesPerBatch = 2), snapshotCache = snapshots)
            val key = CitizenSnapshotKey(0, 0)
            val warm = requireNotNull(snapshots.capture(world, key, 0, setOf(3, 4)))
            warm.prepare()
            warm.releaseSource()
            snapshots.publish(world.uid, warm.payloads())
            val allowed: (CivPosition) -> Boolean = { it.x in 0..20 && it.y == 64 && it.z == 0 }
            val policy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true })
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 20.5, 64.0, .5),
                allowed, policy.stepAllowed, { 1.0 }, null, policy)
            try {
                var tick = 0
                while (owner.debug()["used_snapshot_sections"] == 0 && tick++ < 150) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                route.searching shouldBe true
                val edit = CivPosition(5, 64, 5)
                var edits = 0
                while (route.searching && tick++ < 350) {
                    world.getBlockAt(edit.x, edit.y, edit.z).type = if (edits++ % 2 == 0) Material.STONE else Material.COBBLESTONE
                    owner.changed(world.uid, edit)
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                (edits > 2) shouldBe true
                route.searching shouldBe false
                route.failure shouldBe null
                route.continuation shouldBe false
                route.endpoint shouldBe CitizenPoint(20.5, 64.0, .5)
                owner.debug()["stale_snapshot_restarts"] shouldBe 0L
                val totals = (owner.debug()["preparation_lifetime"] as Map<*, *>)["published_work"] as Map<*, *>
                val ready = totals["ready"] as Map<*, *>
                ready["nodes"] shouldBe route.nodeCount.toLong()
                ready["captured_cells"] shouldBe route.capturedBlocks.toLong()
                ready["search_nanos"] shouldBe route.searchNanos
                paper.performTicks(3)
                val wanted = CitizenSnapshotSectionKey(0, 4, 0)
                snapshots.publish(world.uid, warm.payloads())
                snapshots.cached(world, listOf(wanted), org.bukkit.Bukkit.getCurrentTick().toLong()).isEmpty() shouldBe true
                val fresh = requireNotNull(snapshots.capture(world, key, org.bukkit.Bukkit.getCurrentTick().toLong(), setOf(4)))
                fresh.prepare()
                fresh.releaseSource()
                fresh.data(edit).material shouldBe world.getBlockAt(edit.x, edit.y, edit.z).type
            } finally { owner.clear(); tasks.close() }
        }
    }

    test("an exhausted stale search retries once and finds a passage only if it has opened") {
        for (opened in listOf(true, false)) sharedRouteRuntime { h ->
            val wall = (64..66).map { y -> h.world.getBlockAt(1, y, 0) }
            wall.forEach { it.type = Material.STONE }
            val route = h.request(reuseKey = null)
            h.prepareUntil { h.owner.debug()["used_snapshot_sections"] != 0 }
            if (opened) wall.forEach { it.type = Material.AIR }
            val change = if (opened) CivPosition(1, 64, 0) else CivPosition(4, 64, 4)
            h.owner.changed(h.world.uid, change)
            h.prepareUntil { h.owner.debug()["stale_snapshot_restarts"] == 1L }
            h.prepareUntil { h.owner.debug()["used_snapshot_sections"] != 0 }
            // Make the second verdict stale as well. A genuinely closed passage must still terminate.
            h.owner.changed(h.world.uid, CivPosition(5, 64, 5))
            h.finish(route)
            h.owner.debug()["stale_snapshot_restarts"] shouldBe 1L
            route.failure shouldBe if (opened) null else "no-path"
            if (opened) h.walk(route) shouldBe h.target
        }
    }

    test("a warmed detour yields to a short job and its cancelled worker cannot publish more progress") {
        sharedRouteRuntime(CivsNavigationSettings(searchNodesPerBatch = 1)) { h ->
            val warmed = h.request(reuseKey = null, surfaceSpeed = h.footTraffic)
            h.finish(warmed)
            warmed.cancel()
            h.allowedZ = 0..8
            val detour = h.request(reuseKey = null, surfaceSpeed = h.footTraffic,
                allowedPoint = { it.x != 4 || it.z == 8 })
            var nearbySampled = false
            val nearby = h.request(target = CitizenPoint(2.5, 64.0, .5), reuseKey = null, surfaceSpeed = h.footTraffic,
                allowedPoint = { nearbySampled = true; true })
            repeat(40) { if (!nearbySampled) h.stepWorker() }
            nearbySampled shouldBe true
            detour.searching shouldBe true
            val published = detour.nodeCount
            detour.cancel()
            h.finish(nearby)
            nearby.endpoint shouldBe CitizenPoint(2.5, 64.0, .5)
            h.prepareUntil { h.owner.debug()["jobs"] == 0 }
            detour.nodeCount shouldBe published
            detour.endpoint shouldBe null
            detour.preparationPhase shouldBe "cancelled"
        }
    }

    test("an inline executor and a full continuation queue return a resumable bounded worker turn") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            for (inline in listOf(true, false)) {
                val terrain = flatSnapshotTerrain(0, 0)
                terrain.chunks.values.forEach { it.drainExtracted(); it.releaseSource() }
                val policy = CitizenSnapshotPolicy({ p, _ ->
                    p.y == 64 && p.x in 2..13 && p.z in 2..13 && (p.x != 7 || p.z == 11)
                }, { _, _ -> true }, supportCost = { _, _ -> 1.0 })
                val target = CitizenPoint(13.5, 64.0, 2.5)
                val search = CitizenNavigationSearch(CitizenPoint(2.5, 64.0, 2.5), target, -64, 320, emptyList(),
                    CivsNavigationSettings(searchNodesPerBatch = 1))
                val initial = search.advance(emptyList())
                val cursor = CitizenSnapshotCapture(initial.probes, terrain, policy, -64, 320, 32768, 1, false)
                val batch = CitizenSnapshotSearchBatch(cursor, search, combine = true)
                val queued = ArrayDeque<Runnable>()
                var calls = 0
                batch.submit(Executor { task ->
                    calls++
                    if (inline) task.run()
                    else if (calls == 1) queued.addLast(task)
                    else throw java.util.concurrent.RejectedExecutionException()
                }, CitizenSnapshotInput())
                queued.removeFirstOrNull()?.run()
                batch.completion.join()
                batch.searchReady shouldBe true
                calls shouldBe if (inline) 1 else 2
                var result = requireNotNull(batch.progress)
                result.probes.isNotEmpty() shouldBe true
                while (result.probes.isNotEmpty()) {
                    val next = cursor.following(result.probes).advance(CitizenSnapshotInput(), search::captured)
                    result = search.advance(requireNotNull(next.captures))
                }
                requireNotNull(result.path).last().point shouldBe target
            }
        }
    }

    test("preparation work is counted once for ready failed and cancelled jobs using only published progress") {
        sharedRouteRuntime { h ->
            fun work(outcome: String): Map<*, *> = ((h.owner.debug()["preparation_lifetime"] as Map<*, *>)
                ["published_work"] as Map<*, *>)[outcome] as Map<*, *>
            fun check(outcome: String, route: CitizenPacketRoute) {
                work(outcome)["nodes"] shouldBe route.nodeCount.toLong()
                work(outcome)["captured_cells"] shouldBe route.capturedBlocks.toLong()
                work(outcome)["search_nanos"] shouldBe route.searchNanos
                work(outcome)["native_shape_reads"] shouldBe route.nativeShapeReads.toLong()
            }
            val ready = h.request(reuseKey = null)
            h.finish(ready)
            ready.failure shouldBe null
            check("ready", ready)
            val failed = h.request(reuseKey = null, permitted = { false })
            h.finish(failed)
            failed.failure shouldBe "no-path"
            check("failed", failed)
            val cancelled = h.request(reuseKey = null)
            h.prepareUntil { cancelled.capturedBlocks > 0 }
            cancelled.searching shouldBe true
            (cancelled.nodeCount > 0) shouldBe true
            cancelled.cancel(); h.owner.pulse()
            check("cancelled", cancelled)
            val before = (h.owner.debug()["preparation_lifetime"] as Map<*, *>).toMap()
            repeat(3) { h.owner.pulse() }
            h.owner.debug()["preparation_lifetime"] shouldBe before
        }
    }

    test("requested new cells enforce the block limit in existing cell order after cached cells") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(0, 0)
            val first = CitizenSearchNode(2, 64.0, 2)
            completeSnapshotCapture(terrain, listOf(CitizenSearchProbe(first, first.point())), 1.0)
            val cached = terrain.blocks.keys.toSet()
            val node = CitizenSearchNode(3, 64.0, 2)
            val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
            val capture = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), terrain,
                policy, -64, 320, 64, 1, false)
            shouldThrow<CitizenNavigationCaptureLimit> { capture.advance(CitizenSnapshotInput()) }
            terrain.blocks.keys - cached shouldBe setOf(CivPosition(4, 61, 1))
            terrain.blocks.size shouldBe 64
        }
    }

    test("a partial prefetched neighbourhood at the block limit publishes only completed requested probes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(0, 0)
            val node = CitizenSearchNode(2, 64.0, 2)
            val probe = CitizenSearchProbe(node, node.point())
            val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
            val capture = CitizenSnapshotCapture(listOf(probe), terrain, policy, -64, 320, 64, 2, false)
            val request = capture.advance(CitizenSnapshotInput())
            request.capturedBlocks shouldBe 64
            val completed = requireNotNull(capture.advance(CitizenSnapshotInput(
                costs = request.costs.associateWith { 1.0 })).captures)
            completed.size shouldBe 1
            completed.single().probe shouldBe probe
            completed.single().blocks.size shouldBe 63
            terrain.blocks.keys - completed.single().blocks.keys shouldBe setOf(CivPosition(0, 61, 0))
        }
    }

    test("snapshot captures reuse unchanged blocks and preserve the complete ordered neighbourhood map") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            for (base in listOf(-3, 2)) {
                val terrain = flatSnapshotTerrain(base shr 4, base shr 4)
                val probes = listOf(base, base + 1).map { x ->
                    val node = CitizenSearchNode(x, 64.0, base)
                    CitizenSearchProbe(node, node.point())
                }
                val captures = completeSnapshotCapture(terrain, probes, 1.0)
                for (point in captures.first().blocks.keys.intersect(captures.last().blocks.keys)) {
                    captures.first().blocks.keys.single { it == point } shouldBeSameInstanceAs
                        captures.last().blocks.keys.single { it == point }
                }
                for (capture in captures) {
                    val node = capture.probe.node
                    val expected = buildMap {
                        for (x in node.x - 1..node.x + 1) for (z in node.z - 1..node.z + 1)
                            for (y in 61..67) put(CivPosition(x, y, z), terrain.blocks.getValue(CivPosition(x, y, z)))
                    }
                    capture.blocks shouldBe expected
                    capture.blocks.keys.toList() shouldBe expected.keys.toList()
                    capture.blocks.size shouldBe 63
                    val fallback = capture.blocks.toMap()
                    for ((point, block) in expected) {
                        capture.blocks[point] shouldBeSameInstanceAs block
                        capture.blocks[point.x, point.y, point.z] shouldBeSameInstanceAs block
                        fallback[point.x, point.y, point.z] shouldBeSameInstanceAs block
                    }
                    for (point in listOf(CivPosition(node.x - 2, 64, node.z), CivPosition(node.x + 2, 64, node.z),
                        CivPosition(node.x, 60, node.z), CivPosition(node.x, 68, node.z),
                        CivPosition(node.x, 64, node.z - 2), CivPosition(node.x, 64, node.z + 2),
                        CivPosition(Int.MIN_VALUE, 64, node.z), CivPosition(Int.MAX_VALUE, 64, node.z))) {
                        capture.blocks[point] shouldBe null
                        capture.blocks[point.x, point.y, point.z] shouldBe null
                        fallback[point.x, point.y, point.z] shouldBe null
                        capture.blocks.containsKey(point) shouldBe false
                    }
                }
            }
        }
    }

    test("support cost requests reuse captured keys for outgoing incoming and fractional candidates") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val slab = paper.server.createBlockData("minecraft:stone_slab[type=bottom,waterlogged=false]")
            for (fractional in listOf(false, true)) for (incoming in listOf(false, true))
                withClue("fractional=$fractional incoming=$incoming") {
                    val terrain = flatSnapshotTerrain(0, 0,
                        if (fractional) mapOf(CivPosition(3, 63, 2) to slab) else emptyMap())
                    if (fractional) terrain.states[slab] = CitizenSnapshotState(CitizenTerrainShape(
                        false, false, false, false, listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))), false)
                    val node = CitizenSearchNode(2, 64.0, 2)
                    val policy = CitizenSnapshotPolicy({ p, material ->
                        material(p)?.let { it.isAir || it == Material.STONE_SLAB } == true
                    }, { from, to -> to.x > from.x })
                    val capture = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point(), incoming)),
                        terrain, policy, -64, 320, 32768, 1, false)
                    val request = capture.advance(CitizenSnapshotInput())
                    request.chunks shouldBe emptySet()
                    request.states shouldBe emptyMap()
                    request.dynamics shouldBe emptyMap()
                    request.costs.isNotEmpty() shouldBe true
                    val result = requireNotNull(capture.advance(CitizenSnapshotInput(
                        costs = request.costs.associateWith { 2.25 })).captures).single()
                    request.costs shouldBe result.edges.map { edge ->
                        CivPosition(edge.node.x, citizenSupportY(edge.node.y), edge.node.z)
                    }.toSet()
                    result.edges.any { it.permitted } shouldBe true
                    result.edges.any { !it.permitted } shouldBe true
                    result.edges.forEach { edge ->
                        edge.permitted shouldBe if (incoming) node.x > edge.node.x else edge.node.x > node.x
                    }
                    if (fractional) result.edges.any { it.node.x == 3 && it.node.z == 2 && it.node.y == 63.5 } shouldBe true
                    for (key in request.costs) {
                        key shouldBeSameInstanceAs result.blocks.keys.single { it == key }
                        result.blocks.getValue(key).cost shouldBe 2.25
                    }
                }
        }
    }

    test("unit support costs and uncosted unavailable cells reuse their captured blocks across probes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(0, 0)
            terrain.unavailable += CitizenSnapshotKey(-1, 0)
            val probes = listOf(2, 3).map { z ->
                val node = CitizenSearchNode(0, 64.0, z)
                CitizenSearchProbe(node, node.point())
            }
            val captures = completeSnapshotCapture(terrain, probes, 1.0)
            captures.size shouldBe probes.size
            terrain.costs.isNotEmpty() shouldBe true
            val unavailable = terrain.blocks.getValue(CivPosition(-1, 64, 2))
            unavailable shouldBe CitizenTerrainBlock.UNAVAILABLE.copy(cost = 1.0)
            for (capture in captures) {
                capture.blocks.getValue(CivPosition(-1, 64, 2)) shouldBeSameInstanceAs unavailable
                for ((point, block) in capture.blocks) block shouldBeSameInstanceAs terrain.blocks.getValue(point)
            }
        }
    }

    test("overlapping snapshot captures share resolved costs while later cost knowledge leaves published captures unchanged") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(0, 0)
            val probes = listOf(2, 3).map { x ->
                val node = CitizenSearchNode(x, 64.0, 2)
                CitizenSearchProbe(node, node.point())
            }
            val captures = completeSnapshotCapture(terrain, probes, 2.25)
            val shared = CivPosition(3, 63, 2)
            captures.first().blocks.getValue(shared).cost shouldBe 2.25
            captures.first().blocks[shared] shouldBeSameInstanceAs captures.last().blocks[shared]
            val oldValues = captures.map { it.blocks.toMap() }
            val newlyCosted = captures.first().blocks.keys.first { it !in terrain.costs }
            val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
            val next = CitizenSnapshotCapture(probes, terrain, policy, -64, 320, 32768, probes.size, false)
                .advance(CitizenSnapshotInput(costs = mapOf(newlyCosted to Double.POSITIVE_INFINITY)))
            requireNotNull(next.captures).first().blocks.getValue(newlyCosted).cost shouldBe Double.POSITIVE_INFINITY
            captures.map { it.blocks } shouldBe oldValues
            captures.first().blocks.getValue(newlyCosted).cost shouldBe 1.0
            captures.first().blocks[newlyCosted.x, newlyCosted.y, newlyCosted.z]?.cost shouldBe 1.0
            requireNotNull(next.captures).first().blocks[newlyCosted.x, newlyCosted.y, newlyCosted.z]?.cost shouldBe
                Double.POSITIVE_INFINITY
        }
    }

    test("independent routes share decoded passability but apply their own cell policy") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val stone = paper.server.createBlockData("minecraft:stone")
            val native = CivsNavigationChunkSnapshotFixture(0, 0, -64, 320, "shared-passability", 0, stone, emptyMap())
            var snapshotReads = 0
            val snapshot = object : ChunkSnapshot by native {
                override fun getBlockData(x: Int, y: Int, z: Int): BlockData {
                    snapshotReads++
                    return native.getBlockData(x, y, z)
                }
            }
            val chunk = CitizenSnapshotChunk(snapshot, -64, 320)
            val key = CitizenSnapshotKey(0, 0)
            val full = listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            val palette = chunk.states(61, 67).mapValues { (data, _) ->
                CitizenSnapshotState(CitizenTerrainShape(false, false, false, false,
                    if (data.material.isAir) emptyList() else full), dynamic = false)
            }
            snapshotReads shouldBe 8192
            val node = CitizenSearchNode(2, 64.0, 2)
            fun route(allowed: Boolean): CitizenSearchCapture {
                val checked = mutableListOf<CivPosition>()
                val policy = CitizenSnapshotPolicy({ point, material ->
                    checked += point
                    allowed && material(point) == Material.AIR
                }, { _, _ -> true })
                val capture = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), CitizenSnapshotTerrain(),
                    policy, -64, 320, 63, 1, false)
                val request = capture.advance(CitizenSnapshotInput(chunks = mapOf(key to chunk), states = palette))
                val result = requireNotNull(capture.advance(CitizenSnapshotInput(costs = request.costs.associateWith { 1.0 })).captures).single()
                checked shouldBe result.blocks.keys.toList()
                return result
            }
            val admitted = route(true)
            val denied = route(false)
            val feet = CivPosition(2, 64, 2)
            admitted.blocks.getValue(feet).allowed shouldBe true
            denied.blocks.getValue(feet).allowed shouldBe false
            admitted.blocks.getValue(feet) shouldBeSameInstanceAs admitted.blocks.getValue(feet.copy(x = 3))
            admitted.blocks.getValue(feet) shouldBeSameInstanceAs route(true).blocks.getValue(feet)
            denied.blocks.getValue(feet) shouldBeSameInstanceAs denied.blocks.getValue(feet.copy(y = 65))
            denied.blocks.getValue(feet) shouldBeSameInstanceAs route(false).blocks.getValue(feet)
            for ((point, block) in admitted.blocks) block shouldBe CitizenTerrainBlock(true, false, false, false,
                false, point.y >= 64, 1.0, if (point.y >= 64) emptyList() else full)
            for ((point, block) in denied.blocks) block shouldBe CitizenTerrainBlock(true, false, false, false,
                false, false, 1.0, if (point.y >= 64) emptyList() else full)
            admitted.blocks.getValue(feet.copy(y = 63)).boxes shouldBeSameInstanceAs denied.blocks.getValue(feet.copy(y = 63)).boxes
            snapshotReads shouldBe 8192
        }
    }

    test("a pending probe pins its palette and a new search resolves the replacement in bounded batches") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val snow = paper.server.createBlockData(Material.SNOW)
            val at = CivPosition(2, 64, 2)
            val terrain = flatSnapshotTerrain(0, 0, mapOf(at to snow))
            val key = CitizenSnapshotKey(0, 0)
            val original = terrain.chunks.getValue(key).snapshot
            val node = CitizenSearchNode(at.x, at.y.toDouble(), at.z)
            val policy = CitizenSnapshotPolicy({ p, _ -> p.y == at.y }, { _, _ -> true })
            fun cursor(view: CitizenSnapshotTerrain) = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())),
                view, policy, -64, 320, 63, 1, false)
            val pending = cursor(terrain)
            pending.advance(CitizenSnapshotInput()).dynamics shouldBe mapOf(at to snow)

            // These extra states are in the same immutable section, outside the probe's 63 cells.
            val extra = List(257) { mockk<BlockData>() }
            val replacement = object : ChunkSnapshot by original {
                override fun getBlockData(x: Int, y: Int, z: Int): BlockData =
                    extra.getOrNull((y - 70) * 256 + z * 16 + x) ?: original.getBlockData(x, y, z)
            }
            fun updated() = CitizenSnapshotInput(chunks = mapOf(key to CitizenSnapshotChunk(replacement, -64, 320)))
            val pinned = pending.advance(updated())
            pinned.states.isEmpty() shouldBe true
            pinned.dynamics shouldBe mapOf(at to snow)
            val capture = cursor(CitizenSnapshotTerrain().also { it.states.putAll(terrain.states) })
            var request = capture.advance(updated())
            request.states.keys.toList() shouldBe extra.take(256)
            val state = terrain.states.getValue(paper.server.createBlockData(Material.AIR))
            request = capture.advance(CitizenSnapshotInput(states = request.states.keys.associateWith { state }))
            request.states.keys.toList() shouldBe listOf(extra.last())
            request = capture.advance(CitizenSnapshotInput(states = mapOf(extra.last() to state)))
            request.dynamics shouldBe mapOf(at to snow)
            request = capture.advance(CitizenSnapshotInput(dynamics = mapOf(at to emptyList())))
            val result = capture.advance(CitizenSnapshotInput(costs = request.costs.associateWith { 1.0 }))
            requireNotNull(result.captures).single().blocks.size shouldBe 63
        }
    }

    test("immutable chunk palette ranges reuse their merged state map and keep later section sample precedence") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val terrain = flatSnapshotTerrain(-1, -1)
            val chunk = terrain.chunks.getValue(CitizenSnapshotKey(-1, -1))
            val first = chunk.states(61, 67)
            val stone = first.keys.single { it.material == Material.STONE }
            val air = first.keys.single { it.material == Material.AIR }
            first.getValue(stone) shouldBe CivPosition(-16, 48, -16)
            first.getValue(air) shouldBe CivPosition(-16, 64, -16)
            chunk.states(60, 68) shouldBeSameInstanceAs first
            chunk.states(61, 63).getValue(stone) shouldBe CivPosition(-16, 48, -16)
            chunk.states(64, 67).getValue(air) shouldBe CivPosition(-16, 64, -16)
        }
    }

    test("registered player block listeners ignore cancellation and batch accepted edits") {
        residentRuntime { h ->
            h.warmSnapshotFromWorkRoute()
            fun cache() = (h.owner.debug(null, null).getValue("planner_global") as Map<*, *>)["snapshot_cache"] as Map<*, *>
            val captured = cache()["chunk_captures"] as Long
            (captured > 0) shouldBe true
            val block = h.world.getBlockAt(8, 63, 8)
            val player = h.paper.server.addPlayer()
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockBreakEvent(block, player).also { it.isCancelled = true })
            cache()["dirty_chunks"] shouldBe 0
            repeat(100) { h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockBreakEvent(block, player)) }
            cache()["dirty_chunks"] shouldBe 1
            cache()["chunk_captures"] shouldBe captured
            block.type = Material.AIR // Bukkit applies the native edit after the event listeners return.
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured
            cache()["deferred_native_recaptures"] shouldBe 1L
            cache()["refresh_batches"] shouldBe 1L
        }
    }

    test("piston listeners refresh empty head changes and the actual retract destination") {
        residentRuntime { h ->
            h.warmSnapshotFromWorkRoute()
            fun cache() = (h.owner.debug(null, null).getValue("planner_global") as Map<*, *>)["snapshot_cache"] as Map<*, *>
            val captured = cache()["chunk_captures"] as Long
            val changed = hashSetOf<CivPosition>()
            h.owner.worksiteTerrainChanged = { _, p -> changed += p }
            val east = org.bukkit.block.BlockFace.EAST
            val base = h.world.getBlockAt(8, 64, 8)
            base.type = Material.PISTON
            base.blockData = (base.blockData as org.bukkit.block.data.Directional).also { it.facing = east }
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonExtendEvent(base, emptyList(), east)
                .also { it.isCancelled = true })
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(base, emptyList(), east)
                .also { it.isCancelled = true })
            changed shouldBe emptySet()
            cache()["dirty_chunks"] shouldBe 0

            repeat(100) { h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonExtendEvent(base, emptyList(), east)) }
            changed shouldBe setOf(CivPosition(8, 64, 8), CivPosition(9, 64, 8))
            cache()["dirty_chunks"] shouldBe 1
            cache()["chunk_captures"] shouldBe captured
            base.getRelative(east).type = Material.PISTON_HEAD
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured
            cache()["deferred_native_recaptures"] shouldBe 1L

            changed.clear()
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(base, emptyList(), east))
            changed shouldBe setOf(CivPosition(8, 64, 8), CivPosition(9, 64, 8))
            base.getRelative(east).type = Material.AIR
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured
            cache()["deferred_native_recaptures"] shouldBe 1L

            changed.clear()
            val edge = h.world.getBlockAt(14, 64, 8)
            edge.type = Material.STICKY_PISTON
            edge.blockData = (edge.blockData as org.bukkit.block.data.Directional).also { it.facing = east }
            val moved = h.world.getBlockAt(16, 64, 8).also { it.type = Material.STONE }
            // Paper reports movement (west) when retracting a block, but facing (east) for an empty retract.
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(edge, listOf(moved), east.oppositeFace))
            changed shouldBe setOf(CivPosition(14, 64, 8), CivPosition(15, 64, 8), CivPosition(16, 64, 8))
        }
    }

    test("compact section snapshots keep negative coordinates and detached contents after native edits") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val main = Thread.currentThread()
            val world = object : CivsWorksiteWorldFixture("negative-snapshot") {
                override fun getBlockAt(x: Int, y: Int, z: Int): org.mockbukkit.mockbukkit.block.BlockMock {
                    check(Thread.currentThread() === main) { "Worker read live terrain" }
                    return super.getBlockAt(x, y, z)
                }
            }.also(paper.server::addWorld)
            world.loadChunk(-2, -1)
            val point = CivPosition(-17, 64, -1)
            world.getBlockAt(point.x, point.y, point.z).type = Material.COBBLESTONE
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("DetachedSnapshot")))
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            val chunk = requireNotNull(snapshots.extracted(world, CitizenSnapshotKey(-2, -1), 0))
            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
            val worker = Executors.newSingleThreadExecutor()
            try {
                CompletableFuture.supplyAsync({
                    chunk.data(point).material shouldBe Material.COBBLESTONE
                    chunk.data(CivPosition(-18, 63, -2)).material shouldBe Material.STONE
                    chunk.states(61, 67).keys.map { it.material }.toSet() shouldBe
                        setOf(Material.STONE, Material.AIR, Material.COBBLESTONE)
                }, worker).get(20, TimeUnit.SECONDS)
            } finally { worker.shutdownNow(); snapshots.clear(); tasks.close() }
        }
    }

    test("one hundred block changes coalesce into one delayed batch including the opposite chunk edge") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-dirty-batch").also(paper.server::addWorld)
            val keys = listOf(CitizenSnapshotKey(-1, 0), CitizenSnapshotKey(0, 0), CitizenSnapshotKey(1, 0))
            keys.forEach { world.loadChunk(it.x, it.z) }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotBatch")))
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            try {
                val captured = keys.associateWith { requireNotNull(snapshots.extracted(world, it, 0)) }
                val old = captured.mapValues { it.value.revision }
                val at = CivPosition(-1, 64, 5)
                world.getBlockAt(at.x, at.y, at.z).type = Material.STONE
                repeat(100) { snapshots.changed(world.uid, at) }
                snapshots.current(world.uid, old) shouldBe false
                snapshots.debug()["dirty_chunks"] shouldBe 2
                snapshots.debug()["chunk_captures"] shouldBe 3L
                tasks.trackedTaskCount() shouldBe 1
                shouldThrow<CitizenSnapshotPending> { snapshots.extracted(world, keys.first(), 0) }
                paper.performTicks(1)
                snapshots.debug()["chunk_captures"] shouldBe 3L
                paper.performTicks(1)
                snapshots.debug()["chunk_captures"] shouldBe 3L
                snapshots.debug()["deferred_native_recaptures"] shouldBe 2L
                snapshots.debug()["refresh_batches"] shouldBe 1L
                snapshots.debug()["dirty_chunks"] shouldBe 0
                snapshots.current(world.uid, old) shouldBe false
                val fresh = requireNotNull(snapshots.extracted(world, keys.first(), 2))
                fresh.data(at).material shouldBe Material.STONE
                captured.getValue(keys.first()).data(at).material shouldBe Material.AIR
                snapshots.extracted(world, keys.last(), 2)?.states(64, 79) shouldBeSameInstanceAs captured.getValue(keys.last()).states(64, 79)
                (snapshots.extracted(world, keys[1], 2) === captured.getValue(keys[1])) shouldBe false
                snapshots.debug()["chunk_captures"] shouldBe 5L
                // Re-loading an evicted generation must never make a retired worker snapshot current again.
                snapshots.removeChunk(world.uid, -1, 0)
                snapshots.extracted(world, keys.first(), 3)
                snapshots.current(world.uid, mapOf(keys.first() to old.getValue(keys.first()))) shouldBe false
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("palette shapes are reused while contextual collision boxes remain exact per position") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-context-shapes").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotShapes")))
            val owner = Thread.currentThread()
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks,
                contextFree = { it.material != Material.SNOW }, shape = { _, _, p ->
                    check(Thread.currentThread() === owner)
                    listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, if (p.x == 2) .25 else .75, 1.0))
                })
            try {
                val stone = world.getBlockAt(0, 63, 0).blockData
                snapshots.state(world, stone, CivPosition(0, 63, 0)).shape.boxes.single().maxY shouldBe .75
                snapshots.state(world, stone, CivPosition(1, 63, 0)).shape.boxes.single().maxY shouldBe .75
                snapshots.debug()["palette_shape_reads"] shouldBe 1L
                for (x in 2..3) world.getBlockAt(x, 64, 0).type = Material.SNOW
                val data = world.getBlockAt(2, 64, 0).blockData
                snapshots.state(world, data, CivPosition(2, 64, 0)).dynamic shouldBe true
                snapshots.dynamic(world, data, CivPosition(2, 64, 0)).single().maxY shouldBe .25
                snapshots.dynamic(world, data, CivPosition(3, 64, 0)).single().maxY shouldBe .75
                snapshots.debug()["dynamic_shape_reads"] shouldBe 2L
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("LRU eviction retains active generations while TTL expiry and lifecycle retirement invalidate them") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-lru").also(paper.server::addWorld)
            world.loadChunk(0, 0); world.loadChunk(1, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotLru")))
            val snapshots = CivsNavigationSnapshots(8192, 5, tasks, contextFree = { true })
            try {
                val a = CitizenSnapshotKey(0, 0)
                val b = CitizenSnapshotKey(1, 0)
                val first = requireNotNull(snapshots.extracted(world, a, 0))
                snapshots.extracted(world, a, 0)?.states(64, 79) shouldBeSameInstanceAs first.states(64, 79)
                snapshots.extracted(world, b, 0)
                snapshots.debug()["chunks"] shouldBe 1
                snapshots.extracted(world, a, 1)?.revision shouldBe first.revision
                snapshots.current(world.uid, mapOf(a to first.revision)) shouldBe true
                snapshots.extracted(world, a, 6)
                snapshots.current(world.uid, mapOf(a to first.revision)) shouldBe false
                // A native edit after eviction marks the next capture dirty.
                world.getBlockAt(24, 64, 8).type = Material.STONE
                snapshots.changed(world.uid, CivPosition(24, 64, 8))
                paper.performTicks(2)
                requireNotNull(snapshots.extracted(world, b, 7))
                val counts = snapshots.debug()
                counts["chunk_cache_hits"] shouldBe 1L
                counts["chunk_captures"] shouldBe 5L
                counts["chunk_captures_cold"] shouldBe 2L
                counts["chunk_captures_dirty"] shouldBe 1L
                counts["chunk_captures_lru"] shouldBe 1L
                counts["chunk_captures_ttl"] shouldBe 1L

                val workers = ArrayDeque<Runnable>()
                val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) }, snapshotCache = snapshots)
                val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
                val route = owner.request(world, Location(world, 2.5, 64.0, 2.5), Location(world, 8.5, 64.0, 2.5),
                    { it.y == 64 }, policy.stepAllowed, { 1.0 }, null, policy)
                workers.removeFirst().run(); owner.pulse()
                route.preparationPhase shouldBe "snapshot-processing"
                owner.replan(route, Location(world, 2.5, 64.0, 2.5),
                    listOf(BoundingBox(4.0, 64.0, 4.0, 4.6, 65.8, 4.6)))
                snapshots.debug()["dirty_chunks"] shouldBe 0 // A resident body is not a native block edit.
                owner.clear()
                while (workers.isNotEmpty()) workers.removeFirst().run()
                paper.performTicks(3)
                owner.debug()["jobs"] shouldBe 0
                route.preparationPhase shouldBe "cancelled"
                snapshots.debug()["chunks"] shouldBe 0
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("a new wall stops the native mover and replans from its actual position") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-stale-route").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotRace")))
            val workers = ArrayDeque<Runnable>()
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) }, snapshotCache = snapshots)
            val allowed: (CivPosition) -> Boolean = { it.x in 0..20 && it.y == 64 && it.z == 0 }
            val policy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true })
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 20.5, 64.0, .5),
                allowed, policy.stepAllowed, { 1.0 }, null, policy)
            try {
                var tick = 0
                while (owner.debug()["used_snapshot_sections"] == 0 && tick++ < 100) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                route.searching shouldBe true
                // The origin section has already been consumed by this job. Its route must still stop at the new wall.
                for (y in 64..66) {
                    world.getBlockAt(8, y, 0).type = Material.STONE
                    owner.changed(world.uid, CivPosition(8, y, 0))
                }
                owner.pulse()
                owner.debug()["stale_snapshot_restarts"] shouldBe 0L
                while (route.searching && tick++ < 250) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                withClue("${owner.debug()} phase=${route.preparationPhase}") {
                    route.searching shouldBe false
                    route.failure shouldBe null
                    route.continuation shouldBe false
                    route.endpoint shouldBe CitizenPoint(20.5, 64.0, .5)
                }
                var actual = Location(world, .5, 64.0, .5, -90f, 0f)
                repeat(100) {
                    route.move(actual, 1.0) { true }?.let { actual = it }
                    (actual.x + .299 <= 8.000001) shouldBe true
                }
                (actual.x > 6.0) shouldBe true
                (route.failure in setOf("path-changed", "movement-blocked")) shouldBe true
                val replan = owner.replan(route, actual)
                while (replan.searching && tick++ < 400) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                replan.failure shouldBe "no-path"
                snapshots.changed(world.uid, CivPosition(0, 64, 0))
                owner.clear()
                paper.performTicks(3)
                snapshots.debug()["chunks"] shouldBe 0
                snapshots.debug()["dirty_chunks"] shouldBe 0
            } finally { owner.clear(); tasks.close() }
        }
    }
})

private fun ResidentRuntime.warmSnapshotFromWorkRoute() {
    val farm = assignFarm()
    work = CivWorldWork(workerId, 0, farm.id, CivPosition(24, 64, 0), emptyList(),
        durationMillis = 4_000, status = "planting")
    navigationNow = 2_000
    owner.pulse(2_000)
    phase() shouldBe CitizenPhase.TO_WORK
    repeat(100) { navigate() }
}

private fun flatSnapshotTerrain(x: Int, z: Int, cells: Map<CivPosition, BlockData> = emptyMap()): CitizenSnapshotTerrain {
    val terrain = CitizenSnapshotTerrain()
    val snapshot = CivsNavigationChunkSnapshotFixture(x, z, -64, 320, "capture-reuse", 0,
        org.bukkit.Bukkit.createBlockData(Material.STONE), cells)
    val chunk = CitizenSnapshotChunk(snapshot, -64, 320)
    terrain.chunks[CitizenSnapshotKey(x, z)] = chunk
    chunk.states(61, 67).forEach { (data, _) ->
        terrain.states[data] = CitizenSnapshotState(CitizenTerrainShape(false, false, false, false,
            if (data.material.isAir) emptyList() else listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))), data.material == Material.SNOW)
    }
    return terrain
}

private fun completeSnapshotCapture(terrain: CitizenSnapshotTerrain, probes: List<CitizenSearchProbe>,
    cost: Double): List<CitizenSearchCapture> {
    val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
    val capture = CitizenSnapshotCapture(probes, terrain, policy, -64, 320, 32768, probes.size, false)
    val request = capture.advance(CitizenSnapshotInput())
    request.chunks shouldBe emptySet()
    request.states shouldBe emptyMap()
    request.dynamics shouldBe emptyMap()
    return requireNotNull(capture.advance(CitizenSnapshotInput(costs = request.costs.associateWith { cost })).captures)
}

internal fun sharedRouteRuntime(settings: CivsNavigationSettings = CivsNavigationSettings(), block: (SharedRouteRuntime) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        SharedRouteRuntime(paper, settings).use(block)
    }
}

internal class SharedRouteRuntime(private val paper: MockBukkitTestRuntime, settings: CivsNavigationSettings) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("shared-route-cache").also(paper.server::addWorld)
    val paths = CivsPaths()
    val city = Settlement(UUID(0, 1), UUID(0, 2), "Shared", world.uid, CivPosition(0, 64, 0),
        96, "fixture", Stock(), emptyList(), emptyList())
    val target = CitizenPoint(8.5, 64.0, .5)
    var allowedY = 64..64
    var allowedZ = 0..0
    private val plugin = paper.createSimplePlugin("SharedRouteCache")
    val footTraffic = CivsFootTraffic(plugin) { listOf(city) }
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val work = BudgetedTaskQueue(tasks, 10000, nanoTime = { 0L })
    private val workers = ArrayDeque<Runnable>()
    val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) }, settings = settings,
        snapshotContextFree = { true })

    init { for (x in -1..1) for (z in -1..1) world.loadChunk(x, z) }

    fun key(allowed: Set<UUID> = emptySet()) = CitizenRouteReuseKey.of(paths.begin(city), allowed)

    fun request(from: CitizenPoint = CitizenPoint(.5, 64.0, .5), target: CitizenPoint = this.target,
        reuseKey: CitizenRouteReuseKey? = key(), corridor: CitizenSearchCorridor? = null,
        permitted: () -> Boolean = { true }, allowedPoint: (CivPosition) -> Boolean = { true },
        world: org.bukkit.World = this.world, surfaceSpeed: (Block) -> Double = { 1.0 }, cityId: UUID? = city.id,
        roadCorridor: CitizenRoadLookup? = null): CitizenPacketRoute {
        val trip = paths.begin(city)
        fun allowed(p: CivPosition) = permitted() && allowedPoint(p) && p.x in 0..8 && p.y in allowedY && p.z in allowedZ
        val supportCost = (surfaceSpeed as? CivsFootTraffic)?.detachedSupportCost(trip.weights, Tag.LOGS.values.toSet(),
            Tag.LEAVES.values.toSet())
        val policy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> permitted() }, reuseKey, supportCost, cityId, roadCorridor)
        return owner.request(world, Location(world, from.x, from.y, from.z), Location(world, target.x, target.y, target.z),
            ::allowed, policy.stepAllowed, { trip.cost(it, surfaceSpeed) }, corridor, policy)
    }

    fun runNextWorker() = workers.removeFirst().run()

    fun stepWorker() {
        workers.removeFirstOrNull()?.run()
        paper.performTicks(1); owner.pulse()
    }

    fun finish(route: CitizenPacketRoute) {
        prepareUntil { !route.searching }
    }

    fun prepareUntil(done: () -> Boolean) {
        repeat(300) {
            if (done()) return
            while (workers.isNotEmpty()) workers.removeFirst().run()
            paper.performTicks(1)
            owner.pulse()
        }
        error("Shared route did not reach the requested state: ${owner.debug()}")
    }

    fun walk(route: CitizenPacketRoute, from: CitizenPoint = CitizenPoint(.5, 64.0, .5),
        target: CitizenPoint = this.target, requireFinished: Boolean = true): CitizenPoint {
        var at = Location(world, from.x, from.y, from.z)
        for (tick in 0 until 2000) {
            if (route.finished || route.failure != null) break
            val before = CitizenPoint(at.x, at.y, at.z)
            route.move(at, 1.0) { true }?.let { next ->
                at = next
                route.observeAcceptedMove(before, CitizenPoint(at.x, at.y, at.z))
            }
        }
        val actual = CitizenPoint(at.x, at.y, at.z)
        if (requireFinished) withClue("actual=$actual target=$target failure=${route.failure}") {
            route.finished shouldBe true
            route.failure shouldBe null
            actual.distanceSquared(target) shouldBe 0.0
        }
        return actual
    }

    fun walkUntil(route: CitizenPacketRoute, done: (CitizenPoint) -> Boolean): CitizenPoint {
        var at = Location(world, .5, 64.0, .5)
        for (tick in 0 until 2000) {
            val from = CitizenPoint(at.x, at.y, at.z)
            if (done(from)) return from
            check(!route.finished && route.failure == null)
            route.move(at, 1.0) { true }?.let { next ->
                at = next
                route.observeAcceptedMove(from, CitizenPoint(at.x, at.y, at.z))
            }
        }
        error("Arrival tolerance was not reached")
    }

    @Suppress("UNCHECKED_CAST")
    fun cache() = owner.debug()["shared_routes"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    fun suffixes() = owner.debug()["shared_route_suffixes"] as Map<String, Any>

    fun diagnostics(cityId: UUID? = null) = owner.debugSharedRoutes(cityId)

    fun routeCity(cityId: UUID = city.id) = (diagnostics(cityId)["cities"] as List<*>).single() as Map<*, *>

    fun routeCounters(cityId: UUID = city.id) = routeCity(cityId)["counters"] as Map<*, *>

    fun supportCostReads() = (owner.debug()["snapshot_cache"] as Map<*, *>)["support_cost_reads"] as Long

    fun enableTrails(api: TrailsFootTrafficService) {
        val trails = paper.createSimplePlugin("Trails")
        paper.server.servicesManager.register(TrailsFootTrafficService::class.java, api, trails, ServicePriority.Normal)
    }

    fun otherWorld(): org.bukkit.World = CivsWorksiteWorldFixture("other-shared-world").also { other ->
        paper.server.addWorld(other)
        for (x in -1..1) for (z in -1..1) other.loadChunk(x, z)
    }

    fun waitTicks(count: Int) { repeat(count) { paper.performTicks(1) } }

    override fun close() { owner.clear(); work.close(); tasks.close() }
}

private fun CitizenPoint.location(world: org.bukkit.World) = Location(world, x, y, z)

/** Mirrors the runtime handoff: native copy on owner, extraction on worker, owner publication of compact values only. */
private fun CivsNavigationSnapshots.extracted(world: org.bukkit.World, key: CitizenSnapshotKey, tick: Long,
    wanted: Set<Int> = setOf(3, 4)): CitizenSnapshotChunk? {
    val handoff = capture(world, key, tick, wanted) ?: return null
    CompletableFuture.runAsync {
        try { handoff.prepare() } finally { handoff.releaseSource() }
    }.get(20, TimeUnit.SECONDS)
    publish(world.uid, handoff.payloads())
    return handoff
}
