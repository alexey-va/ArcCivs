# Creative tool issuance and resident recovery, 0.1.39

Before, an empty creative inventory could not use the tool request button. After, an accepted creative request creates exactly one plain requested iron tool, equips it through the durable city receipt and preserves the previous held item in the bag. The request disappears when satisfied. Survival, including an operator in Survival, still needs an existing native tool and transfers its exact payload. This administrative path is not ordinary repeatable income; creative already supports native item creation.

The explicit sensitivity uses 1/2/4 creative operators and 1/4/12 requests per operator-day, not telemetry or imposed limits. Item issuance is 0→1/4/12 tools per operator-day and 0→7/56/336 tools per network-week. Vault, tokens and player XP remain separately zero; no monetary value or sale redemption is invented. The current SELL and planned future contract transition are unchanged.

The farmer now completes a bounded field scan before choosing care. Mature crops no longer wait behind the first immature patch. No-op watering is removed; exposed rain also suppresses the former age3 free growth increment. Covered or dry-weather meaningful care and optional real bone-meal fertilizing retain their existing amounts and durations. Restored harvest throughput and foregone rain bonuses are unmeasured and not combined into a guessed multiplier.

Interrupted construction retains its exact reserved row and consumed materials. Chunk availability controls retry; native protection and filled-container safety remain. Smoother movement changes waypoint distance accounting without changing work rewards. Source and automated evidence do not replace runtime or native-client observations.

Inputs: [assessment](creative-tools-and-resident-recovery-assessment.json); calculator: [result](creative-tools-and-resident-recovery-assessment-result.json).

## 0.1.40: avoid empty builder trips

Before, a new CONSTRUCTING session entered TO_STORAGE even when the existing
carried material check passed. Live automatic pickups at 22:13:24, 22:14:55 and
22:15:44 on 2026-10-21 completed without inventory mutation; their route searches
took about 5–8.5 seconds. After, a supplied builder goes directly to work; a real
shortage still requires the same physical pickup. The two-second session, at
most 64 existing rows of credit, carried-item debit and once-only receipt rules
are unchanged. Faster construction may make buildings available earlier; the
throughput change is not measured or assigned a guessed multiplier.

Direct builder-session Vault, tokens and player XP remain 0→0 in separate units.
The same rows consume the same native item quantities; this adds no item grant,
sale value, reward or offline catch-up. Current SELL and the planned contract
transition are unchanged. The new diagnostic events observe state only.

## 0.1.41: preserve field searches and recover a gravel obstruction

Before, a temporarily saving city disappeared from worksite liveness and lost
its partial field scan. After harvesting one crop, an incomplete new scan also
reported waiting for growth and started idle walking. The correction retains
the existing bounded scan across a save and holds the farmer in place until
the next target or a complete empty pass. Native crop drops, tool wear, hunger,
the 1.5-second action (3 seconds when hungry) and loaded-chunk limits stay the
same. Restored useful harvest throughput is not measured or assigned a multiplier.

The live miner remained at authored shaft step 147 because gravel refilled the
two clearance cells above its unprocessed stone floor. A saved chunk timestamped
2026-10-22 00:04:26 MSK confirms that obstruction; this is saved-world evidence,
not a live block query. Recovery clears real falling blocks through the existing
two-second native-drop action and durable receipt, leaving step 147 unchanged
until the original floor action completes. It adds no synthetic block, item
grant, drop probability, timer reward or offline catch-up.

Direct Vault, tokens and player XP remain separately 0→0. Item drops are the
unchanged native yields of actual harvested crops or removed falling blocks;
restored mining throughput and resulting profession-skill progress are unmeasured.
Current SELL and the planned contract transition remain unchanged.

Productive travel is reduced from 0.215 to 0.172 blocks per tick before the
unchanged Trails surface multiplier: 4.3→3.44 blocks/second at 20 TPS. The pure
travel leg therefore takes 1.25 times as long over an unchanged path and surface;
this is not a claim about total production throughput. Work durations and item
quantities stay unchanged. Idle walking and combat retain their previous speeds.
Only real productive footsteps wear paths; leisure remains excluded. Correcting
accumulated yaw changes the turn direction, with no work reward or item grant.

## 0.1.42: prioritize harvest and remove idle handoffs

Before, per-worker field stripes and an unchanged cached care plan could leave
ripe wheat behind watering. Completing any action discarded the discovered
field, and one-second resident admissions delayed arrival and the next action.
The replacement keeps bounded observations of the complete authored field,
reserves actual targets across farmers, and selects a live ripe crop before
care. A short field-only admission handles existing actors every five ticks;
physical travel, the configured 1.5-second harvest (3 seconds when hungry),
loaded-chunk checks and the durable item/world receipt still apply.

The observed pre-change 15-minute sample contained ten watering and five
field-work commits, with 4–72 seconds between commits (median 12). Those logs
did not record crop ages, so the counts do not establish how many ripe crops
were available or a measured production multiplier. Native crop yields,
growth increments, seed debits, hunger, tool wear and internal training per
accepted harvest remain unchanged. Faster useful work may increase their
realized rate; that rate and resulting sale income remain unmeasured.

Travel stays at the existing 0.172 blocks/tick before the Trails multiplier.
Bounded corner smoothing and gradual facing remove unnecessary turns and
detours without changing work rewards. Direct Vault, tokens and player XP
remain separately 0→0. Existing SELL and the planned contract transition are
unchanged; no item-to-currency rate or synthetic resource grant is introduced.
Source tests, delivered artifacts and observed runtime behavior are separate
evidence layers.

### Versioned shaft and ordered mine levels

The V2 plan turns the existing preview's 3×3 shaft into actual excavation,
then completes each level's forward corridor, left arm and right arm in order.
Relative to the same finished legacy mine, levels 1/2/3 require 21/35/49 extra
native terrain cells across all three supported styles. A sequential upgrade
adds 21, then 14, then 14 cells; these are physical volumes, not guaranteed item
drops. Already empty cells yield nothing. The two-second mining action, native
drop rules, 53 floor cells per level, six-block ladder depth per level and paid
support materials are retained. Cave sealing remains conditional on actual
world geometry and paid cobblestone.

Resident profession training remains one point per accepted physical action,
capped at 40 per resident and profession. A fully worked mine already reaches
that cap before and after this change. Preserving completed work also removes
its former repeat-action training; extra real excavation and cave repairs depend
on the world. This internal resident skill is separate from player XP.

Retaining the seven authored torch positions per floor avoids deleting and
repaying old torches during upgrades. Cumulative torch bag debits through
levels 1/2/3 change from 7/21/42 to 7/14/21 for the authored pattern; stolen or
missing torches and exposed caves are outside that fixed-pattern calculation.
The mutually exclusive low/base/high sensitivity assumes target levels 1/2/3,
1/10/16 owners and 0.1/0.25/1 completed target mine per owner-day. Extra physical
excavation is 2.1/8.75/49 cells per owner-day and 14.7/612.5/5488 network cells
per week. Corresponding torch debits change from 0.7/5.25/42 to 0.7/3.5/21 per
owner-day. These are assumptions, not measured rates or imposed limits.

Vault, tokens and player XP remain separately 0→0. Existing SELL and the planned
contract transition remain unchanged; neither terrain nor torch units receive
a guessed currency conversion. The [input](farm-motion-and-mine-plan-assessment.json)
and [calculator result](farm-motion-and-mine-plan-assessment-result.json) preserve
the separate units and sensitivity assumptions.

The new plan stores its version and cursor separately from the retained legacy
ordinal. Old saves are reconciled against loaded world blocks without resetting
the world or granting skipped-step rewards. Once a mine has started V2, a
rollback to 0.1.41 or earlier must first pause that quarry: those versions do
not understand the new cursor and can reseal the widened shaft using their old
inner-wall plan. Retaining the old ordinal supports audit and decoding; it is
not permission to run the old mining algorithm on a migrated shaft.
