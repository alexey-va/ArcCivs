package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsVillageSiteScoringTest : FunSpec({
    test("interlaced tiers preserve the original forty eight anchors and fill both radial and angular gaps") {
        val reference = villageReference(UUID(0, 1)).copy(id = UUID(0, 123), center = CivPosition(-1883, 67, -2760))
        val candidates = CivsVillageSiteScoring.candidates(reference, "Auto-")
        val original = candidates.take(48).joinToString(";") { "${it.x},${it.y},${it.z}" }
        MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } shouldBe "dd497670ee266802251ee061424277b9f8d69df22c1773acdfcf70842ad55ffb"
        candidates.chunked(CivsVillageSiteScoring.CANDIDATE_BATCH).size shouldBe 4
        candidates.distinct().size shouldBe 192
        fun distance(p: CivPosition): Double = kotlin.math.hypot((p.x - reference.center.x).toDouble(), (p.z - reference.center.z).toDouble())
        (kotlin.math.abs(distance(candidates[48]) - distance(candidates[0])) < 24) shouldBe true
        (distance(candidates[96]) - distance(candidates[0]) in 232.0..280.0) shouldBe true
        CivsVillageSiteScoring.samples(reference.center).let { samples ->
            samples.size shouldBe 25
            samples.minOf { it.x } shouldBe reference.center.x - 88
            samples.maxOf { it.z } shouldBe reference.center.z + 88
        }
    }

    test("wet or hilly provincial fringe cannot veto a dry construction core but the same core limits stay strict") {
        val center = CivPosition(0, 63, 0)
        val flat = villageSurface(center, 127)
        for (fringe in listOf("wet", "hill")) {
            val surface = flat.copy(columns = flat.columns.mapValues { (key, column) ->
                val outer = kotlin.math.abs((key shr 32).toInt()) > 88 || kotlin.math.abs(key.toInt()) > 88
                if (!outer) column else column.copy(natural = fringe != "wet", groundY = 95, topY = 95)
            })
            (CivsVillageSiteScoring.score(surface).rejection(full = true) != null) shouldBe true
            CivsVillageSiteScoring.coreScore(surface, center).let { score ->
                score.columns shouldBe 177 * 177
                score.rejection(full = true) shouldBe null
            }
        }
        val split = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(natural = (key shr 32).toInt() != 0)
        })
        CivsVillageSiteScoring.coreScore(split, center).rejection(full = false) shouldBe "village-disconnected"
        val wet = flat.copy(columns = flat.columns.mapValues { (_, column) -> column.copy(natural = false) })
        CivsVillageSiteScoring.coreScore(wet, center).rejection(full = false) shouldBe "village-wet-or-obstructed"
        val threshold = CivVillageTerrainScore(0, 100, 80, 60, 8, 12, 63)
        threshold.rejection(full = true) shouldBe null
        threshold.copy(dryColumns = 79).rejection(full = true) shouldBe "village-wet-or-obstructed"
        threshold.copy(connectedColumns = 59).rejection(full = true) shouldBe "village-disconnected"
        threshold.copy(relief = 13).rejection(full = true) shouldBe "village-steep"
    }

    test("usable timber belongs to the first rotated forester work zone and excludes future buildings and roads") {
        val city = villageReference(UUID.randomUUID())
        val source = "civs/minecolonies/nordic/fundamentals/lumberjack1"
        val forester = villageBlueprint(source, UUID.randomUUID(), city.worldId, CivPosition(20, 63, 0), BuildingType.FORESTER).building
        val plan = villageLayout(city).copy(plots = listOf(CivSettlementPlot(forester, forester.bounds)))
        val bare = villageSurface(city.center, 127).let { surface -> surface.copy(
            columns = surface.columns.mapValues { (_, column) -> column.copy(topY = column.groundY) }) }
        fun trees(points: List<CivPosition>): CivTerrainSurface = bare.copy(columns = LinkedHashMap(bare.columns).apply {
            points.forEach { point -> val key = civsTerrainKey(point.x, point.z); this[key] = getValue(key).copy(topY = 68) }
        })
        val remote = (0..7).map { CivPosition(-60 + it, 63, 0) }
        CivsVillageSiteScoring.score(trees(remote)).treeColumns shouldBe 8
        CivsVillageSiteScoring.usableTreeColumns(city, plan, trees(remote)) shouldBe 0
        val usable = (0..7).map { CivPosition(30 + it, 63, 5) }
        CivsVillageSiteScoring.usableTreeColumns(city, plan, trees(usable)) shouldBe 8
        val house = villageBlueprint("house1", UUID.randomUUID(), city.worldId, CivPosition(33, 63, 5), BuildingType.HOUSE).building
        val reserved = plan.copy(plots = plan.plots + CivSettlementPlot(house,
            CivBox(CivPosition(30, 63, 4), CivPosition(37, 68, 6))))
        CivsVillageSiteScoring.usableTreeColumns(city, reserved, trees(usable)) shouldBe 0
        val road = plan.copy(routes = mapOf(forester.id.toString() to usable.map { it.copy(y = 64) }))
        CivsVillageSiteScoring.usableTreeColumns(city, road, trees(usable)) shouldBe 0
        val perimeter = CivBox(CivPosition(30, 63, 5), CivPosition(37, 72, 5))
        CivsVillageSiteScoring.usableTreeColumns(city, plan.copy(walls = listOf(perimeter)), trees(usable)) shouldBe 0
        CivsVillageSiteScoring.usableTreeColumns(city, plan.copy(gates = listOf(perimeter)), trees(usable)) shouldBe 0
        val edge = (0..7).map { CivPosition(30 + it, 63, -25) }
        CivsVillageSiteScoring.usableTreeColumns(city, plan, trees(edge)) shouldBe 8
        val rotated = forester.copy(rotation = 90)
        val turned = plan.copy(plots = listOf(CivSettlementPlot(rotated, rotated.bounds)))
        CivsVillageSiteScoring.usableTreeColumns(city, turned, trees(edge)) shouldBe 0
        val rotatedTrees = (0..7).map { CivsWorksiteLayouts.point(rotated, CivPosition(10 + it, 0, -25)) }
        CivsVillageSiteScoring.usableTreeColumns(city, turned, trees(rotatedTrees)) shouldBe 8
        val unavailable = trees(usable).let { surface -> surface.copy(columns = surface.columns.mapValues { (_, column) ->
            column.copy(loaded = false)
        }) }
        CivsVillageSiteScoring.usableTreeColumns(city, plan, unavailable) shouldBe 0
    }

    test("coarse requests reserve complete proof areas for every missing canonical town at the exact budget boundary") {
        val cap = CivsVillageSites.MAXIMUM_CHUNK_REQUESTS
        val boundary = cap - 25 - 5 * 361
        CivsVillageSiteScoring.probeFits(boundary, 25, 5, 361) shouldBe true
        CivsVillageSiteScoring.probeFits(boundary + 1, 25, 5, 361) shouldBe false
        CivsVillageSiteScoring.probeFits(boundary + 361, 25, 4, 361) shouldBe true
    }

    test("candidate IDs and bounded terrain centres repeat across negative world coordinates") {
        val reference = villageReference(UUID.randomUUID()).copy(center = CivPosition(-1883, 67, -2760))
        val candidates = CivsVillageSiteScoring.candidates(reference, "Auto-")
        candidates shouldBe CivsVillageSiteScoring.candidates(reference, "Auto-")
        candidates.distinct().size shouldBe CivsVillageSiteScoring.MAXIMUM_CANDIDATES
        candidates.all { point ->
            val x = point.x.toLong() - reference.center.x
            val z = point.z.toLong() - reference.center.z
            x * x + z * z in (700L * 700)..(4096L * 4096)
        } shouldBe true
        val ids = (1..5).map { CivsVillageSiteScoring.id(reference.id, "Auto-", it) }
        ids.distinct().size shouldBe 5
        ids shouldBe (1..5).map { CivsVillageSiteScoring.id(reference.id, "Auto-", it) }
        (ids.first() != CivsVillageSiteScoring.id(reference.id, "Other-", 1)) shouldBe true
    }

    test("ranking prefers connected flat dry land with trees and rejects water cliffs and missing chunks") {
        val flat = villageSurface(CivPosition(0, 63, 0), 12)
        val eligible = CivsVillageSiteScoring.score(flat)
        eligible.rejection(full = true) shouldBe null
        eligible.connectedColumns shouldBe flat.columns.size
        val wet = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(natural = key.toInt() > 0)
        })
        CivsVillageSiteScoring.score(wet).rejection(full = true) shouldBe "village-wet-or-obstructed"
        val cliff = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(groundY = if ((key shr 32).toInt() > 0) 90 else 63)
        })
        (eligible.score > CivsVillageSiteScoring.score(cliff).score) shouldBe true
        (CivsVillageSiteScoring.score(cliff).rejection(full = true) != null) shouldBe true
        val unavailable = flat.copy(columns = flat.columns.mapValues { (_, column) -> column.copy(loaded = false) })
        CivsVillageSiteScoring.score(unavailable).dryColumns shouldBe 0
        CivsVillageSiteScoring.score(unavailable).rejection(full = true) shouldBe "village-wet-or-obstructed"
        val treeless = flat.copy(columns = flat.columns.mapValues { (_, column) -> column.copy(topY = column.groundY) })
        CivsVillageSiteScoring.score(treeless).rejection(full = true) shouldBe "village-no-trees"
    }

    test("a narrow protected cut splits an otherwise dry village and missing profile plots are refused") {
        val flat = villageSurface(CivPosition(0, 63, 0), 12)
        val split = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(natural = (key shr 32).toInt() != 0)
        })
        CivsVillageSiteScoring.score(split).rejection(full = true) shouldBe "village-disconnected"
        val city = villageReference(UUID.randomUUID())
        val full = villageLayout(city)
        CivsVillageSiteScoring.completeProfile(full) shouldBe true
        CivsVillageSiteScoring.completeProfile(full.copy(plots = full.plots.filter {
            it.building.type != BuildingType.FORESTER
        })) shouldBe false
    }
})

class CivsVillageSiteRejectionDetailsTest : FunSpec({
    test("validated type stage block and numeric counters survive while arbitrary strings and sample payloads are excluded") {
        val values = linkedMapOf("type" to "HOUSE", "stage" to "route-search", "block" to "OAK_LOG",
            "frontage_candidates" to "64", "frontage_options" to "0", "infill_candidates" to "32", "infill_options" to "4",
            "frontage_filter_autonomy-terrain" to "12", "infill_draft_autonomy-no-path" to "4", "route-state-budget" to "131072",
            "x" to "-1900", "y" to "69", "z" to "-3593", "samples" to "123", "draft_samples" to "456",
            "world_name" to "arc_civs_test", "note" to "unbounded detail", "invalid key" to "9",
            "decimal" to "1.5", "overflow" to "9223372036854775808", "a".repeat(65) to "7")
        val details = CivsVillageSites.rejectionDetails(values)
        details shouldBe mapOf("type" to "HOUSE", "stage" to "route-search", "block" to "OAK_LOG", "frontage_candidates" to "64",
            "frontage_options" to "0", "infill_candidates" to "32", "infill_options" to "4",
            "frontage_filter_autonomy-terrain" to "12", "infill_draft_autonomy-no-path" to "4", "route-state-budget" to "131072",
            "x" to "-1900", "y" to "69", "z" to "-3593")
        values["frontage_options"] = "999"
        values["stage"] = "changed"
        details["frontage_options"] shouldBe "0"
        details["stage"] shouldBe "route-search"
        shouldThrow<UnsupportedOperationException> { (details as MutableMap<String, String>)["type"] = "FARM" }
    }

    test("detail cap preserves validated headers and all four stage totals and bounds every retained field") {
        val values = linkedMapOf("type" to "FARM", "stage" to "fortification-reservation", "block" to "STONE",
            "frontage_candidates" to "0", "frontage_options" to "0", "infill_candidates" to "0", "infill_options" to "0",
            "route-expanded" to "131072")
        repeat(40) { values["reason_$it"] = it.toString() }
        val details = CivsVillageSites.rejectionDetails(values)
        details.size shouldBe 24
        listOf("type", "stage", "block", "frontage_candidates", "frontage_options", "infill_candidates", "infill_options", "route-expanded")
            .all(details::containsKey) shouldBe true
        details.entries.all { it.key.length <= 64 && it.value.length <= 64 } shouldBe true
        CivsVillageSites.rejectionDetails(values.entries.reversed().associate { it.key to it.value }).keys.toList() shouldBe details.keys.toList()
        CivsVillageSites.rejectionDetails(mapOf("type" to "unknown building", "stage" to "x".repeat(65), "block" to "123",
            "world" to "456", "world_name" to "789",
            "samples" to "123", "draft_samples" to "456", "zeros" to "0".repeat(256))) shouldBe mapOf("zeros" to "0")
    }

    test("block details accept only exact Material enum names and never numeric or arbitrary payloads") {
        for (block in listOf("123", "oak_log", "minecraft:oak_log", "OAK_LOG[axis=y]", "UNKNOWN_MATERIAL", "x".repeat(256))) {
            CivsVillageSites.rejectionDetails(mapOf("block" to block, "x" to "262", "samples" to "arbitrary payload")) shouldBe
                mapOf("x" to "262")
        }
    }
})

class CivsVillageSitesTest : FunSpec({
    test("foundation rejectAt shape retains validated block and coordinates without world identifiers or accepting a town") {
        villageScenario { s ->
            val values = mutableMapOf("block" to "OAK_LOG", "x" to "262", "y" to "69", "z" to "-3593",
                "world" to s.reference.worldId.toString(), "world_name" to "arc_civs_test", "samples" to "123")
            s.foundationFailure = CivsRejection("autonomy-terrain", values)
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { (s.subject.status()["rejected_sites"] as? List<*>)?.filterIsInstance<CivVillageSiteRejection>()
                ?.any { it.phase == "town-hall-foundation" } == true }
            val evidence = (s.subject.status()["rejected_sites"] as List<*>).filterIsInstance<CivVillageSiteRejection>().last()
            evidence.reason shouldBe "autonomy-terrain"
            evidence.details shouldBe mapOf("block" to "OAK_LOG", "x" to "262", "y" to "69", "z" to "-3593")
            values["x"] = "999"
            values["block"] = "AIR"
            evidence.details["x"] shouldBe "262"
            evidence.details["block"] shouldBe "OAK_LOG"
            s.subject.cancel("foundation-details-test")
            s.until { result.isDone }
            result.join().rejectedSites.last() shouldBe evidence
            result.join().created shouldBe emptyList()
            s.admissions shouldBe emptyList()
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("a complete layout with only remote provincial trees is refused with its detached usable timber evidence") {
        villageScenario { s ->
            s.fullSurface = { city ->
                val surface = villageSurface(city.center, 127)
                surface.copy(columns = surface.columns.mapValues { (key, column) ->
                    val timber = (key shr 32).toInt() in city.center.x + 112..city.center.x + 119 &&
                        key.toInt() == city.center.z + 120
                    column.copy(topY = if (timber) 68 else column.groundY)
                })
            }
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { (s.subject.status()["rejected_sites"] as? List<*>)?.filterIsInstance<CivVillageSiteRejection>()
                ?.any { it.usableTreeColumns == 0 } == true }
            val evidence = (s.subject.status()["rejected_sites"] as List<*>).filterIsInstance<CivVillageSiteRejection>().last()
            evidence.phase shouldBe "complete-layout-proof"
            evidence.reason shouldBe "village-no-trees"
            evidence.provinceTreeColumns shouldBe 8
            evidence.terrain?.columns shouldBe 177 * 177
            evidence.point shouldBe s.fullCities.single().center
            s.admissions shouldBe emptyList()
            s.subject.cancel("timber-evidence-test")
            s.until { result.isDone }
            result.join().rejectedSites.last() shouldBe evidence
            result.join().created shouldBe emptyList()
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("a later interlaced tier finds five distinct complete towns and replay creates no duplicate grants") {
        villageScenario { s ->
            val flat = s.coarseSurface
            s.coarseSurface = { city, points -> flat(city, points).let { surface ->
                if (s.coarseCities.size > 48) surface else surface.copy(columns = surface.columns.mapValues { (_, column) ->
                    column.copy(natural = false)
                })
            } }
            val result = s.subject.search(s.reference, 5, "Auto-")
            s.until { result.isDone }
            val first = result.join()
            first.complete shouldBe true
            first.created.size shouldBe 5
            first.created.map { it.style }.toSet().size shouldBe 5
            first.created.map { it.city }.toSet() shouldBe (1..5).mapTo(hashSetOf()) {
                CivsVillageSiteScoring.id(s.reference.id, "Auto-", it)
            }
            s.coarseCities.size shouldBe 96
            s.fullCities.size shouldBe 5
            s.admissions.size shouldBe 5
            s.registry.activeLeaseCount shouldBe 0
            (s.peakRequests <= CivsVillageSites.MAXIMUM_CHUNK_REQUESTS) shouldBe true
            (s.peakPending <= 2) shouldBe true
            val canonical = s.cities.toList()
            val loads = s.loads.size
            val tickets = s.added.size
            val replay = s.subject.search(s.reference, 5, "Auto-")
            s.until { replay.isDone }
            replay.join().complete shouldBe true
            replay.join().created.all { it.existing } shouldBe true
            replay.join().created.map { it.city }.toSet() shouldBe first.created.map { it.city }.toSet()
            s.cities shouldBe canonical
            s.loads.size shouldBe loads
            s.added.size shouldBe tickets
            s.admissions.size shouldBe 5
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("exhausted full proofs continue once into the next tier while keeping the final proof inside the request budget") {
        villageScenario { s ->
            var proofs = 0
            s.layout = { city ->
                if (++proofs <= 16) throw CivsRejection("autonomy-no-site")
                villageLayout(city)
            }
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { result.isDone }
            result.join().complete shouldBe true
            result.join().created.size shouldBe 1
            result.join().rejected["autonomy-no-site"] shouldBe 16
            proofs shouldBe 17
            s.coarseCities.size shouldBe 82
            s.fullCities.size shouldBe 17
            s.peakRequests shouldBe 8_187 // 82*25 coarse requests + 17*361 complete proof areas.
            s.admissions.size shouldBe 1
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("province timber rejection stays cheap and reserved proof budget returns one durable town for a safe replay") {
        villageScenario { s ->
            val forested = s.fullSurface
            var proofs = 0
            s.layout = { city -> proofs++; villageLayout(city) }
            s.fullSurface = { city -> forested(city).let { surface ->
                if (s.admissions.isEmpty()) surface else surface.copy(columns = surface.columns.mapValues { (_, column) ->
                    column.copy(topY = column.groundY)
                })
            } }
            val result = s.subject.search(s.reference, 5, "Auto-")
            s.until { result.isDone }
            result.join().complete shouldBe false
            result.join().reason shouldBe "village-chunk-budget"
            result.join().created.size shouldBe 1
            (requireNotNull(result.join().rejected["village-no-trees"]) > 0) shouldBe true
            proofs shouldBe 1 // Treeless provinces never enter the expensive full layout solver.
            val saved = s.cities.last()
            val savedReceipt = result.join().created.single()
            saved.id shouldBe CivsVillageSiteScoring.id(s.reference.id, "Auto-", 1)
            CivsVillageSiteScoring.probeFits(s.peakRequests, 25, 4, 361) shouldBe false
            (s.peakRequests <= CivsVillageSites.MAXIMUM_CHUNK_REQUESTS) shouldBe true
            s.registry.activeLeaseCount shouldBe 0
            s.fullSurface = forested
            val replay = s.subject.search(s.reference, 5, "Auto-")
            s.until { replay.isDone }
            replay.join().complete shouldBe true
            replay.join().created.first { it.city == saved.id }.existing shouldBe true
            replay.join().created.first { it.city == saved.id }.name shouldBe savedReceipt.name
            s.cities.first { it.id == saved.id } shouldBe saved
            s.cities.size shouldBe 6
            s.admissions.size shouldBe 5
            s.cities.drop(1).map { it.id }.toSet().size shouldBe 5
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("cancelling adaptive continuation fences its late native callbacks and releases the previous tier once") {
        villageScenario { s ->
            val flat = s.coarseSurface
            s.coarseSurface = { city, points ->
                if (s.coarseCities.size == 48) s.holdLoads = true
                flat(city, points).let { surface -> surface.copy(columns = surface.columns.mapValues { (_, column) ->
                    column.copy(natural = false)
                }) }
            }
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.coarseCities.size == 48 && s.subject.status()["pending_loads"] == 2 }
            val observations = (s.subject.status()["rejected_sites"] as List<*>).filterIsInstance<CivVillageSiteRejection>()
            observations.size shouldBe 8
            observations.all { it.phase == "coarse-survey" && it.reason == "village-wet-or-obstructed" &&
                it.terrain?.columns == 25 && it.point != null } shouldBe true
            observations.last().point shouldBe s.coarseCities.last().center
            s.subject.cancel("adaptive-cancel")
            s.tick(10)
            s.completeLoads(evictUnticketed = true)
            s.tick(10)
            result.join().reason shouldBe "adaptive-cancel"
            result.join().created shouldBe emptyList()
            result.join().rejectedSites shouldBe observations
            s.admissions shouldBe emptyList()
            s.registry.activeLeaseCount shouldBe 0
            (s.peakPending <= 2) shouldBe true
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    for (change in listOf("removed", "owner", "world", "autonomy", "deletion", "chunk-loading-disabled"))
    test("a committed handoff releases temporary leases and preserves its receipt when the target is $change") {
        villageScenario { s ->
            s.chunksReady = false
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.subject.status()["phase"] == "handing-off-chunks" }
            val saved = s.cities.last()
            val receipt = (s.subject.status()["created"] as List<*>).single()
            if (change == "removed") s.cities.remove(saved) else {
                val changed = when (change) {
                    "owner" -> saved.copy(owner = UUID.randomUUID())
                    "world" -> saved.copy(worldId = UUID.randomUUID())
                    "autonomy" -> saved.copy(autonomy = null)
                    "deletion" -> saved.copy(pendingDeletion = CivDeletion(null))
                    else -> saved.copy(settings = saved.settings!!.copy(chunkLoading = false))
                }
                s.cities[s.cities.indexOf(saved)] = changed
            }
            val canonical = s.cities.toList()
            s.until { result.isDone }
            result.join().reason shouldBe "village-handoff-invalidated"
            result.join().complete shouldBe false
            result.join().created.single() shouldBe receipt
            s.cities shouldBe canonical
            s.admissions.size shouldBe 1
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    for (failure in listOf("not-authorized", "chunk-unavailable"))
    test("an observed $failure during committed chunk handoff returns a partial receipt and releases temporary leases") {
        villageScenario { s ->
            s.chunksReady = false
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.subject.status()["phase"] == "handing-off-chunks" }
            val saved = s.cities.last()
            val receipt = (s.subject.status()["created"] as List<*>).single()
            s.handoffFailure = failure
            s.until { result.isDone }
            result.join().reason shouldBe failure
            result.join().complete shouldBe false
            result.join().created.single() shouldBe receipt
            s.cities.last() shouldBe saved
            s.admissions.size shouldBe 1
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("a committed town keeps temporary leases beyond the search deadline until permanent chunks are ready") {
        villageScenario { s ->
            s.chunksReady = false
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.subject.status()["phase"] == "handing-off-chunks" }
            val saved = s.cities.last()
            val retained = s.registry.activeLeaseCount
            (retained > 0) shouldBe true
            val removed = s.removed.toList()
            s.tick(CivsVillageSites.MAXIMUM_TICKS + 1)
            result.isDone shouldBe false
            s.registry.activeLeaseCount shouldBe retained
            s.removed shouldBe removed
            s.admissions.size shouldBe 1
            s.cities.last() shouldBe saved
            s.chunksReady = true
            s.until { result.isDone }
            result.join().created.single().city shouldBe saved.id
            result.join().complete shouldBe true
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("native load completion owns its ticket before unviewed terrain can unload and keeps it through survey") {
        villageScenario { s ->
            s.holdLoads = true
            s.requireLeasedSurvey = true
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until {
                s.completeLoads(evictUnticketed = true)
                result.isDone
            }
            result.join().complete shouldBe true
            result.join().created.size shouldBe 1
            result.join().rejected["chunks-unloaded"] shouldBe null
            result.join().examined shouldBe CivsVillageSiteScoring.CANDIDATE_BATCH
            s.surveys shouldBe CivsVillageSiteScoring.CANDIDATE_BATCH + 1
            s.admissions.size shouldBe 1
            s.registry.activeLeaseCount shouldBe 0
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    test("a partially completed load cohort cannot admit new work ahead of its late callback") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.tick(30)
            s.loads.size shouldBe 2
            s.completeLoad(0, evictUnticketed = true)
            s.registry.activeLeaseCount shouldBe 1 // The callback owns this before any timer bookkeeping.
            s.tick(1)
            s.loads.size shouldBe 2
            s.subject.status()["pending_loads"] shouldBe 1
            s.completeLoad(1, evictUnticketed = true)
            s.registry.activeLeaseCount shouldBe 2
            s.tick(1)
            s.loads.size shouldBe 3 // One reply plus one new request consumes this pulse's two admissions.
            s.subject.status()["pending_loads"] shouldBe 1
            s.subject.cancel("test-cancel")
            s.tick(3)
            s.completeLoads(evictUnticketed = true)
            result.join().reason shouldBe "test-cancel"
            s.registry.activeLeaseCount shouldBe 0
            s.added.size shouldBe 2
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
        }
    }

    for (stop in listOf("cancel", "close"))
    test("$stop releases a callback-owned lease with an unread reply once and rejects the other stale callback") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.tick(30)
            s.loads.size shouldBe 2
            s.completeLoad(0, evictUnticketed = true)
            s.registry.activeLeaseCount shouldBe 1
            if (stop == "close") s.subject.close() else s.subject.cancel("test-cancel")
            s.tick(3)
            s.completeLoad(1, evictUnticketed = true)
            s.tick(10)
            result.join().reason shouldBe if (stop == "close") "closed" else "test-cancel"
            s.registry.activeLeaseCount shouldBe 0
            s.added.size shouldBe 1
            s.removed shouldBe s.added
            s.admissions shouldBe emptyList()
        }
    }

    test("a callback retention failure releases its successful sibling without admitting a survey") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.tick(30)
            s.completeLoad(0, evictUnticketed = true)
            s.retainFailure = CivsRejection("storage-unavailable")
            s.completeLoad(1, evictUnticketed = true)
            s.registry.activeLeaseCount shouldBe 1
            s.tick(1)
            (s.subject.status()["rejected"] as Map<*, *>)["storage-unavailable"] shouldBe 1
            s.surveys shouldBe 0
            s.subject.cancel("test-cancel")
            s.tick(3)
            result.join().reason shouldBe "test-cancel"
            s.registry.activeLeaseCount shouldBe 0
            s.added.size shouldBe 1
            s.removed shouldBe s.added
            s.admissions shouldBe emptyList()
        }
    }

    test("an injected off-thread completion never acquires a native ticket") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.tick(30)
            val (key, future) = s.loads.first()
            s.world.loadChunk(key.x, key.z)
            CompletableFuture.runAsync { future.complete(mockk<Chunk>()) }.join()
            s.tick(1)
            s.registry.activeLeaseCount shouldBe 0
            s.added shouldBe emptyList()
            s.completeLoad(1, evictUnticketed = true)
            s.tick(1)
            (s.subject.status()["rejected"] as Map<*, *>)["village-search-failed"] shouldBe 1
            s.subject.cancel("test-cancel")
            s.tick(3)
            result.join().reason shouldBe "test-cancel"
            s.registry.activeLeaseCount shouldBe 0
            s.added shouldBe emptyList()
            s.admissions shouldBe emptyList()
        }
    }

    test("explicit preparation has two outstanding loads and cancellation admits no stale lease") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 5, "Auto-")
            s.subject.search(s.reference, 5, "Auto-") shouldBe result
            s.tick(30)
            s.loads.size shouldBe 2
            s.subject.status()["pending_loads"] shouldBe 2
            (s.subject.status()["styles"] as Map<*, *>).values.toSet().size shouldBe 5
            s.subject.cancel("test-cancel")
            s.tick(3)
            result.join().reason shouldBe "test-cancel"
            result.join().created shouldBe emptyList()
            s.loads.forEach { (key, future) ->
                s.world.loadChunk(key.x, key.z)
                future.complete(mockk<Chunk>())
            }
            s.tick(20)
            s.loads.size shouldBe 2
            s.registry.activeLeaseCount shouldBe 0
            s.admissions shouldBe emptyList()
        }
    }

    test("a failed full growth proof creates no town and returns bounded rejection evidence") {
        villageScenario { s ->
            val values = mutableMapOf("type" to "HOUSE", "frontage_candidates" to "64", "frontage_options" to "0",
                "infill_candidates" to "32", "infill_options" to "0", "route-state-budget" to "8", "draft_samples" to "999")
            every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any()) } throws
                CivsRejection("autonomy-no-site", values)
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { result.isDone }
            result.join().complete shouldBe false
            (requireNotNull(result.join().rejected["autonomy-no-site"]) >= CivsVillageSiteScoring.MAXIMUM_SHORTLIST) shouldBe true
            result.join().reason shouldBe "village-chunk-budget"
            (s.peakRequests <= CivsVillageSites.MAXIMUM_CHUNK_REQUESTS) shouldBe true
            result.join().rejectedSites.size shouldBe 8
            result.join().rejectedSites.all { it.phase == "complete-layout-proof" && it.reason == "autonomy-no-site" &&
                it.terrain?.columns == 177 * 177 && (it.provinceTreeColumns ?: 0) >= 8 } shouldBe true
            result.join().rejectedSites.last().point shouldBe s.fullCities.last().center
            val observations = result.join().rejectedSites
            observations.all { it.details["type"] == "HOUSE" && it.details["frontage_candidates"] == "64" &&
                it.details["route-state-budget"] == "8" && "draft_samples" !in it.details } shouldBe true
            values["route-state-budget"] = "999"
            observations.all { it.details["route-state-budget"] == "8" } shouldBe true
            result.join().created shouldBe emptyList()
            s.admissions shouldBe emptyList()
            s.cities shouldBe listOf(s.reference)
            s.registry.activeLeaseCount shouldBe 0
            val replay = s.subject.search(s.reference, 1, "Auto-")
            s.subject.cancel("details-replay-test")
            s.until { replay.isDone }
            result.join().rejectedSites shouldBe observations
            observations.all { it.details["route-state-budget"] == "8" } shouldBe true
        }
    }

    test("storage rejection stops founding and a retry retains the exact requested city identity") {
        villageScenario { s ->
            s.founding = { CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) }
            val failed = s.subject.search(s.reference, 1, "Auto-")
            s.until { failed.isDone }
            failed.join().reason shouldBe "storage-unavailable"
            failed.join().created shouldBe emptyList()
            val first = s.admissions.single().city
            s.registry.activeLeaseCount shouldBe 0
            s.founding = { admission -> s.cities += admission.city; CompletableFuture.completedFuture(admission.city) }
            val retry = s.subject.search(s.reference, 1, "Auto-")
            s.until { retry.isDone }
            retry.join().complete shouldBe true
            retry.join().created.single().city shouldBe first.id
            s.cities.count { it.id == first.id } shouldBe 1
            s.cities.last().inventory!!.materials shouldBe mapOf("OAK_LOG" to 96L, "COBBLESTONE" to 96L, "BREAD" to 24L)
            s.cities.last().settings shouldBe CivSettlementSettings(true, true, false)
        }
    }

    test("cancellation during an entered commit waits for canonical publication and releases its leases once") {
        villageScenario { s ->
            val commit = CompletableFuture<Settlement>()
            s.founding = { commit }
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.admissions.isNotEmpty() }
            val admission = s.admissions.single()
            val retained = s.registry.activeLeaseCount
            (retained > 0) shouldBe true
            admission.current() shouldBe true
            s.subject.cancel("operator-cancel")
            s.tick(10)
            admission.current() shouldBe false
            commit.isCancelled shouldBe false
            result.isDone shouldBe false
            s.registry.activeLeaseCount shouldBe retained
            s.cities += admission.city
            commit.complete(admission.city)
            s.until { result.isDone }
            result.join().created.single().city shouldBe admission.city.id
            result.join().reason shouldBe "operator-cancel"
            s.registry.activeLeaseCount shouldBe 0
            // A coarse sample chunk can be acquired again for the final full-area proof.
            // Every native acquisition must be balanced, including those separate lease lifetimes.
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
            val repeated = s.subject.search(s.reference, 1, "Auto-")
            s.until { repeated.isDone }
            repeated.join().created.single().existing shouldBe true
            s.admissions.size shouldBe 1
            s.cities.count { it.id == admission.city.id } shouldBe 1
        }
    }

    test("a colliding existing name is preserved before terrain or a founding grant is requested") {
        villageScenario { s ->
            val other = s.reference.copy(id = UUID.randomUUID(), name = "Auto-1", center = CivPosition(2000, 63, 0))
            s.cities += other
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { result.isDone }
            result.join().reason shouldBe "city-name-conflict"
            s.loads shouldBe emptyList()
            s.admissions shouldBe emptyList()
            s.cities.last() shouldBe other
        }
    }
})

class CivsProspectiveVillageSurfaceTest : FunSpec({
    test("an unpublished prospective town shares the scan budget and never loads unavailable terrain") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("village-prospective").also(paper.server::addWorld)
                world.loadChunk(0, 0)
                val scheduler = TestTaskScheduler()
                val tasks = LifecycleTaskScope(scheduler)
                val cities = mutableListOf<Settlement>()
                CivsWorld(paper.createSimplePlugin("VillageSurfaceTest"), tasks, nanoTime = { 0 }) { cities }.use { access ->
                    val city = villageReference(world.uid)
                    val result = access.prospectiveHeightField(city, city.buildings.single())
                    scheduler.tick()
                    result.isDone shouldBe false
                    scheduler.tick(40)
                    result.join().columns.size shouldBe 255 * 255
                    result.join().at(32, 0)!!.loaded shouldBe false
                    world.isChunkLoaded(2, 0) shouldBe false
                    cities shouldBe emptyList()
                }
                tasks.close()
            }
        }
    }
})

private fun villageSurface(center: CivPosition, radius: Int) = CivTerrainSurface(-64, 320, buildMap {
    for (x in -radius..radius) for (z in -radius..radius) put(civsTerrainKey(center.x + x, center.z + z),
        CivTerrainSurfaceColumn(63, true, topY = if ((x + z) % 11 == 0) 67 else 63))
})

private fun villageReference(world: UUID): Settlement {
    val hall = villageBlueprint("civs/v2/caledonia/town_hall/fixture/level1", UUID.randomUUID(), world,
        CivPosition(0, 63, 0), BuildingType.TOWN_HALL).building
    return CivsAutonomy.found(UUID.randomUUID(), UUID.randomUUID(), "Auto2", world, hall.control!!,
        128, "caledonia", hall, 1_000)
}

private fun villageBlueprint(source: String, id: UUID, world: UUID, anchor: CivPosition,
    type: BuildingType): CivBlueprint {
    val bounds = CivBox(anchor.copy(x = anchor.x - 1, z = anchor.z - 1),
        anchor.copy(x = anchor.x + 1, y = anchor.y + 2, z = anchor.z + 1))
    val control = anchor.copy(y = anchor.y + 1)
    val building = CivBuilding(id, type, 1, anchor, 0, source, bounds,
        anchor.copy(y = anchor.y + 1, z = anchor.z + 2), BuildingPhase.CONSTRUCTING, control)
    val cells = buildList {
        for (x in bounds.min.x..bounds.max.x) for (y in bounds.min.y..bounds.max.y) for (z in bounds.min.z..bounds.max.z) {
            val data = if (CivPosition(x, y, z) == control) "minecraft:structure_void" else "minecraft:air"
            add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", false))
        }
    }
    val snapshot = mockk<ArcSchematicSnapshot>()
    every { snapshot.fileSha256 } returns "a".repeat(64)
    return CivBlueprint(building, world, snapshot, cells, cells, byteArrayOf(1))
}

private fun villageLayout(city: Settlement): CivSettlementPlan {
    var next = 0
    val plots = CivsAutonomy.completeProfile.map { type ->
        val hall = city.buildings.single()
        val building = if (type == BuildingType.TOWN_HALL) hall else {
            val anchor = city.center.copy(x = city.center.x - 60 + next % 10 * 12,
                y = 63, z = city.center.z - 48 + next / 10 * 12)
            next++
            villageBlueprint("civs/v2/${city.style}/${type.name.lowercase()}/fixture/level1", UUID.randomUUID(),
                city.worldId, anchor, type).building.copy(phase = BuildingPhase.SITE).let {
                    // The detached fixture retains a real authored forester work zone for usable timber admission.
                    if (type == BuildingType.FORESTER) it.copy(schematicId = "civs/minecolonies/nordic/fundamentals/lumberjack1") else it
                }
        }
        CivSettlementPlot(building, building.bounds)
    }
    return CivSettlementPlan(1, 0, 72, plots, emptyMap(), emptyList(),
        listOf(CivBox(city.center.copy(x = city.center.x + 72), city.center.copy(x = city.center.x + 72))), listOf(1))
}

private class VillageScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    private val loaded = hashSetOf<Pair<Int, Int>>()
    private val server = mockk<Server>()
    val plugin = mockk<Plugin>()
    val world = mockk<World>()
    private val worldId = UUID.randomUUID()
    init {
        every { plugin.server } returns server
        every { plugin.logger } returns Logger.getLogger("VillageSearchTest")
        every { server.getWorld(worldId) } returns world
        every { world.uid } returns worldId
        every { world.name } returns "arc_civs_test"
        every { world.isChunkLoaded(any(), any()) } answers { firstArg<Int>() to arg<Int>(1) in loaded }
        every { world.loadChunk(any<Int>(), any<Int>()) } answers { loaded += firstArg<Int>() to arg<Int>(1); Unit }
        // The surrounding Paper fixture supplies the owning-thread contract; no chunk geometry is needed by this loader test.
        paper.server.isPrimaryThread shouldBe true
    }
    val reference = villageReference(world.uid)
    val cities = mutableListOf(reference)
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val blueprints = mockk<CivsBlueprints>()
    val access = mockk<CivsWorld>()
    val admissions = mutableListOf<CivVillageSiteAdmission>()
    val loads = mutableListOf<Pair<PaperChunkKey, CompletableFuture<Chunk?>>>()
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()
    private val nativeTickets = hashSetOf<PaperChunkKey>()
    var holdLoads = false
    var requireLeasedSurvey = false
    var chunksReady = true
    var handoffFailure: String? = null
    var surveys = 0
    var retainFailure: Throwable? = null
    var foundationFailure: Throwable? = null
    val coarseCities = mutableListOf<Settlement>()
    val fullCities = mutableListOf<Settlement>()
    var peakRequests = 0
    var peakPending = 0
    var coarseSurface: (Settlement, List<CivPosition>) -> CivTerrainSurface = { _, samples -> CivTerrainSurface(-64, 320,
        samples.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(63, true) }) }
    var fullSurface: (Settlement) -> CivTerrainSurface = { villageSurface(it.center, 127) }
    var layout: (Settlement) -> CivSettlementPlan = ::villageLayout
    var founding: (CivVillageSiteAdmission) -> CompletableFuture<Settlement> = { admission ->
        cities += admission.city
        CompletableFuture.completedFuture(admission.city)
    }
    val registry = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
        override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
            added += key
            nativeTickets += key
            return PaperChunkTicketAddResult.ADDED
        }
        override fun remove(key: PaperChunkKey): Boolean { removed += key; return nativeTickets.remove(key) }
    })
    val settings = CivsSettings(Path.of("/tmp/village-test"), setOf(world.name), 128, 500, 50_000, 4,
        120, 64, 96, 6144)
    val subject: CivsVillageSites

    init {
        val styles = listOf("nordic", "medievaloak", "birch", "original", "medievaldarkoak")
        every { blueprints.availableStyles(any()) } returns styles
        every { blueprints.autonomousSource(any(), any()) } answers {
            if (arg<BuildingType>(1) == BuildingType.PLAN_BOARD) null
            else "civs/v2/${firstArg<String>()}/${arg<BuildingType>(1).name.lowercase()}/fixture/level1"
        }
        every { blueprints.maximumLevel(any()) } returns 1
        every { blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
            val source = firstArg<String>()
            CompletableFuture.completedFuture(villageBlueprint(source, arg(1), arg(2), arg(3),
                BuildingType.valueOf(source.split('/')[3].uppercase())))
        }
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(villageBlueprint(CivsPlanBoardBlueprint.SCHEMATIC_ID,
                arg(3), arg(4), arg(5), arg(1)))
        }
        every { blueprints.fortification(any()) } returns CompletableFuture.completedFuture(null)
        every { access.prospectiveHeightField(any(), any(), any()) } answers {
            val city = firstArg<Settlement>()
            val samples = arg<List<CivPosition>?>(2)
            surveys++
            if (requireLeasedSurvey) {
                val keys = samples?.mapTo(hashSetOf()) { PaperChunkKey(city.worldId, it.x shr 4, it.z shr 4) }
                    ?: CivsChunkArea.of(city).keys()
                keys.all { it in nativeTickets && world.isChunkLoaded(it.x, it.z) } shouldBe true
            }
            val surface = if (samples == null) { fullCities += city; fullSurface(city) }
                else { coarseCities += city; coarseSurface(city, samples) }
            CompletableFuture.completedFuture(surface)
        }
        every { access.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            foundationFailure?.let { CompletableFuture.failedFuture<CivTerraformPlan?>(it) }
                ?: CompletableFuture.completedFuture<CivTerraformPlan?>(null)
        }
        every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any()) } answers {
            layout(firstArg())
        }
        subject = CivsVillageSites(plugin, tasks, settings, blueprints, access, Executor(Runnable::run), { cities },
            { true }, { worldId, center ->
                if (cities.any { it.worldId == worldId && CivsService.areasOverlap(center, 192, it.center,
                        it.radius + (3 - it.level()) * 32) }) throw CivsRejection("city-overlap")
            }, { key ->
                check(org.bukkit.Bukkit.isPrimaryThread())
                check(world.isChunkLoaded(key.x, key.z))
                retainFailure?.let { throw it }
                (registry.acquire(key) as PaperChunkTicketAcquireResult.Acquired).lease
            }, { admission ->
                admissions += admission
                founding(admission)
            }, load = { target, x, z ->
                val future = CompletableFuture<Chunk?>()
                loads += PaperChunkKey(target.uid, x, z) to future
                if (!holdLoads) {
                    target.loadChunk(x, z)
                    future.complete(mockk<Chunk>())
                }
                future
            }, nanoTime = { 0 }, clock = { 1_000 }, chunksReady = {
                handoffFailure?.let { throw CivsRejection(it) }
                chunksReady
            })
    }

    fun completeLoad(index: Int, evictUnticketed: Boolean) {
        val (key, future) = loads[index]
        world.loadChunk(key.x, key.z)
        future.complete(mockk<Chunk>()) shouldBe true
        // Paper may discard unviewed terrain as soon as its callback returns; a future value is not a ticket.
        if (evictUnticketed && key !in nativeTickets) loaded -= key.x to key.z
    }
    fun completeLoads(evictUnticketed: Boolean) {
        loads.indices.filter { !loads[it].second.isDone }.forEach { completeLoad(it, evictUnticketed) }
    }
    fun tick(count: Int) { repeat(count) {
        scheduler.tick()
        val status = subject.status()
        peakRequests = maxOf(peakRequests, status["chunk_requests"] as? Int ?: 0)
        peakPending = maxOf(peakPending, status["pending_loads"] as? Int ?: 0)
    } }
    fun until(done: () -> Boolean) {
        repeat(12_000) { if (done()) return; tick(1) }
        error("Village search did not finish: ${subject.status()}")
    }
    override fun close() { subject.close(); registry.close(); tasks.close() }
}

private fun villageScenario(action: (VillageScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    mockkObject(CivsSettlementPlans)
    try { MockBukkitTestRuntime.open().use { paper -> VillageScenario(paper).use(action) } }
    finally { unmockkObject(CivsSettlementPlans) }
}
