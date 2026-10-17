# ArcCivs

Paper settlement gameplay built on public arc-core modules. Keep all core
coordinates aligned with arc-core-consumer.toml. ArcCore owns shared service
contracts and schematic loading; never shade ru.arc.paper.api or its providers.

Domain rules live in CivsRules and immutable CivsModel types. The city store
commits asynchronously before publishing state or mutating the world. World
placement is bounded and restart-recoverable; NPCs exist only near viewers.
Do not access Bukkit state from worker threads or retain decoded schematics
after a preview/build finishes. See ARCHITECTURE.md for the player contract.

Run ./gradlew test shadowJar and the central consumer verifier. New tests must
use arc-core-paper-testing for Paper fixtures. Production activation and native
client acceptance remain separate from source/package verification.
