# Citizen skins

`src/main/resources/citizen-skins.yml` contains five fixed, signed Java player
skins for the settlement workforce. They reuse appearances already installed
on reviewed RusCrafting Citizens NPCs. No current-account lookup or new signing
request is needed. The pool is ordered; keep its keys and order stable when
preserving deterministic worker appearances.

## Selected pool

Reviewed on 2026-10-19 against the exact signed textures supplied from the
`classic` Citizens records:

| Resource key | Existing NPC | Model | Recorded catalog source and author | Selection reason |
| --- | --- | --- | --- | --- |
| `civs-citizen-1` | 349 — Мастер Эдгар | classic | [Medieval Blacksmith](https://www.minecraftskins.net/medievalblacksmith), Treaty | Dark beard, pale work shirt, burgundy leather apron and gloves; a clear adult craftsman. |
| `civs-citizen-2` | 358 — Молотобоец Доран | classic | [Blacksmith](https://www.minecraftskins.net/blacksmith), Laxbax | Dark hair and beard, muted teal work shirt and brown apron; a second craftsman with a distinct face and clothing. |
| `civs-citizen-3` | 354 — Подмастерье Лука | slim | [Medieval Boy](https://www.minecraftskins.net/medievalboy), Allykaty | Fair hair, brown work clothes and a slimmer silhouette; a younger everyday resident. |
| `civs-citizen-4` | 373 — Архивариус Эдвин | classic | [Medieval Guy](https://www.minecraftskins.net/medievalguy), deginech | Brown hair, white sleeves and an olive-brown vest; an ordinary townsman. |

The cast shares subdued leather, cloth and earth colors, with exposed human
faces. The review found no obvious modern branding, franchise costume or
recognizable creator identity. The catalog provenance was already recorded in
`ruscrafting-ops/docs/knowledge/npc-scene-origin-forge.md` and
`ruscrafting-ops/docs/knowledge/npc-scenes-origin-bank-auction.json`; the current
signed bytes came from the task's Citizens readback.

Candidate 351, Савва (`undergroundmerchant`, m0rdimort), was reviewed and omitted.
Its masked face, saturated red headgear and dark outfit made it read as a
conspicuous adventurer beside the everyday workers. Candidate 358 replaces it.

## Fixed texture identity

These are SHA-256 hashes of the downloaded PNG bytes; each also matches the
final component of its signed `textures.minecraft.net/texture/` URL:

| Resource key | PNG SHA-256 / Minecraft texture ID |
| --- | --- |
| `civs-citizen-1` | `8167c939c8629fd3aeb77dc231b017dbdcb5fa5f3f30cdc0bf97a4053868f4bf` |
| `civs-citizen-2` | `a8d8faa51d16a92cf654c4bfb1aa528c775665cd602a5a2cced11c4ab6daea29` |
| `civs-citizen-3` | `7d2b48a91b2e1f4387a45981023cf9d7da88c7cbd67d99eee4cbbf8ce0face35` |
| `civs-citizen-4` | `ff04cc9dbe3f6470527da9194a7951bac29fbbe3c5a1a7cb557927bbcc53a1d7` |

Each resource entry contains `name`, `sex`, `texture` and `signature`. The complete
base64 texture value and its matching signature are copied byte for byte from
the existing record. Arm model metadata is inside that signed value: Luka has
`textures.SKIN.metadata.model = slim`; the other three use the default classic
model. Do not decode and reserialize a texture value, strip metadata, swap a
signature, or replace it with an account name. Historical profile names inside
the signed payload describe the signing record, not the intended NPC identity.

## Review evidence and limits

All four PNGs are 64×64. Local validation parsed the YAML, checked four unique
keys, compared every texture/signature pair with its source record, decoded
all signatures to 512 bytes, and matched the PNG hashes to the signed URLs.
This is preservation and format validation, not a new cryptographic signature
verification against Mojang's public key.

The local preview uses the installed RusCrafting player-bot's Prismarine player
geometry and Three.js renderer, its local bone-pivot normalization, and the
signed classic/slim arm layout. Front, back and three-quarter views were
inspected at 275×250 pixels per figure. A second front/back render without
outer layers confirmed that the base textures remain coherent; the outer
hair and clothing layers introduce no obvious holes or misplaced faces.

Workspace evidence is in
`task-artifacts/civs-player-npcs-20261019/skins/`:

- `selected-front-back.png`, `selected-quarter.png`, `selected-base-layer.png`;
- `selected-signed-skins.json` and `selection-review.json` with source IDs,
  model metadata, texture/signature hashes and validation results;
- `preview.html`, `preview.cjs`, `build-preview.cjs` and the exact downloaded PNGs
  for reproducing the static review with the existing player-bot dependencies.

These observations establish the selected assets' static appearance in the
local renderer. They do not establish native Minecraft client appearance,
held-item placement, live hand-animation timing, delivery or runtime activation.

## Sex-aware resident selection — 2026-10-21

Resident selection now uses the persisted sex (`CivsResidents.sex`, including its deterministic legacy fallback) before applying the stable UUID index. Male and female residents no longer share one untyped pool. Existing names and persisted sex are not rewritten. Corpses reuse this same selection with the original worker.

The female pool adds existing classic Citizens 350, Счётчица Мила: [Female Wanderer](https://www.minecraftskins.net/femalewanderer) by Oblivion, using its exact signed slim texture `6dd4b61d5b7ffbe0bd5014d3655e72838be84263c6978f6cba0b79574c7e996e`. The live source was read on 2026-10-21; texture and signature were copied byte-identically. Its front and back were inspected in the same 275×250 Prismarine fixture: pale hair, a visibly feminine face, brown/burgundy cloth and leather, with coherent slim arms and outer layers.

Reproducible fixture and exact PNG: `task-artifacts/civs-resident-recovery-20261021/skins/`. This is local static skin verification; it does not establish native-client acceptance.
