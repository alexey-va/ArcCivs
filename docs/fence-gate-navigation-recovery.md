# Fence gate navigation recovery

Auto's builder repeatedly exhausted the route search on the TAVERN L2 order
`9e1b7c8a-0588-4188-afa0-a8212eb1270c`. From 18:40 UTC on 24 October 2026,
worker `2ad3e1a5-567f-455f-be79-31dcba078ad8`, cycle 184, remained at
(-430.8405243461768, 79, -502.5), targeting (-410.5, 78, -488.5).
Repeated attempts captured 31,305 blocks and exhausted 4,096 nodes without
walking. Detached CPU was about 105–142 ms; the complete attempt took 20–45 s.
Those timings include the bounded native capture and scheduling pipeline.

The tavern has closed oak fence gates at (-413, 78, -482) and
(-409, 78, -482). The existing passage classification supported doors but
treated these gates as ordinary 1.5-block collision barriers. Local free
standing space beside the barrel did not establish a route into the room.

A bounded saved Anvil snapshot was read without changing the live world.
Its 891-cell overlap matched the fresh native terrain capture exactly.
Reconstruction of all 20,800 sampled block states on the same Purpur build
retained every state and captured native collision shapes in one main-thread
task. With the original compiled 0.1.74 solver and city policies, changing only
the western gate to its open collision state produced a route to the original
target: 44 steps, 1,004 visited nodes and 6,823 captured cells. The unchanged
closed-gate room was disconnected within the captured area. This is a bounded
geometry replay, not native-client visual acceptance or a claim about unknown
terrain beyond the snapshot.

The correction extends the existing door passage and opening path to typed
fence gates. Trapdoors keep their existing collision behavior. The shared
construction state guard tolerates only the open bit of a same-material gate;
facing, in-wall and powered states remain strict. Search budgets, road costs,
standing-side selection, permission checks and construction receipts are not
relaxed.

A refused passage now has its own `passage-blocked` reason. Previously a
cancelled opening or active world-write lock was reported as `path-changed`,
which could enter generic teleport recovery after replans. Passage refusal
keeps the existing later retry but is excluded from teleport recovery. This
preserves the cancellation and receipt lock for both doors and fence gates.

No resource price, storage cap, output amount or item issuance rule changes.
Existing paid orders retain their saved plans and costs. Direct Vault, token
and player XP changes are separately zero. Restored travel and construction
are recovery of the existing flow, not a new nominal payout rate.

Source checks, exact-commit CI, delivery, activation and observed walking/work
are recorded separately in the operations report. Replay inputs and native
fixture evidence are in `artifacts/arccivs-075-*` in the shared workspace.
