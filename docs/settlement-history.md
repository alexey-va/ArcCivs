# Per-settlement history

Starting with 0.1.82, source-owned ArcCivs events with a typed city UUID are written to:

```
plugins/ArcCivs/logs/settlements/<city-UUID>/latest.log
```

For Auto2 on Spawn this is `plugins/ArcCivs/logs/settlements/8b890e82-2bff-4e27-b3f9-5932754a820b/latest.log`. Events without a city use `plugins/ArcCivs/logs/system/latest.log`. These paths are relative to the server runtime, not the local source checkout. City names are not filenames, so renaming a city does not split its history.

Records use UTC ISO timestamps, severity and the existing structured event payload. Normal city INFO records go only to the settlement file. WARN/ERROR records also produce one normal global plugin log record, retaining the original Throwable for unexpected failures. The settlement entry carries bounded cause types and stack locations; it excludes arbitrary exception messages that could contain raw SQL/item payloads. Provider startup/health logs remain global. This is a forward history: older entries already in server `logs/latest.log` and its archives are not imported.

One bounded asynchronous ArcCore worker owns all file IO. A record is captured before admission; no Bukkit state or inventory/NBT payload is deferred. Files append across restart. UTC day changes and the five-MiB threshold rotate to `yyyy-MM-dd.N.log`, preserving existing archive names. Retention is the last ten archives and at most thirty days per stream, including dormant settlements. Reading the directory gives both current and retained older history; it is not an unlimited archive.

History is best effort under queue saturation or filesystem failure. It never blocks or runs file writes on a gameplay thread. A throttled global `city_history_gap` warning exposes the affected stream and cumulative dropped/failed records; a recovery marker in the stream records its gap. Normal shutdown seals the writer after service, storage, network and native producers finish, and drains accepted history work asynchronously. A broken history directory does not change the existing SQL/native failure policy.

## Supported operational reading

Use RCNet with the actual runtime root. Read bounded new byte ranges from the settlement file and global server log; persist separate device/inode/offset cursors for both. On rotation, read any unread tail from the previous file and intervening archives before advancing. Do not reset a cursor to EOF and claim the skipped interval was audited. A scheduled monitor that previously read only the global server log must change its source to the per-city stream after this version is activated.

The same source event is no longer printed through two ArcLogging channels. When comparing older global records or reconciling a WARN present in both streams, deduplicate by timestamp, server, city, worker, cycle and event. UTC file timestamps and the server's local console timestamps use different formats; normalize time zones before matching.

Use `civs debug <system> <city-UUID> [worker-UUID]` for current owner state and `civs debug layout <city-UUID> [terrain]` for the complete asynchronous layout export. History records are evidence of operations and positions; a plan, a teleport or a status string alone does not prove successful walking, harvesting or native-client appearance.

Temp-directory regressions cover routing/global multiplicity, restart append, date/size/archive collision rotation, dormant retention, bounded overload, IO recovery and late/failed producer shutdown. Source/package checks, delivery, activation and live file observation are recorded separately in the release evidence.
