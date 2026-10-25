# Immortal residents and hostile mobs, 0.1.80

## Confirmed incident

On 25 October 2026, Spawn's Auto2 settlement
`8b890e82-2bff-4e27-b3f9-5932754a820b` had one surviving builder,
Flora (`06254fa5-4795-4a8e-a7c7-783f9fc90cfd`). In 0.1.79, her original farm
order `1d2c1b0d-abe9-47cc-afec-0ef23f0fe0bd` resumed from 26 to 64 of 287
batches, including 72 native block changes and measured walked work arrivals.
Subsequent pillager attacks kept her in retreat with one health point. A fresh
09:12:35–09:17:18 UTC window contained no further construction progress;
09:17:18–09:24:12 UTC likewise contained no new native construction changes.

The immortal setting prevented death but did not exclude residents from the
plugin's synthetic hostile-mob pursuit, damage and combat scheduling. These
residents are packet NPCs; a Bukkit entity-target listener alone cannot guard
the actual threat owner.

## Intended behavior

Use the existing effective immortal setting: the settlement flag must be enabled
and the owner's existing testing permission must currently allow it. Protected
residents cannot be acquired or retained by the hostile-mob pursuit loop. A
previous mob combat route is retired so normal work can resume from the current
position. New mob hits must not create injury operations or enlist nearby
protected residents into combat. Existing accepted persistence work must retain
its canonical completion boundary.

Protection must recognize all Bukkit mob sources, including a ghast firing a
large fireball, while the existing pursuit and defence machinery remains specific
to its supported monster types. A protected packet body must not consume a mob
projectile sweep ahead of an unprotected resident. Burning caused by a mob
before the setting changed must retain enough provenance to retire only that
effect; direct environmental and player-caused fire remains authoritative.

Disabling the setting or losing its permission restores ordinary threat
handling. Player and environmental injury paths retain their existing behavior.
No production quantity, cost, recruitment rule, stock, inventory, health refill,
item grant, mob removal, time change or NPC relocation is part of this fix.

## Verification boundaries

Source regressions, build/CI, exact artifact delivery, active runtime and actual
Auto2 progress are separate evidence. Runtime receipts are kept under
`artifacts/arccivs-auto2-check-20261025/` in the workspace. A resumed status or
safe teleport cannot establish a walked route or native construction progress.
Log and command observations are not native-client visual acceptance.
