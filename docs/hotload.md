# ArcCivs code replacement preparation

`civs reload` rereads configuration and settlement data. It does not replace
Java classes, the plugin instance or its classloader.

ArcCivs provides a console maintenance gate for an external plugin loader:

```text
civs hotload prepare
civs hotload status
```

Both commands require `arccivs.use` and `arccivs.admin` and accept only a console
sender. Each returns one `ARCCIVS_HOTLOAD` JSON line. The receipt contains the
loaded plugin `version`, overall `state`, and separate `storage`, `native` and
`history` stages. Stage states are `active`, `pending`, `complete` or `failed`.
Failures report the terminal exception type, including cancellation.

The overall states mean:

- `active`: the gameplay runtime has not been stopped.
- `preparing`: the runtime is closed and at least one terminal drain remains.
- `ready`: runtime close, storage, native retirement and history writer close
  completed successfully.
- `failed`: all terminal drains settled and at least one failed. Do not proceed
  with code replacement after this receipt.

Preparation closes the same lifecycle used by plugin disable: local native work
queue first, runtime task scope and owned resources next, then the existing
storage/native terminal barrier and city-history writer. The explicit maintenance
close may wait for detached file/SQL I/O. It never waits on Main for native work
that needs a later server tick. Repeating `prepare` preserves the original drain
and does not close resources again.

The Java plugin remains enabled during preparation. Its gameplay listeners,
sessions and runtime tasks are closed, ordinary commands return `not-ready`, and
the console can continue polling `hotload status` without the closed task scope.
There is no resume command. Actual disable clears the gameplay command executor
and tab-completer references. A subsequent enable of the same instance refuses
to start while its predecessor is pending or failed, including failed history.

## Operator sequence

1. Build and verify the replacement JAR separately; record its exact filename,
   version and hash. Keep ArcCore and its SQL, native-cleanup and packet providers
   enabled throughout the replacement.
2. Run `civs hotload prepare` on each affected server.
3. Poll `civs hotload status` until the receipt reports `ready`. A pending drain
   is not permission to unload; a failed stage requires diagnosis.
4. With the external loader's own supported commands, unload ArcCivs, replace
   its file with the verified exact JAR and load that JAR. ArcCivs neither calls
   the loader nor uses its API. Do not unload or hot-replace ArcCore.
5. Verify the newly loaded ArcCivs version, service readiness, persisted city
   data and native-client behavior. Check that old commands/listeners/tasks and
   native NPC/display handles are gone and that new interactions work.

The external loader must actually release its command registrations and old
classloader. No loader is bundled or certified by this preparation command.
In particular, PlugManX 3.2.1's `gentleUnload` registration retains the plugin and
its hook in a static `Map<Plugin, GentleUnload>`; preparation does not repair that
loader behavior.

Storage failure and native-retirement failure are separate diagnostics. The
combined readiness barrier still waits for both stages to settle.
The previously observed full-server-stop cancellation came from ArcCore's native
cleanup provider closing, and alone does not establish a SQL failure. City
history remains best-effort logging; its stage confirms writer termination,
not guaranteed delivery of every history record.

Source checks and lifecycle unit tests establish this preparation contract.
Successful replacement by a different JAR/classloader, provider continuity and
native-client acceptance require separate runtime evidence; a build or a
same-instance disable/enable test does not establish them. Full-server restart
remains the standard activation path when no suitable external loader is available.
