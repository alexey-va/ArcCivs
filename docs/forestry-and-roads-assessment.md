# Native forestry and registered roads

The correction makes planting, real tree growth, complete harvesting and replanting the forester's actual cycle. It does not add a second output alongside autonomous timed delivery. Physical towns consume existing matching saplings and receive native drops in the worker bag through the existing receipt. Autonomous towns keep abstract stock: one completed tree earns the existing 4/8/12 WOOD by mill level, and each complete planting plot costs one abstract WOOD (one ordinary seedling or a four-seedling kit) before any world write. Plant-only work and intermediate tree batches earn no WOOD. At zero stock, a real grown-tree job may defer its one-plot debit until the same final durable completion that earns WOOD and replants. One recovery planting kit is included in a mill's already-paid level-1 construction cost (20 WOOD and 12 STONE); its use is recorded once per building before planting. Normal paid planting also consumes that one-use marker. This fixes last-WOOD planting and construction spending without a recurring grant. The 128-cell mutation receipt limit stays in place; a compact durable tree job carries the exact remaining work between batches and restarts.

For a level-2 mill, the old nominal interval was 60 seconds, doubled at hunger 6 or below. It was an upper production potential, subject to travel, pauses and stock capacity. The new actual cadence also depends on native growth, light, loaded chunks and tree size. No growth timer, bone meal or mature tree is fabricated. Stock capacity remains 128 plus active warehouses; abstract stock is not directly withdrawable as a player item or Vault balance. Planting creates an indirect native-sapling bridge that is modeled below.

The calculator uses one hypothetical mill and 1/2/4 active hours per day, with 6/24/72 completed grown trees. These are assumptions, not telemetry. A four-seedling grove starting empty needs one initial planting plus one reservation per harvested tree.

The 2026-10-28 grass-placement correction permits an existing planting operation
on short grass or a valid two-block tall-grass plant. It does not change the
per-plot WOOD cost, sapling consumption, harvest output, work duration, or native
drop rules; the removed grass adds no drop and planting does not wear the axe.
Direct changes remain zero Vault, zero tokens and zero player XP. Previously
unusable grassy sites can increase the realised rate of planting and its indirect
native-sapling flow, within the existing ideal-rate ceiling below; the actual
increase is unmeasured. The SELL figure below is the historical 2026-10-25 disk
observation, not a new live quote or a claim about current sales.

| Scenario | Previous gross WOOD potential | New completion WOOD | New seed WOOD debit | New net WOOD potential |
| --- | ---: | ---: | ---: | ---: |
| Low | 480 | 48 | 7 | 41 |
| Base | 960 | 192 | 25 | 167 |
| High | 1920 | 576 | 73 | 503 |

At levels 1/2/3, one harvest followed by its paid replant retains 3/7/11 net abstract WOOD for both ordinary and large trees. A per-individual-sapling debit was rejected before activation because it would make a four-sapling level-1 cycle net zero. Physical towns still consume four actual saplings for a 2×2 tree.

These values assume capacity is freed by normal town spending; a full stock blocks further production. They are not guaranteed daily income. Physical logs, leaf drops, saplings and sticks remain separate native item units. Each bounded cutting receipt releases only its own native drops; the final receipt never repeats earlier batches. This permits unloading during a tree larger than the resident bag. Complete crown harvesting adds real native leaf drops rather than a fixed sapling reward. Bag capacity, supply reserves and exact before-images remain authoritative. Saplings planted in the world may be accessible to players, so their creation from abstract stock is an indirect item flow. A live Spawn disk read of Decoration.yml on 2026-10-25 records nominal SELL 2 Vault for all six supported saplings. This is current disk configuration, not an observed sale or a confirmed in-memory quote.

New earth roads receive their existing Trails metadata immediately. A live Spawn read on 2026-10-25 confirmed `only-created-trails: true`, `strict-stage-order: false`, COARSE_DIRT ×1.2 and DIRT_PATH ×1.3 in the allowed `arc_civs_test` world. Registration changes metadata, not rates, player preferences or counters. Dry bottom road stairs inherit the base material's configured speed. Ordinary natural blocks stay untracked. NPCs continue to use one surface multiplier, with no additional speed stack.

With road shares of 10/25/50% and travel shares of 20/50/80%, a ×1.2 travel multiplier gives illustrative whole-cycle factors of 1.003344/1.021277/1.071429. The pure all-road, all-travel ceiling is ×1.2; stationary production quantities and intervals do not change. Actual throughput has not been measured.

Direct changes are separately zero Vault, zero tokens and zero player XP. In an alternative interception scenario, selling all 28/100/292 planted seedlings would mint 56/200/584 nominal Vault. Those plots cannot simultaneously grow the trees counted in the WOOD scenario; external existing stock must fund them. With instant interception, ready stock and no travel, the 3-second planting duration gives a theoretical upper bound of 20 four-seedling plots/minute: 80 saplings or 160 nominal Vault/minute per resident until stock runs out. The one-use recovery kit adds at most four saplings/eight nominal Vault per paid mill. The normal paid-stock table above excludes that one-off kit; using it can reduce the first-cycle stock debit by one. No new shop price, daily cap or observed income is claimed. Existing SELL remains a possible independent native-item redemption path; the planned SELL-off transition and future contracts are not assumed active. The owner requested real forestry and immediate road speed. The selected implementation keeps one payout owner and paid seedlings rather than combining native harvesting with the old timed grant.

Arithmetic input and evaluator output are in `forestry-and-roads-assessment.json` and `forestry-and-roads-assessment-result.json`. Source checks, publication, delivery, runtime activation and native tree/client observation are recorded separately.
