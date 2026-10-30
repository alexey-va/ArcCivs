package ru.arc.civs

/** A fixed selected recipe, copied into the actor plan; native towns use ordinary material recipes. */
internal data class CivIndustryRecipe(
    val id: String,
    val type: BuildingType,
    val minimumLevel: Int,
    val research: CivResearchId?,
    val stock: Stock,
    val products: Map<CivIndustryProduct, Long>,
    val inputs: Map<String, Long>,
    val output: String,
    val amount: Long,
    val product: CivIndustryProduct?,
)

internal data class CivIndustryWork(val building: CivBuilding, val recipe: CivIndustryRecipe)

/** Legacy closed recipes keep their saved chain; physical Auto shares ordinary production recipes. */
internal object CivsIndustry {
    val processorTypes = setOf(BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH)

    private fun recipe(product: CivIndustryProduct): CivIndustryRecipe = when (product) {
        CivIndustryProduct.RAW_IRON -> CivIndustryRecipe("RAW_IRON", BuildingType.QUARRY, 1, CivResearchId.MATERIALS,
            Stock(), emptyMap(), emptyMap(), "RAW_IRON", 1, product)
        CivIndustryProduct.CHARCOAL -> CivIndustryRecipe("CHARCOAL", BuildingType.SMELTERY, 1, CivResearchId.MATERIALS,
            Stock(wood = 10), emptyMap(), emptyMap(), "CHARCOAL", 8, product)
        CivIndustryProduct.GRAVEL -> CivIndustryRecipe("GRAVEL", BuildingType.CRUSHER, 1, CivResearchId.MATERIALS,
            Stock(stone = 2), emptyMap(), emptyMap(), "GRAVEL", 1, product)
        CivIndustryProduct.SAND -> CivIndustryRecipe("SAND", BuildingType.CRUSHER, 2, CivResearchId.MATERIALS,
            Stock(), mapOf(CivIndustryProduct.GRAVEL to 2), emptyMap(), "SAND", 1, product)
        CivIndustryProduct.GLASS -> CivIndustryRecipe("GLASS", BuildingType.SMELTERY, 1, CivResearchId.GLASSWORK,
            Stock(), mapOf(CivIndustryProduct.SAND to 8, CivIndustryProduct.CHARCOAL to 1), emptyMap(), "GLASS", 8, product)
        CivIndustryProduct.IRON_INGOT -> CivIndustryRecipe("IRON_INGOT", BuildingType.SMELTERY, 1, CivResearchId.METALLURGY,
            Stock(), mapOf(CivIndustryProduct.RAW_IRON to 8, CivIndustryProduct.CHARCOAL to 1), emptyMap(), "IRON_INGOT", 8, product)
        CivIndustryProduct.IRON_TOOL_KIT -> CivIndustryRecipe("IRON_TOOL_KIT", BuildingType.BLACKSMITH, 1, CivResearchId.METALLURGY,
            Stock(wood = 2), mapOf(CivIndustryProduct.IRON_INGOT to 3), emptyMap(), "IRON_TOOL_KIT", 1, product)
    }

    fun recipes(city: Settlement, building: CivBuilding): List<CivIndustryRecipe> = if (CivsAutonomy.legacyStock(city)) {
        CivIndustryProduct.entries.map(::recipe).filter { it.type == building.type && allowed(city, building, it) }
    } else CivsProduction.options(city, building).map { material ->
        val selected = building.copy(production = material)
        CivIndustryRecipe(material, building.type, if (building.type == BuildingType.CRUSHER && material == "SAND") 2 else 1,
            null, Stock(), emptyMap(), CivsProduction.inputs(selected), material, CivsProduction.amount(selected), null)
    }

    /** Current sample bill first, then small finite buffers. Products never become construction payments. */
    private fun targets(city: Settlement): Map<CivIndustryProduct, Long> = buildMap {
        if (CivsResearch.has(city, CivResearchId.MATERIALS)) {
            put(CivIndustryProduct.RAW_IRON, 8); put(CivIndustryProduct.CHARCOAL, 8)
            put(CivIndustryProduct.GRAVEL, 16); put(CivIndustryProduct.SAND, 16)
        }
        if (CivsResearch.has(city, CivResearchId.METALLURGY)) {
            put(CivIndustryProduct.IRON_INGOT, 16); put(CivIndustryProduct.IRON_TOOL_KIT, 1)
        }
        if (CivsResearch.has(city, CivResearchId.GLASSWORK)) put(CivIndustryProduct.GLASS, 16)
        city.research?.project?.takeUnless { it.funded || it.paused }?.cost?.products?.forEach { (product, amount) ->
            put(product, maxOf(get(product) ?: 0L, amount))
        }
    }

    private fun nextStage(city: Settlement): CivIndustryProduct? {
        fun stage(product: CivIndustryProduct): CivIndustryProduct = when (product) {
            CivIndustryProduct.SAND -> if (CivsResearch.product(city, CivIndustryProduct.GRAVEL) < 2) CivIndustryProduct.GRAVEL else product
            CivIndustryProduct.IRON_INGOT -> when {
                CivsResearch.product(city, CivIndustryProduct.RAW_IRON) < 8 -> CivIndustryProduct.RAW_IRON
                CivsResearch.product(city, CivIndustryProduct.CHARCOAL) < 1 -> CivIndustryProduct.CHARCOAL
                else -> product
            }
            CivIndustryProduct.GLASS -> when {
                CivsResearch.product(city, CivIndustryProduct.SAND) < 8 -> stage(CivIndustryProduct.SAND)
                CivsResearch.product(city, CivIndustryProduct.CHARCOAL) < 1 -> CivIndustryProduct.CHARCOAL
                else -> product
            }
            CivIndustryProduct.IRON_TOOL_KIT -> if (CivsResearch.product(city, CivIndustryProduct.IRON_INGOT) < 3)
                stage(CivIndustryProduct.IRON_INGOT) else product
            else -> product
        }
        val bill = city.research?.project?.takeUnless { it.funded || it.paused }?.cost?.products.orEmpty()
        bill.entries.firstOrNull { (product, amount) -> CivsResearch.product(city, product) < amount }?.let { return stage(it.key) }
        return targets(city).entries.firstOrNull { (product, amount) -> CivsResearch.product(city, product) < amount }?.let { stage(it.key) }
    }

    fun autonomousWork(city: Settlement, building: CivBuilding): CivIndustryWork? {
        if (!CivsAutonomy.legacyStock(city) || city.autonomy?.paused != false || city.pendingDeletion != null || building.phase != BuildingPhase.ACTIVE) return null
        val product = nextStage(city) ?: return null
        val recipe = recipe(product)
        if (!allowed(city, building, recipe)) return null
        // The sole quarry still supplies the current raw construction bill before switching to ore.
        if (product == CivIndustryProduct.RAW_IRON && CivsAutonomy.nextGoal(city)?.missing?.stone?.let { it > 0 } == true) return null
        return CivIndustryWork(building, recipe).takeIf { room(city, it) }
    }

    fun desiredBuilding(city: Settlement): CivBuilding? {
        val project = city.research?.project
        if (project?.funded == true && !project.paused) return CivsResearch.university(city)
        return city.buildings.sortedBy { it.id.toString() }.firstOrNull { autonomousWork(city, it) != null }
    }

    fun view(city: Settlement) = CivIndustryView(city.id, city.autonomy != null, city.autonomy?.products.orEmpty().toMap(),
        city.buildings.filter { it.type in processorTypes || it.type == BuildingType.QUARRY && city.autonomy != null }.map { building ->
            val work = if (CivsAutonomy.legacyStock(city)) autonomousWork(city, building) else null
            val choices = recipes(city, building)
            CivIndustryBuildingView(building.id, building.type, building.production, choices.map { it.id },
                if (CivsAutonomy.legacyStock(city)) work?.recipe?.id else CivsProduction.material(building),
                if (CivsAutonomy.legacyStock(city) && work == null) "waiting-orders"
                else if (work != null && !inputsReady(city, work)) "waiting-inputs" else null, choices.map(::view))
        })

    fun allowed(city: Settlement, building: CivBuilding, recipe: CivIndustryRecipe): Boolean =
        building.phase == BuildingPhase.ACTIVE && building.type == recipe.type && building.level >= recipe.minimumLevel &&
            (recipe.research == null || CivsResearch.has(city, recipe.research))

    fun inputsReady(city: Settlement, work: CivIndustryWork): Boolean = city.stock.covers(work.recipe.stock) &&
        work.recipe.products.all { (product, amount) -> CivsResearch.product(city, product) >= amount }

    fun room(city: Settlement, work: CivIndustryWork): Boolean =
        work.recipe.product?.let { CivsResearch.product(city, it) <= 64L - work.recipe.amount } == true

    fun view(recipe: CivIndustryRecipe) = CivIndustryRecipeView(recipe.id, recipe.stock, recipe.products,
        recipe.inputs, recipe.output, recipe.amount)

    /** Called only after the observed work/depot-arrival proof. Identity and funds are checked again here. */
    fun complete(city: Settlement, workerId: java.util.UUID, cycle: Long, nextAt: Long, work: CivIndustryWork, now: Long): Settlement {
        CivsRules.validate(city)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (city.autonomy?.paused != false || city.inventory != null || city.pendingDeletion != null ||
            worker.assignment != work.building.id || worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt ||
            now < nextAt || worker.cargo != null || CivsResidents.isChild(worker) ||
            city.building(work.building.id) != work.building || !allowed(city, work.building, work.recipe) ||
            work.recipe.product?.let(::recipe) != work.recipe)
            throw CivsRejection("stale-delivery")
        if (!inputsReady(city, work)) throw CivsRejection("materials-missing")
        if (!room(city, work)) throw CivsRejection("storage-full")
        val products = city.autonomy.products.orEmpty().toMutableMap()
        work.recipe.products.forEach { (product, count) ->
            val remaining = (products[product] ?: 0L) - count
            if (remaining == 0L) products.remove(product) else products[product] = remaining
        }
        val output = requireNotNull(work.recipe.product)
        products[output] = Math.addExact(products[output] ?: 0, work.recipe.amount)
        val updated = CivsResidents.trained(worker.copy(completedCycles = Math.addExact(cycle, 1),
            nextDeliveryAt = Math.addExact(now, CivsResidents.workDuration(worker, CivsRules.deliveryInterval(city, work.building)))), work.building.type, city)
        return CivsRules.validate(city.copy(stock = city.stock - work.recipe.stock,
            autonomy = city.autonomy.copy(products = products),
            workers = city.workers.map { if (it.id == workerId) updated else it }, revision = Math.addExact(city.revision, 1)))
    }
}
