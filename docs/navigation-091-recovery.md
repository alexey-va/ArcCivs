# Navigation recovery in 0.1.91

The 0.1.90 Spawn audit of Auto2 found two unfinished work episodes. Regina
(`679ad6aa-2a43-4c17-89ba-16589232fed3`) remained on the ordinary street at
`(-1866.25,64,-2757.5)`: 19 unique failures in 07:30–07:49 UTC on 26 October,
one captured node per attempt, zero work arrival or native mine progress.
The completed-mine-prefix restriction incorrectly applied to her outdoor leg
below the raised quarry's first floor. More search budget cannot open that edge.

Outdoor miners now walk to the authored entrance with ordinary city navigation
before a shaft/gallery leg applies its captured completed-prefix restriction.
Building bounds and completed passages classify the current position; future
mine volume alone does not. Inner legs still reject unbuilt galleries and caves,
including intermediate shaft targets. Retained legacy prefixes stay usable.
The existing arrival contract can finish that approach early when actual feet
already have support on a completed mine stance with real line of sight/reach.
It rechecks those conditions before native work; the waypoint alone earns nothing.

Egor (`da33dc2d-c343-48c7-9b8d-10cda594970e`) completed one real tree, then
repeated the next approach after an oak trunk occupied its cached standing point
`(-1884,64,-2685)`. Forestry now revalidates cached block support, body clearance
and reach. A proven obstruction or final-route failure excludes that temporary
stance and selects another approach to the same tree. The existing bounded
exclusion is keyed by building, worker cycle and tree root. Temporary chunk
unavailability retains the work; persistence pauses keep accepted batches fixed.
Neither reselection nor route failure changes paid jobs, world blocks or items.

Regression coverage includes two outdoor origins (one within future-only shaft
volume), bounded per-tick movement through the real entrance, forbidden future
inner cells, paid/unpaid forestry obstruction, chunk unload/reload, repeated
stance exclusion, pre-route rejection and exactly one completed tree receipt.

Search/capture budgets, prices, quantities, stock caps and receipt rules are
unchanged. The [balance input](navigation-091-assessment.json) and
[checked calculation](navigation-091-assessment-result.json) model hypothetical
restored L1 completion rates of 6/24/72 cycles per village-day. Net WOOD and
STONE are each 18/72/216 units; the two worker streams separately grant a total
12/48/144 profession XP. These are sensitivities, not measured throughput.
Direct Vault, tokens, player XP and generated transferable items remain zero.

Source tests, CI artifact, delivered bytes, active version and observed live
recovery must be recorded separately. Auto2 has abstract inventory and no
physical courier: its stock credits cannot demonstrate moving-courier pickup.
