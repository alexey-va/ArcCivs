#!/usr/bin/env python3
"""Check the shaded ArcCivs runtime artifact without extra Python packages."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


FORBIDDEN_CLASS_PREFIXES = (
    "org/bukkit/",
    "io/papermc/",
    "net/kyori/adventure/",
    "org/slf4j/",
    "com/sk89q/",
    "ru/arc/paper/api/",
    "ru/arc/paper/schematics/",
    "ru/arc/paper/sql/PaperSqlServiceProvider",
    "ru/arc/paper/sql/SharedPaperSqlService",
    "ru/arc/paper/redis/PaperRedisServiceProvider",
    "ru/arc/paper/redis/SharedPaperRedisService",
    "ru/arc/paper/metrics/PaperArcMetricsProvider",
    "ru/arc/paper/packet/PaperVisualPacketRuntime",
)


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


def verify(jar_path: Path) -> None:
    with zipfile.ZipFile(jar_path) as archive:
        entries = archive.namelist()
        entry_set = set(entries)
        if "plugin.yml" not in entry_set:
            raise ValueError("production JAR is missing plugin.yml")
        if "kotlin/collections/CollectionsKt.class" not in entry_set:
            raise ValueError("production JAR is missing the bundled Kotlin runtime")

        descriptor = archive.read("plugin.yml").decode("utf-8")
        descriptor_names = []
        for line in descriptor.splitlines():
            if not line or line[0].isspace():
                continue
            key, separator, value = line.partition(":")
            if separator and key.strip() == "name":
                descriptor_names.append(value.strip())
        if descriptor_names != ["ArcCivs"]:
            raise ValueError(
                "plugin.yml must declare exactly one top-level name: ArcCivs"
            )

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


def main() -> int:
    parser = argparse.ArgumentParser()
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
        verify(jar)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"path={jar}\n")
    except (OSError, UnicodeError, zipfile.BadZipFile, ValueError) as error:
        print(f"ArcCivs artifact verification failed: {error}", file=sys.stderr)
        return 1

    print(f"Verified ArcCivs production artifact: {jar}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
