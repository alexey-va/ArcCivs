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
