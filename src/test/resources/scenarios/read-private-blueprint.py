"""Opt-in fixture adapter for the existing bounded atelier Sponge reader.

The private payload is read from the explicit root and returned only to the local
test process. Nothing is copied into the repository or a test report.
"""
from pathlib import Path
import hashlib
import json
import sys

root, atelier = (Path(value).resolve(strict=True) for value in sys.argv[1:3])
identifier, expected = sys.argv[3:5]
source = (root / f"{identifier}.schem").resolve(strict=True)
if not source.is_relative_to(root):
    raise ValueError("Scenario schematic escapes its explicit private root")
blob = source.read_bytes()
actual = hashlib.sha256(blob).hexdigest()
if actual != expected:
    raise ValueError(f"Scenario source hash mismatch: {identifier}: expected {expected}, got {actual}")
sys.path.insert(0, str(atelier))
from sponge import read_schematic

snapshot = read_schematic(blob)
if len(snapshot["cells"]) > 50_000:
    raise ValueError("Scenario blueprint exceeds the existing 50000-cell building bound")
print(json.dumps({key: snapshot[key] for key in ("size", "worldEditOffset", "cells")}))
