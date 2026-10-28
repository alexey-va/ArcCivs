package ru.arc.civs

/** One-way conversion of old closed reserves. Native bags and accepted receipts keep their exact ownership. */
internal object CivsAutoStorageMigration {
    fun ready(city: Settlement): Boolean = city.autonomy == null || city.inventory?.let {
        it.mode == CivStorageMode.PHYSICAL && it.materials.isEmpty() && it.buildingMaterials.orEmpty().isEmpty()
    } == true

    fun waiting(city: Settlement): Boolean = city.autonomy != null && !ready(city)

    fun canMigrate(city: Settlement): Boolean = CivsAutonomy.legacyStock(city)

    fun migrate(city: Settlement): Settlement {
        if (!canMigrate(city)) return city
        val materials = linkedMapOf<String, Long>()
        val workplaces = linkedMapOf<String, MutableMap<String, Long>>()
        fun add(material: String, amount: Long, buildingId: java.util.UUID? = null) {
            val target = buildingId?.let { workplaces.getOrPut(it.toString(), ::linkedMapOf) } ?: materials
            if (amount > 0) target[material] = Math.addExact(target[material] ?: 0L, amount)
        }
        val wood = city.buildings.filter { it.type == BuildingType.FORESTER }.mapNotNull(CivsProduction::material)
            .distinct().singleOrNull() ?: "OAK_LOG"
        fun addStock(stock: Stock, buildingId: java.util.UUID? = null) {
            val timber = buildingId?.let(city::building)?.takeIf { it.type == BuildingType.FORESTER }
                ?.let(CivsProduction::material) ?: wood
            add(timber, stock.wood, buildingId); add("COBBLESTONE", stock.stone, buildingId); add("BREAD", stock.food, buildingId)
        }
        addStock(city.stock)
        fun migrateWorker(worker: CivWorker): CivWorker {
            worker.pendingStock?.let { addStock(it, worker.assignment?.takeIf { id -> city.building(id) != null }) }
            val closedCargo = worker.cargo?.takeIf { it.stockResource != null }
            closedCargo?.let { cargo -> add(when (requireNotNull(cargo.stockResource)) {
                Resource.WOOD -> wood
                Resource.STONE -> "COBBLESTONE"
                Resource.FOOD -> "BREAD"
            }, cargo.amount, CivsLogistics.destination(city, cargo)?.id) }
            return worker.copy(pendingStock = null, cargo = worker.cargo.takeIf { closedCargo == null })
        }
        val workers = city.workers.map(::migrateWorker)
        val corpses = city.corpses?.map { corpse -> corpse.copy(worker = migrateWorker(corpse.worker)) }
        city.autonomy?.products.orEmpty().forEach { (product, amount) -> add(productMaterial(product), amount) }
        return CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL, materials,
            buildingMaterials = workplaces.filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }),
            workers = workers, corpses = corpses, autonomy = requireNotNull(city.autonomy).copy(products = null, storage = null),
            research = city.research?.let { state -> state.copy(project = state.project?.let { project ->
                project.copy(cost = nativeCost(project.cost), termsVersion = 2)
            }) }, revision = Math.addExact(city.revision, 1)))
    }

    fun nativeCost(cost: CivResearchCost): CivResearchCost {
        val materials = cost.materials.toMutableMap()
        fun add(material: String, amount: Long) { if (amount > 0) materials.merge(material, amount, Math::addExact) }
        add("OAK_LOG", cost.stock.wood); add("COBBLESTONE", cost.stock.stone); add("BREAD", cost.stock.food)
        cost.products.forEach { (product, count) -> add(productMaterial(product), count) }
        return CivResearchCost(materials = materials.toSortedMap())
    }

    private fun productMaterial(product: CivIndustryProduct): String =
        if (product == CivIndustryProduct.IRON_TOOL_KIT) "IRON_PICKAXE" else product.name
}
