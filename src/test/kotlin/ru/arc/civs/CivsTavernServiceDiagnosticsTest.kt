package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsTavernServiceDiagnosticsTest : FunSpec({
    for ((raisedCounter, carriedBread) in listOf(false to 4L, true to 4L, true to 0L))
    test("two guests receive service after a thirty-eight-second approach with raised counter=$raisedCounter and keeper bread=$carriedBread") {
        residentRuntime { h ->
            try {
                val keeperId = h.standingTavernkeeper()
                val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")
                val original = h.city.buildings.first { it.type == BuildingType.TAVERN }
                val tavern = original.copy(control = CivPosition(6, 64, -4),
                    bounds = CivBox(CivPosition(3, 63, -5), CivPosition(9, 68, 5)))
                for (x in 3..9) for (z in -5..5) {
                    h.world.getBlockAt(x, 63, z).blockData = Bukkit.createBlockData("minecraft:oak_slab[type=top]")
                    for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.AIR
                }
                h.world.getBlockAt(6, 64, 1).type = Material.SPRUCE_PLANKS
                for ((point, facing) in listOf(CivPosition(7, 64, 1) to "east", CivPosition(6, 64, 0) to "north",
                    CivPosition(5, 64, 1) to "west", CivPosition(6, 64, 2) to "south"))
                    h.world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(
                        "minecraft:spruce_stairs[facing=$facing,half=bottom,shape=straight]")
                h.world.getBlockAt(6, 64, -4).type = Material.BARREL
                if (raisedCounter) {
                    h.world.getBlockAt(5, 64, -2).blockData = Bukkit.createBlockData("minecraft:birch_stairs[facing=west,half=top,shape=straight]")
                    h.world.getBlockAt(6, 64, -2).blockData = Bukkit.createBlockData("minecraft:birch_slab[type=top]")
                    h.world.getBlockAt(7, 64, -2).blockData = Bukkit.createBlockData("minecraft:birch_stairs[facing=east,half=top,shape=straight]")
                }
                CivsWorksiteLayouts.installCatalog(mapOf(tavern.schematicId to com.google.gson.JsonParser.parseString(
                    """{"tavern":{"dining":[[1,0,1],[0,0,0],[-1,0,1],[0,0,2]]}}""").asJsonObject))
                val first = h.city.workers.first { it.id == h.workerId }.copy(inventory = mapOf("IRON_AXE" to 1L))
                h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map { if (it.id == tavern.id) tavern else it },
                    workers = h.city.workers.map {
                        if (it.id == keeperId) {
                            if (carriedBread > 0) CivsEquipment.collectSupplies(it, mapOf("BREAD" to carriedBread)) else it
                        } else first
                    } + first.copy(id = secondId, name = "Second guest")))
                val source = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(6, 64, -3))
                h.tavernServiceSources[h.workerId] = source
                h.tavernServiceSources[secondId] = source
                h.owner.collisionActors().first { it.uniqueId == keeperId }.teleport(Location(h.world, 6.5, 64.0, -2.5))
                h.owner.collisionActors().first { it.uniqueId == h.workerId }.teleport(Location(h.world, .5, 64.0, .5))
                val served = mutableListOf<UUID>()
                val eaten = mutableListOf<UUID>()
                var pickups = 0
                h.physicalFoodPickup = { order ->
                    h.owner.validateTavernPickup(h.city.id, keeperId, requireNotNull(order.servingFor), tavern.id, "BREAD",
                        requireNotNull(order.servingTicket))
                    pickups++
                    h.city = h.city.copy(workers = h.city.workers.map {
                        if (it.id == keeperId) CivsEquipment.collectSupplies(it, mapOf("BREAD" to 4L)) else it
                    }, revision = h.city.revision + 1)
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.deliverTavernMeal = { cityId, proof ->
                    h.owner.validateTavernDelivery(cityId, proof)
                    if (raisedCounter) {
                        val servingNpc = h.owner.collisionActors().first { it.uniqueId == keeperId }
                        val grounded = servingNpc.location
                        val before = h.city
                        servingNpc.teleport(grounded.clone().add(0.0, .344, 0.0))
                        shouldThrow<CivsRejection> { h.owner.validateTavernDelivery(cityId, proof) }.reason shouldBe "stale-meal"
                        h.city shouldBe before
                        servingNpc.teleport(grounded)
                        h.owner.validateTavernDelivery(cityId, proof)
                    }
                    h.city = CivsTavernMeals.deliver(h.city, proof)
                    served += proof.guestId
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    eaten += plan.workerId
                    CompletableFuture.completedFuture(h.city)
                }
                h.holdNavigation = true
                h.owner.residentLifeEnabled = false
                h.owner.pulse(5_000)
                val firstNpc = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                val secondNpc = h.owner.collisionActors().first { it.uniqueId == secondId }
                firstNpc.teleport(Location(h.world, .5, 64.0, .5))
                secondNpc.teleport(Location(h.world, .5, 64.0, 2.5))
                h.owner.residentLifeEnabled = true
                h.owner.pulse(6_000)
                firstNpc.teleport(requireNotNull(h.routeTarget(h.workerId)) { h.owner.debug(h.city.id, h.workerId).toString() })
                secondNpc.teleport(requireNotNull(h.routeTarget(secondId)) { h.owner.debug(h.city.id, secondId).toString() })
                h.navigationNow = 44_000
                h.owner.pulse(44_000)
                firstNpc.isSeated shouldBe true
                secondNpc.isSeated shouldBe true
                val seat = h.mealState()["seat"] as CivPosition
                val chair = h.world.getBlockAt(seat.x, seat.y, seat.z)
                val chairState = chair.blockData
                chair.type = Material.STONE
                h.mealState()["seat_failure"] shouldBe "seat-blocked"
                chair.blockData = chairState
                val keeper = h.owner.collisionActors().first { it.uniqueId == keeperId }
                val keeperFeet = keeper.location.block
                val keeperFeetState = keeperFeet.blockData
                keeperFeet.type = Material.STONE
                val blockedKeeper = (h.mealState()["service_keepers"] as List<*>).single() as Map<*, *>
                blockedKeeper["unavailable"] shouldBe "life-unavailable"
                blockedKeeper["life_failure"] shouldBe "body-blocked"
                keeperFeet.blockData = keeperFeetState
                h.mealState()["seat_failure"] shouldBe null
                h.mealState()["service_keepers_truncated"] shouldBe false
                val aggregate = h.owner.debug(h.city.id, null)
                for (entry in aggregate["entries"] as List<*>) {
                    val compact = (entry as Map<*, *>)["meal"] as Map<*, *>
                    compact.containsKey("service_keepers") shouldBe false
                    compact.containsKey("seat_failure") shouldBe false
                }
                if (carriedBread == 0L) keeper.teleport(Location(h.world, 6.5, 64.0, -.5))
                h.owner.pulse(44_001)
                h.releaseNavigation()
                var observedJump = false
                var checkedAirborneEndpoint = false
                for (tick in 1..800) {
                    h.navigate()
                    val serviceTicket = h.mealState(keeperId)["service_ticket"]
                    if (raisedCounter && !observedJump && serviceTicket != null && h.routeIdentity(keeperId) != null &&
                        !citizenPacketSupported(h.world, keeper.location.point())) {
                        observedJump = true
                        val guestId = h.mealState(keeperId)["service_guest"] as UUID
                        val guestTicket = h.mealState(guestId)["ticket"]
                        shouldThrow<CivsRejection> {
                            h.owner.validateTavernPickup(h.city.id, keeperId, guestId, tavern.id, "BREAD")
                        }.reason shouldBe "stale-meal"
                        val breadBefore = h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") }
                        h.owner.pulse(requireNotNull(h.navigationNow))
                        withClue(h.owner.debug(h.city.id, keeperId)) {
                            h.mealState(keeperId)["service_ticket"] shouldBe serviceTicket
                            h.mealState(guestId)["ticket"] shouldBe guestTicket
                        }
                        h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe breadBefore
                        if (!checkedAirborneEndpoint) {
                            checkedAirborneEndpoint = true
                            val actual = keeper.location
                            val loaded = h.mealState(keeperId)["service_loaded"] == true
                            val endpoint = if (loaded) (h.mealState(keeperId)["service_target"] as CivPosition).location(h.world)
                                else source.standing.location(h.world)
                            keeper.teleport(endpoint.add(0.0, .172, 0.0))
                            if (!loaded) civsCanReachContainer(keeper.location, source.container) shouldBe false
                            val route = h.routeIdentity(keeperId)
                            val priorPickups = pickups
                            val priorServed = served.size
                            h.owner.pulse(requireNotNull(h.navigationNow))
                            withClue(h.owner.debug(h.city.id, keeperId)) {
                                h.mealState(keeperId)["service_ticket"] shouldBe serviceTicket
                                (h.routeIdentity(keeperId) === route) shouldBe true
                            }
                            pickups shouldBe priorPickups
                            served.size shouldBe priorServed
                            h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe breadBefore
                            keeper.teleport(actual)
                        }
                    }
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (eaten.size == 2) break
                }
                observedJump shouldBe raisedCounter
                checkedAirborneEndpoint shouldBe raisedCounter
                withClue(h.owner.debug(h.city.id, null)) {
                    served.toSet() shouldBe setOf(h.workerId, secondId)
                    eaten.toSet() shouldBe setOf(h.workerId, secondId)
                }
                h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 2
                pickups shouldBe if (carriedBread == 0L) 1 else 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("an aggregate eight-resident snapshot fits city history and reserves service details for one worker") {
        residentRuntime { h ->
            h.owner.residentLifeEnabled = false
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it
            }, workers = h.city.workers + List(7) {
                CivWorker(UUID.randomUUID(), "Guest ${it + 1}", null)
            }))
            for (now in 2_000L..5_000L step 1_000) h.owner.pulse(now)
            val line = CivsDebug.lines(listOf("citizens", h.city.id.toString())) { _, cityId, workerId ->
                h.owner.debug(cityId, workerId)
            }.single()
            withClue("city history clips at 16384 characters, actual=${line.length}") { (line.length <= 16_384) shouldBe true }
            val payload = com.google.gson.JsonParser.parseString(line.removePrefix("ARCCIVS_DEBUG ")).asJsonObject
            val state = payload.getAsJsonObject("state")
            val planner = state.getAsJsonObject("planner_global")
            val cache = planner.getAsJsonObject("snapshot_cache")
            cache.getAsJsonPrimitive("index_payload_bytes").isNumber shouldBe true
            cache.getAsJsonPrimitive("index_payload_mib").isNumber shouldBe true
            planner.getAsJsonPrimitive("active_job_section_payload_bytes").isNumber shouldBe true
            planner.getAsJsonObject("snapshot_warmup").has("entries") shouldBe true
            val entries = state.getAsJsonArray("entries")
            entries.size() shouldBe 8
            entries.forEach {
                val meal = it.asJsonObject.getAsJsonObject("meal")
                meal.has("service_keepers") shouldBe false
                meal.has("building") shouldBe false
                meal.get("pending").asBoolean shouldBe false
                meal.get("retry_at").asLong shouldBe 0L
            }
            h.mealState().containsKey("building") shouldBe true
            h.mealState().containsKey("service_keepers") shouldBe true

            val globalState = h.owner.debug(null, null)
            val globalPlanner = globalState.getValue("planner_global") as Map<*, *>
            val globalCache = globalPlanner["snapshot_cache"] as Map<*, *>
            val globalWarmup = globalPlanner["snapshot_warmup"] as Map<*, *>
            for (cityCount in listOf(5, 8)) {
                val cityIds = List(cityCount) { UUID(321, it.toLong() + 1) }
                val cached = cityIds.map { cityId -> mapOf("city" to cityId, "chunks" to 142,
                    "sections" to 1000, "maximum_sections" to 1024, "warm_sections_limit" to 512,
                    "index_payload_bytes" to 8_192_000L, "index_payload_mib" to 7.8125, "shared_sections" to 121,
                    "hits" to 532_410L, "cold" to 6582, "evictions" to 13_251, "refreshes" to 1451,
                    "warm_hits" to 1564, "warm_capacity_rejections" to 321) }
                val warmed = cityIds.map { cityId -> mapOf("city" to cityId, "active" to true,
                    "target_sections" to 600, "target_truncated" to false, "processed_sections" to 417,
                    "remaining_batches" to 16, "remaining_states" to 81, "dynamic_states_on_demand" to 316,
                    "unavailable_sections" to 27, "failed_sections" to 11, "capacity_limited" to true,
                    "capacity_skipped_sections" to 126) }
                val populated = globalPlanner + mapOf(
                    "snapshot_main_requests" to 412_000L,
                    "snapshot_cache" to (globalCache + mapOf("sections" to cityCount * 1000,
                        "index_payload_bytes" to cityCount * 8_192_000L, "index_payload_mib" to cityCount * 7.8125,
                        "cities_total" to cityCount, "cities" to cached)),
                    "snapshot_warmup" to (globalWarmup + mapOf("cities_total" to cityCount, "entries" to warmed)))
                val globalLine = CivsDebug.lines(listOf("citizens")) { _, _, _ ->
                    globalState + ("planner_global" to populated)
                }.single()
                withClue("$cityCount populated city rows, actual=${globalLine.length}") {
                    (globalLine.length <= 16_384) shouldBe true
                }
                val globalPayload = com.google.gson.JsonParser.parseString(globalLine.removePrefix("ARCCIVS_DEBUG ")).asJsonObject
                    .getAsJsonObject("state")
                globalPayload.getAsJsonArray("entries").size() shouldBe 8
                val parsedPlanner = globalPayload.getAsJsonObject("planner_global")
                val parsedCache = parsedPlanner.getAsJsonObject("snapshot_cache")
                parsedCache.get("index_payload_bytes").asLong shouldBe cityCount * 8_192_000L
                val parsedCities = parsedCache.getAsJsonArray("cities")
                parsedCities.size() shouldBe cityCount
                parsedCities.forEach { it.asJsonObject.get("hits").asLong shouldBe 532_410L }
                val parsedWarmup = parsedPlanner.getAsJsonObject("snapshot_warmup").getAsJsonArray("entries")
                parsedWarmup.size() shouldBe cityCount
                parsedWarmup.forEach { it.asJsonObject.get("processed_sections").asInt shouldBe 417 }
            }
        }
    }
})
