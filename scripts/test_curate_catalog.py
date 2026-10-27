#!/usr/bin/env python3
"""Synthetic geometry/transaction regressions; no private inputs are needed."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


curate = load("catalog_curator", "curate-catalog.py")
assemble = load("catalog_assembler", "assemble-catalog.py")
dining = load("catalog_dining", "enrich-tavern-dining.py")


def room():
    names = ["minecraft:air", "minecraft:stone", "minecraft:bookshelf", "minecraft:chest", "minecraft:barrel", "minecraft:oak_planks"]
    registry = {"blocks": {name: {"boundingBox": "empty" if name.endswith(":air") else "block"} for name in names}}
    scene = curate.Scene([9, 9, 9], ["minecraft:air"], [0] * 729, [4, 1, 9], registry)
    for x in range(9):
        for z in range(9):
            scene.set((x, 1, z), "minecraft:stone", "fixture-floor")
    return scene


class CurationGeometryTest(unittest.TestCase):
    def test_native_source_contact_plane_overrides_only_the_untagged_hut_height_guess(self):
        scene = room(); primary = [4, 4, 4]
        placement = {"groundPolicy": "source-default-anchor-minus-one", "sourceGroundY": 3,
                     "groundTagPositions": [], "boundaryFloorCandidates": [{"y": 1}]}
        proof = curate.contact_ground(scene.size, scene.palette, scene.cells, primary, placement, scene.registry)
        self.assertEqual(1, proof["y"])
        self.assertEqual(32, proof["boundaryColumns"])
        self.assertIsNone(curate.contact_ground(scene.size, scene.palette, scene.cells, primary,
            {**placement, "groundPolicy": "groundlevel-tag", "groundTagPositions": [[4, 3, 4]]}, scene.registry))
        for x in range(9):
            for z in range(9):
                if x in (0, 8) or z in (0, 8):
                    scene.set((x, 2, z), "minecraft:stone", "enclosed-basement-wall")
        self.assertIsNone(curate.contact_ground(scene.size, scene.palette, scene.cells, primary, placement, scene.registry))

    def test_paid_source_ground_materializes_barriers_but_preserves_clearing_air_and_authored_cells(self):
        scene = room()
        scene.set((3, 1, 4), "minecraft:barrier", "source-terrain")
        scene.set((4, 1, 4), "minecraft:air", "source-basement")
        scene.set((5, 1, 4), "minecraft:oak_planks", "source-room")
        curate.front_pad(scene, ([1, 1, 1], [7, 7, 7]), (5, 2, 8))
        self.assertEqual("minecraft:dirt", scene.at((3, 1, 4)))
        self.assertEqual("minecraft:air", scene.at((4, 1, 4)))
        self.assertEqual("minecraft:oak_planks", scene.at((5, 1, 4)))

    def mine_cap(self, ground=5):
        registry = {"blocks": {"minecraft:air": {"boundingBox": "empty"}}}
        scene = curate.Scene([9, 11, 9], ["minecraft:air"], [0] * 891, [4, ground, 9], registry)
        solid = {"boxes": [[0, 0, 0, 1, 1, 1]], "fullSolid": True}
        ladder = "minecraft:ladder[facing=west,waterlogged=false]"
        scene.native = {"minecraft:air": {"boxes": [], "fullSolid": False}, "minecraft:stone": solid,
                        "minecraft:dirt": solid, "minecraft:oak_planks": solid,
                        curate.state_key(ladder): {"boxes": [[.875, 0, 0, 1, 1, 1]], "fullSolid": False}}
        for x in range(9):
            for z in range(9):
                scene.set((x, ground, z), "minecraft:stone", "fixture-floor")
        for y in range(1, ground + 1):
            scene.set((4, y, 4), ladder if y <= 2 else "minecraft:air", "source-shaft")
            scene.set((5, y, 4), "minecraft:stone", "source-backing")
        scene.set((4, ground, 4), "minecraft:dirt", "source-cap")
        scene.changes.clear()
        return scene

    def test_mine_reopens_only_a_short_existing_backed_shaft_with_one_natural_cap(self):
        scene = self.mine_cap(); bottom = (4, 1, 4)
        self.assertNotIn(bottom, scene.reached_cells(scene.reachable()[0]))
        self.assertTrue(curate.reopen_authored_mine_ladder(scene, bottom))
        self.assertEqual(3, len(scene.changes))
        self.assertIn(bottom, scene.reached_cells(scene.reachable()[0]))
        for failure in ("crafted-cap", "missing-backing", "two-soil", "too-long"):
            scene = self.mine_cap(7 if failure == "too-long" else 5)
            if failure == "crafted-cap": scene.set((4, 5, 4), "minecraft:oak_planks", "fixture")
            if failure == "missing-backing": scene.set((5, 4, 4), "minecraft:air", "fixture")
            if failure == "two-soil": scene.set((4, 4, 4), "minecraft:dirt", "fixture")
            scene.changes.clear(); before = list(scene.cells)
            self.assertFalse(curate.reopen_authored_mine_ladder(scene, bottom), failure)
            self.assertEqual(before, list(scene.cells), failure)
            self.assertEqual([], scene.changes, failure)

    def dining_room(self):
        scene = room()
        solid = {"boxes": [[0, 0, 0, 1, 1, 1]]}
        scene.native = {"minecraft:air": {"boxes": []}, "minecraft:stone": solid,
                        "minecraft:green_wool": solid,
                        "minecraft:green_carpet": {"boxes": [[0, 0, 0, 1, .0625, 1]]}}
        for facing, back in (("north", [0, .5, 0, 1, 1, .5]), ("south", [0, .5, .5, 1, 1, 1])):
            state = f"minecraft:oak_stairs[facing={facing},half=bottom,shape=straight,waterlogged=false]"
            scene.native[curate.state_key(state)] = {"boxes": [[0, 0, 0, 1, .5, 1], back]}
            scene.set((4, 2, 3 if facing == "north" else 5), state, "source-chair")
        scene.set((4, 3, 4), "minecraft:green_wool", "source-table")
        for x in (3, 5):
            for z in (3, 5):
                scene.set((x, 2, z), "minecraft:green_carpet", "source-carpet")
        return scene

    def test_dining_recognizes_authored_chairs_at_upper_table_with_fractional_carpet_approach(self):
        proofs = dining.dining_proofs(self.dining_room())
        self.assertEqual([[4, 2, 3], [4, 2, 5]], [p["seat"] for p in proofs])
        self.assertTrue(all(p["approach"][1] == 2.0625 for p in proofs))
        self.assertTrue(all(p["routeGoal"][1] == 2 for p in proofs))
        self.assertTrue(all(p["table"] == [4, 3, 4] for p in proofs))

    def test_dining_does_not_treat_roof_stairs_or_blocked_chair_as_seats(self):
        scene = self.dining_room()
        scene.set((4, 3, 4), "minecraft:air", "remove-table")
        self.assertEqual([], dining.dining_proofs(scene))
        scene = self.dining_room()
        scene.set((4, 3, 3), "minecraft:stone", "blocked-head")
        self.assertEqual([[4, 2, 5]], [p["seat"] for p in dining.dining_proofs(scene)])

    def test_storage_works_across_counter_with_real_ray_and_no_adjacent_stance(self):
        scene = room(); p = (4, 2, 4)
        scene.set(p, "minecraft:bookshelf", "source")
        for dx, dz in curate.VECTORS.values():
            scene.set((4 + dx, 2, 4 + dz), "minecraft:stone", "source-counter")
            scene.set((4 + dx, 4, 4 + dz), "minecraft:stone", "source-overhead-shelf")
        reachable, _ = scene.reachable()
        self.assertFalse(any((4 + dx, y, 4 + dz) in reachable for dx, dz in curate.VECTORS.values() for y in (1, 2, 3)))
        result = curate.storage(scene, {p: {"facing": "south", "sourceState": "minecolonies:blockminecoloniesrack"}}, None)
        self.assertEqual("chest", result[0]["kind"])
        self.assertTrue(result[0]["reachableFaces"])

    def test_solid_wall_does_not_become_a_container_approach(self):
        scene = room(); p = (4, 3, 4)
        for x in range(3, 6):
            for y in range(2, 6):
                for z in range(3, 6):
                    scene.set((x, y, z), "minecraft:stone", "sealed-source-room")
        scene.set(p, "minecraft:bookshelf", "source")
        with self.assertRaisesRegex(curate.Rejected, "storage-route"):
            curate.storage(scene, {p: {"facing": "south"}}, None)

    def test_stacked_source_racks_keep_each_container_and_clear_top_gets_chest(self):
        scene = room(); racks = {(4, y, 4): {"facing": "south"} for y in (2, 3, 4)}
        for p in racks: scene.set(p, "minecraft:bookshelf", "source")
        result = curate.storage(scene, racks, None)
        self.assertEqual(["barrel", "barrel", "chest"], [row["kind"] for row in result])
        self.assertEqual(set(racks), {tuple(row["position"]) for row in result})
        self.assertTrue(all(curate.name(scene.at(p)) in {"minecraft:barrel", "minecraft:chest"} for p in racks))

    def test_closed_top_trapdoor_is_support_but_open_panel_is_not(self):
        scene = room(); p = (4, 1, 4)
        scene.set(p, "minecraft:oak_trapdoor[half=top,open=false]", "source")
        self.assertTrue(scene.support(p))
        scene.set(p, "minecraft:oak_trapdoor[half=top,open=true]", "source")
        self.assertFalse(scene.support(p))

    def test_literal_container_gets_grounded_support_not_floating_plank(self):
        scene = room(); p = (4, 4, 4)
        scene.set(p, "minecraft:barrel[facing=south,open=false]", "source")
        curate.storage(scene, {p: {"facing": "south", "sourceState": scene.at(p)}}, None)
        self.assertEqual("minecraft:oak_planks", scene.at((4, 3, 4)))
        self.assertEqual("minecraft:oak_planks", scene.at((4, 2, 4)))

    def test_source_role_and_variant_identity_do_not_follow_vague_filename(self):
        self.assertEqual(("acacia", "fundamentals/altminer", "QUARRY", 3),
                         curate.source_family("minecolonies/acacia/fundamentals/altminer3", "minecolonies:blockhutminer[facing=south]"))
        self.assertEqual("DECORATION", curate.source_family("minecolonies/acacia/decorations/miner3", "minecraft:stone")[2])

    def basin(self):
        scene = room()
        solid = {"boxes": [[0, 0, 0, 1, 1, 1]], "blockedFluidFaces": ["DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"], "canHoldAnyFluid": False}
        empty = {"boxes": [], "blockedFluidFaces": [], "canHoldAnyFluid": True}
        scene.native = {"minecraft:stone": solid, "minecraft:cobblestone": solid, "minecraft:air": empty,
                        "minecraft:water[level=0]": empty, "minecraft:water[level=8]": empty}
        for dx, dz in curate.VECTORS.values():
            scene.set((4 + dx, 2, 4 + dz), "minecraft:stone", "basin-bank")
        scene.set((4, 2, 4), "minecraft:water[level=0]", "basin-water")
        return scene

    def test_falling_water_over_contained_pool_does_not_need_air_encasement(self):
        scene = self.basin()
        scene.set((4, 3, 4), "minecraft:water[level=8]", "authored-waterfall")
        result = curate.fluid_check(scene)
        self.assertEqual(2, result["waterCells"])
        self.assertEqual(2, result["possibleFlowCells"])

    def test_open_water_edge_is_rejected_including_waterlogged_source(self):
        for state in ("minecraft:water[level=0]", "minecraft:oak_fence[waterlogged=true]"):
            scene = self.basin(); scene.set((0, 2, 0), state, "open-source")
            scene.native[curate.state_key(state)] = {"boxes": [], "blockedFluidFaces": [], "canHoldAnyFluid": True}
            with self.assertRaisesRegex(curate.Rejected, "water-containment"):
                curate.fluid_check(scene)

    def test_forge_lava_requires_real_noncombustible_bank(self):
        scene = self.basin(); scene.set((4, 2, 4), "minecraft:lava[level=0]", "source-forge")
        self.assertEqual(1, curate.heat_check(scene)["lavaCells"])
        scene.set((5, 2, 4), "minecraft:oak_planks", "unsafe-source-bank")
        with self.assertRaisesRegex(curate.Rejected, "lava-hearth-containment"):
            curate.heat_check(scene)


class CatalogAssemblyTest(unittest.TestCase):
    def fixture(self, root):
        inventory = [{"id": "pack/acacia/house1", "sha256": "1" * 64}, {"id": "pack/acacia/unsafe", "sha256": "2" * 64}]
        inventory_path = root / "inventory.jsonl"
        inventory_path.write_bytes(b"".join(assemble.canonical(row) for row in inventory))
        identifier = "civs/v2/acacia/house/" + "a" * 20 + "/level1"
        row = {"sourceId": inventory[0]["id"], "sourceSha256": "1" * 64, "convertedSha256": "3" * 64,
               "schematicId": identifier, "sha256": assemble.digest(b"synthetic-schematic"), "style": "acacia", "family": "house",
               "type": "HOUSE", "level": 1, "maxSupportedLevel": 1, "referenceLevel": 1, "revision": "a" * 20,
               "size": [3, 4, 5], "origin": [1, 0, 5], "control": [2, 1, 4], "verdict": "READY", "reasons": []}
        rejected = {"sourceId": inventory[1]["id"], "sourceSha256": "2" * 64, "type": "DECORATION", "style": "acacia",
                    "level": 1, "verdict": "REJECTED", "reasons": ["unsafe-test-source"]}
        batch = root / "batch"
        path = assemble.payload_path(batch, identifier); path.parent.mkdir(parents=True); path.write_bytes(b"synthetic-schematic")
        receipt = batch / "receipts" / (identifier.removeprefix("civs/v2/") + ".json")
        receipt.parent.mkdir(parents=True); receipt.write_bytes(assemble.canonical({"entry": row}))
        doc = {"schemaVersion": 2, "recipeVersion": 2, "recipeSha256": "f" * 64, "recipeInputs": {},
               "placementPlanSha256": "4" * 64, "registrySha256": "5" * 64, "nativeRotationsSha256": "6" * 64,
               "sourceInventorySha256": assemble.digest(inventory_path.read_bytes()), "entries": [row, rejected]}
        doc["sourceRepositories"] = {}
        (batch / "civs/v2/catalog.json").write_bytes(assemble.canonical(doc))
        return inventory_path, batch, doc

    def test_manifest_is_allowlist_and_orphan_is_never_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); inventory, batch, _ = self.fixture(root)
            (batch / "civs/v2/orphan.schem").write_bytes(b"unaccepted")
            report = assemble.assemble([batch], inventory, root / "final", expected_sources=2)
            self.assertEqual(1, report["ignoredOrphanPayloads"][str(batch)])
            self.assertEqual(1, len(list((root / "final").rglob("*.schem"))))
            with self.assertRaisesRegex(ValueError, "fresh path"):
                assemble.assemble([batch], inventory, root / "final", expected_sources=2)

    def test_modified_payload_and_duplicate_source_fail_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); inventory, batch, doc = self.fixture(root)
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                assemble.assemble([batch, batch], inventory, root / "duplicate", expected_sources=2)
            assemble.payload_path(batch, doc["entries"][0]["schematicId"]).write_bytes(b"rewritten")
            with self.assertRaisesRegex(ValueError, "hash differs"):
                assemble.assemble([batch], inventory, root / "final", expected_sources=2)
            self.assertFalse((root / "final").exists())

    def test_dining_overlay_updates_matching_receipt_and_never_rewrites_an_accepted_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); inventory, batch, doc = self.fixture(root)
            row = doc["entries"][0]
            row.update(type="TAVERN", worksite={"type": "TAVERN"}, dining=[])
            inputs = {key: "a" * 64 for key in ("scriptSha256", "nativeFactsSha256", "registrySha256", "normalizerSha256", "rotationsSha256")}
            doc["recipeInputs"] = inputs
            catalog = batch / "civs/v2/catalog.json"; catalog.write_bytes(assemble.canonical(doc))
            receipt = batch / "receipts" / (row["schematicId"].removeprefix("civs/v2/") + ".json")
            receipt.write_bytes(assemble.canonical({"entry": row}))
            overlay = {"schemaVersion": 1, "kind": "tavern-dining", "sourceCatalogSha256": assemble.digest(catalog.read_bytes()),
                       **inputs, "curationScriptSha256": inputs["scriptSha256"], "nativeRuntimeAcceptance": False,
                       "entries": [{"schematicId": row["schematicId"], "schematicSha256": row["sha256"],
                                    "sourceId": row["sourceId"], "sourceSha256": row["sourceSha256"], "seats": [{"seat": [1, 1, 2]}]}]}
            path = root / "dining.json"; path.write_bytes(assemble.canonical(overlay))
            final = root / "final"
            assemble.assemble([batch], inventory, final, expected_sources=2, dining=path)
            published = json.loads((final / "civs/v2/catalog.json").read_text())["entries"][0]
            self.assertEqual([[1, 1, 2]], published["dining"])
            self.assertEqual([[0, 1, -3]], published["worksite"]["tavern"]["dining"])
            self.assertEqual(b"synthetic-schematic", assemble.verified_payload(final, published)[0])
            assemble.assemble([batch], inventory, root / "same", previous=final, expected_sources=2, dining=path)
            overlay["entries"][0]["seats"][0]["seat"] = [1, 1, 3]
            path.write_bytes(assemble.canonical(overlay))
            with self.assertRaisesRegex(ValueError, "accepted identity was rewritten"):
                assemble.assemble([batch], inventory, root / "changed", previous=final, expected_sources=2, dining=path)


if __name__ == "__main__":
    unittest.main()
