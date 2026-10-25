#!/usr/bin/env python3
"""Install the exact private 27-file industry pack without replacing existing files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tarfile
import tempfile


PATHS = {
    f"civs/minecolonies/{style}/{family}{level}.schem"
    for style in ("nordic", "medievaloak", "caledonia")
    for family in ("education/university", "craftsmanship/masonry/crusher",
                   "craftsmanship/metallurgy/smeltery")
    for level in (1, 2, 3)
}
MAX_FILE_BYTES = 4 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_target(root, relative):
    target = root / relative
    for part in (target, *target.parents):
        require(not part.is_symlink(), f"Symlink rejected: {part}")
        if part == root:
            break
    require(root in target.parents, "Target outside schematic root")
    return target


def inventory(root):
    result = {}
    for path in sorted(root.rglob("*.schem")):
        relative = path.relative_to(root).as_posix()
        safe_target(root, relative)
        require(path.is_file(), f"Not a regular schematic: {relative}")
        if relative not in PATHS:
            result[relative] = digest(path.read_bytes())
    return result


def install(archive, manifest_path, manifest_sha256, root, apply=False):
    root = Path(root).absolute()
    require(root.exists() and root.is_dir(), "Schematic root must already exist")
    require(all(not p.is_symlink() for p in (root, *root.parents)), "Symlink root rejected")
    manifest_bytes = Path(manifest_path).read_bytes()
    require(digest(manifest_bytes) == manifest_sha256, "Manifest SHA mismatch")
    manifest = json.loads(manifest_bytes)
    require(manifest["runtimeRoot"] == str(root), "Runtime root mismatch")
    specs = manifest["files"]
    require(len(specs) == 27 and {s["path"] for s in specs} == PATHS,
            "Expected exact 27 industry paths")
    expected = {s["path"]: s for s in specs}
    require(digest(Path(archive).read_bytes()) == manifest["archiveSha256"], "Archive SHA mismatch")
    before = inventory(root)
    payloads = {}
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        require(len(members) == 27 and {m.name for m in members} == PATHS,
                "Archive must contain exact 27 regular files")
        for member in members:
            spec = expected[member.name]
            require(member.isfile() and 0 < member.size <= MAX_FILE_BYTES,
                    f"Invalid member: {member.name}")
            require(member.size == spec["bytes"], f"Size mismatch: {member.name}")
            data = source.extractfile(member).read(MAX_FILE_BYTES + 1)
            require(len(data) == spec["bytes"] and digest(data) == spec["sha256"],
                    f"Payload mismatch: {member.name}")
            target = safe_target(root, member.name)
            if target.exists():
                require(target.is_file() and target.read_bytes() == data,
                        f"Existing file conflict: {member.name}")
            payloads[member.name] = data
    missing = [name for name in sorted(payloads) if not (root / name).exists()]
    if apply:
        for name in missing:
            target = safe_target(root, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            safe_target(root, name)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".industry-", delete=False) as out:
                    temporary = Path(out.name)
                    out.write(payloads[name])
                    out.flush()
                    os.fchmod(out.fileno(), 0o644)
                    os.fsync(out.fileno())
                try:
                    os.link(temporary, target, follow_symlinks=False)
                except FileExistsError:
                    require(not target.is_symlink() and target.read_bytes() == payloads[name],
                            f"Concurrent file conflict: {name}")
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        require(all(safe_target(root, name).read_bytes() == data for name, data in payloads.items()),
                "Installed payload verification failed")
    after = inventory(root)
    require(all(after.get(name) == sha for name, sha in before.items()), "Existing schematics changed")
    return {"mode": "apply" if apply else "check", "files": 27, "newFiles": len(missing),
            "existingFiles": len(before), "existingUnchanged": True,
            "oldInventorySha256": digest(json.dumps(before, sort_keys=True).encode()),
            "manifestSha256": manifest_sha256, "archiveSha256": manifest["archiveSha256"],
            "newHashesVerified": apply, "worldPlacement": False, "activation": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("archive", "manifest", "manifest-sha256", "root"):
        parser.add_argument(f"--{flag}", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps(install(args.archive, args.manifest, args.manifest_sha256, args.root, args.apply),
                     sort_keys=True))
