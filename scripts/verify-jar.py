#!/usr/bin/env python3
"""Check the shaded ArcCivs runtime artifact without extra Python packages."""

from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from pathlib import Path


FORBIDDEN_CLASS_PREFIXES = (
    "org/bukkit/",
    "io/papermc/",
    "net/kyori/adventure/",
    "org/slf4j/",
    "com/sk89q/",
    "net/citizensnpcs/",
    "ru/ruscrafting/trails/api/",
    "ru/arc/paper/api/",
    "ru/arc/paper/schematics/",
    "ru/arc/paper/sql/PaperSqlServiceProvider",
    "ru/arc/paper/sql/SharedPaperSqlService",
    "ru/arc/paper/redis/PaperRedisServiceProvider",
    "ru/arc/paper/redis/SharedPaperRedisService",
    "ru/arc/paper/metrics/PaperArcMetricsProvider",
    "ru/arc/paper/packet/PaperVisualPacketRuntime",
    "ru/arc/paper/runtime/PaperNativeCleanupServiceProvider",
)

# Inspect the actual feature and the Core preview transport it constructs. Other
# bundled Core helpers can serve optional ARC-only features that ArcCivs never uses.
HOST_API_CONSUMERS = ("ru/arc/civs/", "ru/arc/paper/display/", "ru/arc/paper/npc/", "ru/arc/paper/packet/PaperVisualPackets")
PREVIEW_CLASSES = (
    "ru/arc/civs/CivsPreviewRenderer.class",
    "ru/arc/paper/display/PaperPacketDisplays.class",
    "ru/arc/paper/packet/PaperVisualPackets.class",
)
HOST_VISUAL_RUNTIME = "ru/arc/paper/packet/PaperVisualPacketRuntime.class"
API_REFERENCE = re.compile(rb"ru/arc/paper/api/[A-Za-z0-9_$]+")


def api_references(bytecode: bytes) -> set[str]:
    """Read JVM constant-pool reference tokens, including field/method descriptors."""
    if bytecode[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid class file in the feature/preview linkage path")
    pool_count = struct.unpack_from(">H", bytecode, 8)[0]
    offset, index = 10, 1
    references = set()
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < pool_count:
        tag = bytecode[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", bytecode, offset)[0]
            offset += 2
            references.update(match.group().decode("ascii") + ".class"
                              for match in API_REFERENCE.finditer(bytecode[offset:offset + length]))
            offset += length
        else:
            if tag not in sizes:
                raise ValueError(f"unsupported JVM constant-pool tag {tag}")
            offset += sizes[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    return references


def descriptor_names(descriptor: str) -> list[str]:
    return [line.partition(":")[2].strip().strip("'\"") for line in descriptor.splitlines()
            if line.startswith("name:")]


def verify_host_linkage(archive: zipfile.ZipFile, host_path: Path) -> None:
    with zipfile.ZipFile(host_path) as host:
        host_entries = set(host.namelist())
        if "plugin.yml" not in host_entries or descriptor_names(host.read("plugin.yml").decode("utf-8")) != ["ArcCore"]:
            raise ValueError("host JAR must declare exactly name: ArcCore")
        for required in PREVIEW_CLASSES:
            if required not in archive.namelist():
                raise ValueError(f"preview runtime class is missing: {required}")
        missing = {}
        for name in archive.namelist():
            if name.endswith(".class") and name.startswith(HOST_API_CONSUMERS):
                for reference in api_references(archive.read(name)) - host_entries:
                    missing.setdefault(reference, name)
        if missing:
            details = "; ".join(f"{api} (referenced by {source})" for api, source in sorted(missing.items()))
            raise ValueError(f"ArcCore host API linkage is incomplete: {details}")
        if HOST_VISUAL_RUNTIME not in host_entries:
            raise ValueError(f"ArcCore host is missing the shared visual provider: {HOST_VISUAL_RUNTIME}")


def runtime_jar(project_root: Path) -> Path:
    candidates = sorted(
        path
        for path in (project_root / "build" / "libs").glob("ArcCivs-*.jar")
        if not path.name.endswith("-plain.jar")
    )
    if len(candidates) != 1:
        raise ValueError(
            "expected exactly one shaded ArcCivs-*.jar in build/libs, "
            f"found {len(candidates)}"
        )
    jar = candidates[0]
    if not jar.is_file() or jar.stat().st_size == 0:
        raise ValueError(f"production JAR is missing or empty: {jar}")
    return jar


def verify(jar_path: Path, host_path: Path) -> None:
    with zipfile.ZipFile(jar_path) as archive:
        entries = archive.namelist()
        entry_set = set(entries)
        if "plugin.yml" not in entry_set:
            raise ValueError("production JAR is missing plugin.yml")
        if "kotlin/collections/CollectionsKt.class" not in entry_set:
            raise ValueError("production JAR is missing the bundled Kotlin runtime")

        descriptor = archive.read("plugin.yml").decode("utf-8")
        if descriptor_names(descriptor) != ["ArcCivs"]:
            raise ValueError(
                "plugin.yml must declare exactly one top-level name: ArcCivs"
            )
        if "Citizens" in descriptor:
            raise ValueError("ArcCivs must not declare a Citizens dependency")
        for name in entries:
            if name.startswith("ru/arc/civs/") and name.endswith(".class"):
                if b"net/citizensnpcs/" in archive.read(name):
                    raise ValueError(f"Citizens linkage remains in {name}")

        leaked = sorted(
            name
            for name in entries
            if name.endswith(".class")
            and name.startswith(FORBIDDEN_CLASS_PREFIXES)
        )
        if leaked:
            preview = ", ".join(leaked[:8])
            suffix = " ..." if len(leaked) > 8 else ""
            raise ValueError(f"platform/Core API/provider classes were shaded: {preview}{suffix}")
        verify_host_linkage(archive, host_path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--arc-core-jar",
        type=Path,
        default=Path("build/verification/ArcCore.jar"),
        help="exact mandatory host JAR (prepared by shadowJar; never use the test classpath)",
    )
    parser.add_argument(
        "--jar",
        type=Path,
        help="explicit production JAR path (defaults to the unique build/libs artifact)",
    )
    parser.add_argument(
        "--github-output",
        type=Path,
        help="append the verified artifact path to a GitHub Actions output file",
    )
    args = parser.parse_args()

    try:
        jar = args.jar.resolve() if args.jar else runtime_jar(Path.cwd())
        verify(jar, args.arc_core_jar.resolve())
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"path={jar}\n")
    except (OSError, UnicodeError, zipfile.BadZipFile, ValueError, IndexError, struct.error) as error:
        print(f"ArcCivs artifact verification failed: {error}", file=sys.stderr)
        return 1

    print(f"Verified ArcCivs production artifact: {jar}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
