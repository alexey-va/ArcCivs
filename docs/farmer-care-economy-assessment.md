# Farmer care, 0.1.36

The previous farmer skipped every immature crop after planting. Care is now a
fallback after actionable planting, tilling and harvest work in the existing
bounded plot slice. Watering uses existing irrigation, keeps farmland wet and
can advance native age 3 to 4. At age 5, optional fertilizer consumes one plain
bone meal to advance to 6. Missing fertilizer leaves watering available.
Both care actions take four seconds before hunger adjustment. Other visits may
care for the plant without changing its age. Only actual observed block changes
enter a world receipt; no equal before/after placeholder is saved.

Wheat, carrots and potatoes have seven native age increments. A watered crop
therefore needs at least six natural increments; a fertilized one at least five.
Ignoring travel, missed checkpoints and care time gives growth-limited upper
sensitivities of 7/6 and 7/5 former throughput. These are mathematical bounds,
not measured farm-output gains. They compare otherwise equally healthy irrigated
fields at the same native per-stage growth rate; moisture maintenance may also
restore normal hydration on a temporarily dry field, and external growth settings
are unmeasured. Multiple workers and restarts cannot apply an
age checkpoint twice after it has changed. Ordinary native/player bone meal
may already bypass checkpoints. Soil irrigation, native growth settings, light,
loaded chunks, daylight, storage and worker availability remain relevant.

The accompanying JSON uses explicit low/base/high assumptions: 1/10/16 cities,
1/2/12 farmers and 1/2/4 productive daylight hours daily. Twelve farmers is a
city-population stress bound across several farms, not a guaranteed layout.
Farm levels still provide 1/2/3 jobs; curated fields contain 72 or 80 cells.
Illustrative wheat-only baseline harvests are 10/80/1920 per city-day. Watering,
a half-fertilized mix, and full fertilizer respectively give upper sensitivities
11.67/102.67/2688; native drops and replanting remain unchanged. Carrot/potato
drop baskets, Fortune and sale prices are not assigned invented values.

One actual bone meal is destroyed per paid extra stage, with optional local
logistics stock of 16 per assigned farmer. Watering creates no transferable
bucket. Active care uses the existing hunger rule: at most one hunger per
active minute, with one carried bread restoring six. Compared with otherwise
idle waiting, an upper feeding sensitivity is ten bread per productive
farmer-hour. Missing food triggers existing hungry slowdown. Care yields no
profession points or hoe wear; normal harvest progression remains unchanged.

Direct Vault, tokens and player-XP deltas are zero. Current EconomyShopGUI SELL
is still a possible indirect redemption path; the planned contract-only model
is not assumed active. The read-only 168-hour audit failed with an ops tunnel
reset, so current sale/contract income and player savings are unmeasured.
No price or progression cost changes; no days-to-save estimate is invented.
No automatic ledger retry or unrelated economic change was attempted.

Accepted as a bounded, requested growth benefit with real optional fertilizer
and existing food costs. Verify priorities, the two native age checkpoints,
water-only work, physical input debit/replay, actual reach, stale growth,
protection, cancellation and absence of care training/tool damage. Source,
delivery, activation and native-client animation acceptance remain separate.

## Correction in 0.1.39

The historical no-change care visits above have been removed. A full field scan, still bounded to sixteen candidate plots per pulse, prioritizes mature crops before care. Open-sky rain suppresses all manual watering, including the free age3 increment; roofed or dry-weather meaningful care retains the previous checkpoints and fertilizer costs. No realized throughput estimate is claimed. See [the correction assessment](creative-tools-and-resident-recovery-assessment.md).
