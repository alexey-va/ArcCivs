# Full-storage idle movement and door interaction recovery

Auto's 24 October 2026 live snapshot exposed two independent stalls. Six producers
had `storage-full`, `PAUSED` and no route, with three pairs occupying identical
positions at the shared depot. Abstract stocks were wood 384, stone 380 and food
384. The existing L1 warehouse gives capacity 384 for each resource; the next L2
quarry delivery of six stone cannot fit in the four remaining places. Native
worker bags were mostly empty: abstract stock capacity is distinct from chest or
resident inventory capacity.

The full-storage wait bypassed the normal idle walker. Reuse the existing idle
route while capacity remains unavailable, and retire that route before resuming
the authentic delivery/work destination. Preserve completed outputs and their
receipt; a leisure walk must neither produce resources nor deliver them remotely.
Ordinary idle walking retains its existing speed and creates no Trails wear.

Courier sources must be stationary. Moving actors are excluded from source
offers; an already accepted cached rendezvous prevents a new leisure route
before the courier's normal hold begins. Existing plan release removes that
restriction, without adding another reservation system. The physical-output
test retains its chosen work plan; unrelated worksite cache invalidation on
bag edits is unchanged.

The current warehouse upgrade was separately paused at 77/310 because its old
oak door had been opened. `sameConstructionState` now normalizes only `Door.open`
when actual and expected materials match. Facing, half, hinge and powered state
remain strict, as do trapdoors, material changes, filled containers and current
Lands authorization. The existing stair-shape and fence-connection exceptions
remain unchanged. Both native preflight and final mutation use the same helper.

Recover the existing paid upgrade with the supported console retry only after
the corrected runtime is active and the same paused order is verified. Preserve
its ID, 128 wood/64 stone cost, plan and committed batches. Do not create another
order or issue resources. Verify subsequent native batches and the ACTIVE L2
warehouse separately from the command acknowledgement.

No price, output, population, infrastructure or stock-cap rule changes. Existing
warehouse capacity is 128 base plus 256/512/1024 for each ACTIVE L1/L2/L3 warehouse;
completing the original L2 upgrade therefore changes this town's capacity from
384 to 640 under the pre-existing rule. Idle waiting itself creates no resources,
native items, Vault, tokens or player XP. Restored throughput is observed after
activation rather than inferred from a source test or converted to sale income.
Current SELL and the planned SELL-to-contract transition remain unchanged.

Acceptance requires genuine idle displacement after a full-storage pause and
genuine work/delivery after capacity returns. A teleport or one debug sample is
insufficient; retain independent source, CI, delivery, activation and live-log
evidence.
