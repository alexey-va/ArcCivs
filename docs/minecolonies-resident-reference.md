# MineColonies source mechanics (pinned)

All links below refer to official `ldtteam/minecolonies` source at commit [`4eb1b870b7cb2339b79f231033df5bbef27b2646`](https://github.com/ldtteam/minecolonies/commit/4eb1b870b7cb2339b79f231033df5bbef27b2646), observed as `version/main` on 2026-10-20.

- **Builder work position and actions.** The structure builder paths toward a reachable point near the active work-order location, saves it as `workFrom`, moves to a safe stand position and resets when blocked/out of range ([`EntityAIStructureBuilder.java`, lines 208–269](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/workers/builder/EntityAIStructureBuilder.java#L208-L269)). Shared work-order logic scans blueprint resource requirements in solid, weak-solid, decoration and entity passes ([`AbstractEntityAIStructureWithWorkOrder.java`, lines 241–335](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/workers/AbstractEntityAIStructureWithWorkOrder.java#L241-L335)). The builder model shows its toolbag/hat only while `isWorking(entity)`; this model does not add a special hammer-swing animation ([`MaleBuilderModel.java`, lines 55–98](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/client/model/MaleBuilderModel.java#L55-L98)).

- **Idle leisure.** On idle decision, 5% chance enters a random leisure site; missing site falls back to home or colony center ([`EntityAICitizenWander.java`, lines 36–48 and 259–290](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/minimal/EntityAICitizenWander.java#L36-L48)). At blueprint sites, it selects tagged sit/stand points and calls `SittingEntity.sitDown` for 30 seconds ([same source, lines 181–249](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/minimal/EntityAICitizenWander.java#L181-L249)). This is leisure-site behavior; the inspected class does not name a campfire or explicitly gather citizens around one.

- **Sleep.** Sleep AI transitions from home arrival to bed search and sleeping ([`EntityAISleep.java`, lines 87–95 and 114–131](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/minimal/EntityAISleep.java#L87-L95)). `trySleep` validates a bed, sets sleeping pose, stops navigation, snaps the citizen to the bed, zeroes movement and removes held items ([`CitizenSleepHandler.java`, lines 98–138](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/citizen/citizenhandlers/CitizenSleepHandler.java#L98-L138)). The sleep loop sends particles every 30 ticks and retains a TODO for sleeping noises ([`EntityAISleep.java`, lines 43–58 and 225–242](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/ai/minimal/EntityAISleep.java#L43-L58)).

- **Speech cadence and event IDs.** Active citizens run the sound handler every `TICKS_20`; random ambient speech runs in daytime for non-silent citizens ([`EntityCitizen.java`, lines 279–285, 760–770 and 913–922](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/core/entity/citizen/EntityCitizen.java#L279-L285)). Random sounds require a close colony subscriber within 10 blocks; idle-at-job selects `MISSING_EQUIPMENT`, while the enum exposes `greeting` but no `idle` or `work` IDs ([`SoundUtils.java`, lines 79–166](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/api/util/SoundUtils.java#L79-L166); [`EventType.java`, lines 6–25](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/api/sounds/EventType.java#L6-L25)). Voice selection uses gender plus `voiceProfile`, and event chances are tested as percentages ([`SoundUtils.java`, lines 271–305](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/api/util/SoundUtils.java#L271-L305)). These random ambient calls queue `length=60,repetitions=0`; the queue pops a one-shot on the same play tick, so that length is not its cooldown ([`SoundManager.java`, lines 41–73 and 87–99](https://github.com/ldtteam/minecolonies/blob/4eb1b870b7cb2339b79f231033df5bbef27b2646/src/main/java/com/minecolonies/api/sounds/SoundManager.java#L41-L73)).

## ArcCivs decisions

The useful pattern is to tie each visible action to a real state transition:
stand at a safe nearby point, show work equipment only while working, and emit
short speech only for nearby observers. ArcCivs 0.1.27 implements those principles
using Citizens and the existing durable construction/inventory operations; it
does not copy the upstream AI or introduce a second world simulation.

The eight original speech clips are runtime resource-pack content with their
asset-specific notice and pinned provenance. They are not bundled into the
plugin JAR. ArcCivs maps greeting, work and idle categories explicitly; these
are not claimed to be three corresponding upstream enum values.

Tagged leisure positions are a useful next extension: imported blueprint sit
and stand markers could let residents use benches and squares without broad
block searches. Bed discovery could similarly prefer existing beds before a
safe indoor sleep point. Neither larger import change is part of this release.
The current campfire gathering uses existing nearby lit campfires and distinct
safe standing places, with work and night shelter taking priority.
