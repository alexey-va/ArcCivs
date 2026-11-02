package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsWarehouseRefitTest : FunSpec({
    test("one bounded shell preserves every holder lid floor route and city value in all rotations") {
        for (rotation in 0..3) {
            val f = warehouseRefitFixture(rotation)
            f.receipt.changes().size shouldBe 323
            f.transaction.validated() shouldBe f.transaction
            val states = f.receipt.replacement.blocks.associate { it.position to it.after }
            f.receipt.beforeCells.filter { it.position.y == f.building.bounds.min.y }.forEach {
                states[it.position] shouldBe it.before
            }
            f.receipt.containers.forEach { holder ->
                if (holder.position != f.building.control) states[holder.position] shouldBe holder.blockData
                if (holder.type != "BARREL") states[holder.position.copy(y = holder.position.y + 1)] shouldBe "minecraft:air"
            }
            f.receipt.routes.flatten().filter { f.building.bounds.contains(it.x, it.y, it.z) }.forEach { p ->
                states[p] shouldBe "minecraft:air"
                states[p.copy(y = p.y + 1)] shouldBe "minecraft:air"
            }
            f.after.copy(buildings = f.before.buildings, revision = f.before.revision) shouldBe f.before
            f.after.building(f.building.id)!!.copy(warehouseCaptureId = null) shouldBe f.building
            CivsWarehouseRefit.matchesCapture(f.after.building(f.building.id)!!, f.receipt.replacement) shouldBe true
            CivsWarehouseRefit.matchesCapture(f.after.building(f.building.id)!!, f.source) shouldBe false
            System.getenv("CIVS_WAREHOUSE_AUDIT_OUT")?.let { directory ->
                val root = java.nio.file.Path.of(directory)
                Files.createDirectories(root)
                Files.write(root.resolve("rotation-$rotation.schem"), f.replacementBytes)
                Files.writeString(root.resolve("rotation-$rotation.json"), Gson().toJson(f.receipt))
            }
        }
    }

    test("protected posts and existing native wiring cannot be silently removed or left floating") {
        val f = warehouseRefitFixture()
        val post = CivPosition(f.building.bounds.min.x, f.building.bounds.min.y + 1, f.building.bounds.min.z)
        shouldThrow<IllegalArgumentException> {
            CivsWarehouseRefit.shape(f.building, f.receipt.beforeCells, f.receipt.routes + listOf(listOf(post)), f.receipt.containers)
        }
        val roof = f.receipt.changes().first { it.position.y == f.building.bounds.max.y }.position
        val changed = f.receipt.beforeCells.map { if (it.position == roof) it.copy(before = "minecraft:redstone_wire", after = "minecraft:redstone_wire") else it }
        shouldThrow<IllegalArgumentException> { CivsWarehouseRefit.shape(f.building, changed, f.receipt.routes, f.receipt.containers) }
        shouldThrow<IllegalArgumentException> {
            CivsWarehouseRefit.shape(f.building, f.receipt.beforeCells, f.receipt.routes, f.receipt.containers.dropLast(1))
        }
        shouldThrow<IllegalArgumentException> {
            CivsWarehouseRefit.shape(f.building, f.receipt.beforeCells,
                listOf(listOf(f.building.entrance, f.building.entrance.copy(x = f.building.entrance.x + 8))), f.receipt.containers)
        }
        val ceiling = f.receipt.containers.first().let { it.copy(position = it.position.copy(y = f.building.bounds.max.y - 1)) }
        val high = f.receipt.beforeCells.map { if (it.position == ceiling.position)
            it.copy(before = ceiling.blockData, after = ceiling.blockData) else it }
        shouldThrow<IllegalArgumentException> {
            CivsWarehouseRefit.shape(f.building, high, f.receipt.routes, f.receipt.containers + ceiling)
        }
    }

    test("the larger write limit belongs only to the validated typed annex receipt") {
        val f = warehouseRefitFixture()
        f.transaction.validated() shouldBe f.transaction
        shouldThrow<IllegalArgumentException> { f.transaction.copy(warehouseRefit = null).validated() }
        val wrong = f.receipt.copy(replacement = f.receipt.replacement.copy(blocks = f.receipt.replacement.blocks.mapIndexed { i, cell ->
            if (i == 0) cell.copy(after = "minecraft:gold_block") else cell
        }))
        shouldThrow<IllegalArgumentException> { f.transaction.copy(warehouseRefit = wrong, worldChanges = wrong.changes()).validated() }
        shouldThrow<IllegalArgumentException> { f.transaction.copy(changes = listOf(CivInventorySlotChange(
            f.building.control!!, "BARREL", 0, null, CivPlainItem("DIAMOND", 64)))).validated() }
    }

    test("legacy snapshots remain valid and unrelated buildings cannot acquire warehouse capture metadata") {
        val f = warehouseRefitFixture()
        val gson = Gson()
        val old = gson.fromJson(gson.toJson(f.before), Settlement::class.java)
        old.building(f.building.id)!!.warehouseCaptureId shouldBe null
        CivsRules.validate(old) shouldBe f.before
        for (building in listOf(f.building.copy(type = BuildingType.HOUSE),
                f.building.copy(schematicId = "fixture/warehouse"), f.building.copy(phase = BuildingPhase.SITE))) {
            CivsSnapshotValidation.validBuilding(f.before, building.copy(warehouseCaptureId = UUID.randomUUID())) shouldBe false
        }
        val saved = gson.fromJson(gson.toJson(f.transaction), CivInventoryTransaction::class.java).validated()
        saved shouldBe f.transaction
        f.receipt.before(f.after) shouldBe f.before
    }

    test("immutable old and new captures plus the no-item-change intent survive a real store restart") {
        val f = warehouseRefitFixture()
        val root = Files.createTempDirectory("civs-warehouse-refit-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(f.before).join()
                store.savePlan(f.source).join()
                store.saveSchematic(f.source.id, f.sourceBytes).join()
                store.savePlan(f.receipt.replacement).join()
                store.saveSchematic(f.receipt.replacement.id, f.replacementBytes).join()
                store.saveInventoryTransaction(f.transaction).join()
                shouldThrow<CompletionException> { store.saveSchematic(f.source.id, f.replacementBytes).join() }
            }
            CivsStore(root, Executor { it.run() }).use { store ->
                store.loadPlan(f.source.id).join() shouldBe f.source
                store.loadPlan(f.receipt.replacement.id).join() shouldBe f.receipt.replacement
                store.loadInventoryTransactions().join().single() shouldBe f.transaction
                store.save(f.after).join()
                CivsWarehouseRefit.retainedPlanId(store.load().join().single().building(f.building.id)!!) shouldBe f.receipt.replacement.id
            }
        } finally { root.toFile().deleteRecursively() }
    }
})

internal data class WarehouseRefitFixture(val before: Settlement, val building: CivBuilding, val source: CivPlacementPlan,
    val receipt: CivWarehouseRefitReceipt, val after: Settlement, val transaction: CivInventoryTransaction,
    val sourceBytes: ByteArray, val replacementBytes: ByteArray)

internal fun warehouseRefitFixture(rotation: Int = 0, worldId: UUID = UUID(184, 4),
    items: List<CivResidentItem?> = List(STORAGE_SLOTS) { CivResidentItem("STONE", it + 1) }): WarehouseRefitFixture {
    val anchor = CivPosition(30, 63, 30)
    fun world(x: Int, y: Int, z: Int): CivPosition = CivsBlueprintGeometry.rotate(
        ru.arc.paper.api.ArcSchematicPoint(x - 6, y, z - 7), rotation).let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
    val corners = listOf(world(0, 0, 0), world(22, 4, 6))
    val box = CivBox(CivPosition(corners.minOf { it.x }, 63, corners.minOf { it.z }),
        CivPosition(corners.maxOf { it.x }, 67, corners.maxOf { it.z }))
    val building = CivBuilding(UUID(184, 1), BuildingType.WAREHOUSE, 3, anchor, rotation * 90,
        "civs/v2/farthest-frontier/craftsmanship-masonry-leftwarehouse/98bde3ab878856d62340/level3", box,
        world(6, 1, 7), BuildingPhase.ACTIVE, control = world(10, 1, 4))
    val holders = buildMap {
        listOf(7, 10, 13, 16, 19, 21).forEach { put(world(it, 1, 1), "minecraft:chest[facing=south,type=single,waterlogged=false]") }
        listOf(7, 10).forEach { put(world(it, 1, 4), "minecraft:barrel[facing=up,open=false]") }
        listOf(13, 16).forEach { put(world(it, 1, 4), "minecraft:chest[facing=south,type=single,waterlogged=false]") }
        listOf(7, 10, 14).forEach { put(world(it, 2, 4), "minecraft:chest[facing=south,type=single,waterlogged=false]") }
    }
    val cells = buildList {
        for (y in 0..4) for (z in 0..6) for (x in 0..22) {
            val p = world(x, y, z)
            val state = holders[p] ?: if (y == 0) "minecraft:cobblestone" else if (x == 14 && y == 1 && z == 4) "minecraft:oak_planks" else "minecraft:air"
            add(CivPlanBlock(p, state, state))
        }
    }.sortedWith(compareBy({ it.position.y }, { it.position.z }, { it.position.x }))
    val containers = holders.map { (p, state) -> CivRelocatedContainer(p, state.substringBefore('[').removePrefix("minecraft:").uppercase(), state, items, items) }
    val routes = listOf((7 downTo 3).map { world(6, 1, it) } + (7..10).map { world(it, 1, 3) })
    val before = CivsRules.validate(settlementPlanCity().copy(worldId = worldId, style = "caledonia",
        buildings = settlementPlanCity().buildings.map { it.copy(level = 3) } + building, stock = Stock(wood = 23, stone = 41, food = 17)))
    val sourceBuilding = building.copy(level = 1, schematicId = building.schematicId.dropLast(1) + "1", phase = BuildingPhase.CONSTRUCTING,
        control = world(7, 1, 6))
    val sourceCells = cells.filterNot { it.position == sourceBuilding.control }
    val sourceBytes = civsGeneratedSchematic(box, anchor, sourceCells.associate { it.position to it.after } +
        (sourceBuilding.control!! to "minecraft:structure_void"), 1)
    val source = CivPlacementPlan(building.id, before.id, worldId, sourceBuilding, sourceCells,
        capturedSha256 = CivsGateRefit.sha256(sourceBytes))
    val draft = CivsWarehouseRefit.proposal(before, source, cells, routes, containers, UUID(184, 2))
    val bytes = CivsWarehouseRefit.schematic(draft, 1)
    val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes))
    val receipt = CivWarehouseRefitReceipt(building, source, replacement, cells, routes, containers)
    val after = CivsRules.validate(receipt.after(before))
    val tx = CivInventoryTransaction(UUID(184, 3), before.id, before.revision, after, emptyList(),
        worldChanges = receipt.changes(), warehouseRefit = receipt).validated()
    return WarehouseRefitFixture(before, building, source, receipt, after, tx, sourceBytes, bytes)
}
