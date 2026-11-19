# Operator layout snapshot

`civs debug layout <city UUID>` exports one settlement without changing its state or reading native terrain. The optional final argument `terrain` adds a height field through the existing shared native scan budget. Before sampling, it requests the survey's chunk footprint, which can load saved chunks with generation disabled. Both `arccivs.use` and `arccivs.admin` are required; console use is supported.

The asynchronous `ARCCIVS_DEBUG` receipt contains the relative plugin-data path `debug/layout/<city UUID>.json`, exact SHA-256, byte and record counts, source/completion revisions and `layout_unchanged`. Read that exact file through the supported RCNet file workflow. The file is compact UTF-8 JSON with schema1; RCNet may read it in bounded portions. Failed requests do not establish a new verified snapshot.

The document includes the saved layout frame and reservations, every building's bounds/entrance/rotation/phase and available future envelope, assignments, construction/queue, accepted infrastructure, placement-journal metadata and validated retained access routes. Existing bounded citizens/worksites/supply snapshots include their coverage and truncation markers. Missing, unreadable and unvalidated plans are explicit.

Evidence labels distinguish city records, construction drafts, retained journals and fresh native surface measurements. Retained routes and ACTIVE infrastructure records do not prove current native road connectivity. The non-atomic terrain survey reports surface heights, column availability at sampling time and the capture interval. Its `loaded_only: true` and `chunks_loaded_by_export: 0` fields are fixed output metadata. Use runtime chunk observations to measure loads from the preceding chunk request.

Exports load journals sequentially, serialize/write off Main, and use atomic file replacement. Limits: two concurrent exports globally, one per city,16MiB per file and32 managed city files. Shutdown cancels accepted work and drains its completion. The fixed managed directory rejects symlink components; callers cannot choose paths.
