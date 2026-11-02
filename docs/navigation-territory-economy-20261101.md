# Navigation, territory maintenance and controls — 0.1.181

The accepted change removes route-preparation stalls, clears natural trees inside autonomous settlement interiors and relocates functional controls to reachable interiors. This is an operational repair. It changes no production recipe, probability, output quantity, worker skill award or existing paid work terms.

Direct before/after amounts remain separately 0 Vault, 0 premium tokens and 0 player XP. The evaluator uses explicit sensitivity inputs, not measured income. Natural cleanup creates no drops, saplings, inventory credits or Stock. It preserves authored structure cells, productive forester groves and accepted forestry cells. For every transferred native item and its metadata, relocation preserves the exact amount and slot receipt; an empty replacement barrel has the same 27-slot capacity. Admin gate refit has no item reward or refund. Future flat-gate material costs remain separately assessed in gate-refit-economy-20261101.json.

Faster navigation may restore legitimate output per hour. Its live magnitude is unknown before activation; the old planner baseline mean is about9.16s and max236.83s across admitted jobs, not a production measurement. Keep native route latency and ordinary paid construction/cargo commits separate from a money-income claim. Existing global supply extraction caps and per-action resource/food costs remain in force. Current SELL and planned SELL-to-contract are separate flows; no item price or savings estimate is assumed.

Release decision: deliver the requested repair, preserve exact inventory conservation and existing productive groves, and observe ordinary movement and commits after activation. Source/tests, delivered artifact, active version and client acceptance are recorded separately in the rollout receipt.

## Flat support consistency in185

Detached search now checks the same crossed support columns as native flat movement.
On an exact891-cell stair capture the old path failed its first native step; the
corrected local route arrives in10ticks. No physics, collision, arrival, credit,
recipe or yield rule is relaxed. Direct Vault, premium-token and player-XP deltas
remain separately zero; actual full-trip throughput and downstream SELL income
are unmeasured. Native full-route and deposit acceptance remain separate.

186: новая бочка над авторской нижней полуплитой получает опору из такой же двойной плиты через существующий журнал pendingControls. Добавляется одна конечная половина плиты в мире; предметы, дроп, Stock, Vault, жетоны и XP не выдаются. Соседний пол и правила физики NPC не меняются; существующие слоты и NBT переносятся прежним журналом.

187: принятому апгрейду не мешает повторный перенос контроля. Регистрация снимается только с точно проверенных изменяемых исходных контейнеров после сохранённой эвакуации предметов; пустой источник требует такого же строгого подтверждения. Повторная проверка состояния, прав и пустых слотов выполняется после native fence, до публикации реестра. Предметы и NBT сохраняются, вместимость не конвертируется в валюту. Заявка на стену ждёт окончание непринятого поиска; полный Native-счёт остаётся26 STONE_BRICKS+2 DARK_OAK_FENCE, без бесплатной выдачи и без изменения стоимости партии.

188: согласованы допуск назначения и подбор уже существующего булыжника для принятого ремонта в ACTIVE доме строителей без строительного заказа. Существующий лимит доставки16, вместимость, предметы/NBT и все выходы действий сохраняются; новые материалы или валюты не создаются. Явная заявка на ограниченный WALL добавляется в обычный FIFO, а приоритет ремонта сохраняет проверка запуска очереди. Оплата полного Native-счёта26 STONE_BRICKS+2 DARK_OAK_FENCE остаётся штатной.
