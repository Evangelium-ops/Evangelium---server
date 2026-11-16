# AI configuration and diagnostics

The `ai-engine` section of `shreddedpaper.yml` uses the existing configuration loader:

```yaml
ai-engine:
  villager-decision-interval: 1
  profiling-enabled: false
  profiling-interval-ticks: 20
```

The optimizations below are always on:

- `goal-optimization`: locked-flag cleanup without iterators; a search of `RemoveBlockGoal`
  (zombies and turtle eggs) keeps the chunk it is in instead of looking it up per block.
- `spatial-optimization`: lazy visibility cache; nearest-entity and nearest-item sensors sort
  with each distance computed once (`DistanceSort`), in the same stable order.
- `navigation-optimization`: path reconstruction appends and reverses.
- `brain-optimization`: sensors and behaviors are walked as flat arrays in the order of the
  maps they come from. The activity check stays live per group, because a behavior can switch
  an activity during the walk, and vanilla sees that at once. `SetLookAndInteract` skips the walk when the sensor snapshot holds no mob of the wanted type and tests the
  mob type before the distance. A plugin that changes Brain's private behavior map by
  reflection without going through `addActivity` is not seen until the next rebuild.

The Brain ordering fix (sensors after memory expiry) is a correctness fix and has no flag.

Missing configuration during startup selects original implementations. Specialized AI,
Brain, sensors, physics and activation timing retain existing lifecycle behavior. There is
no asynchronous navigation; the one scheduler is the optional villager decision interval below.

Profiling samples the existing Mob.serverAiStep on its owning tick context, including
navigation, specialized AI and controls, with try/finally completion even on early return
or exception. Entity id staggers samples; the interval is clamped to 1..1200 entity ticks.
Sampling and activation can bias results: sampled elapsed wall time is not total AI CPU.
No entity/world reference, per-entity cache, queue, or worker is retained. Three counters
per registered entity type bound retention independently of entity population. Counts and
sum use LongAdder, maximum uses AtomicLong; console snapshots are approximate during
concurrent ticks and are never used for AI decisions. Counters last until server restart.
No files are recorded automatically. Disabled profiling reads only configuration before
returning, does not initialize counters, and does not inspect the entity.

Operators can run `/aiengine` for every registry key or `/aiengine minecraft:cow` for one.
The permission is `shreddedpaper.command.aiengine`, default OP. Output distinguishes native
Mob types, non-mobs and unclassified registry entries; unresolved/new types remain visible.
Metadata comes from the registry and declared EntityType generic fields, never live entity
traversal. Per-type gameplay validation remains pending even where shared optimizations
are active; coverage is not a claim that every mob behavior has been verified.

Validation: actual GoalSelector source differential traces cover 204800 ticks across 800
scenarios in enabled, master-disabled, subsystem-disabled and uninitialized states. Tests
compile the real configuration and diagnostics sources, check disabled profiling accepts a
null entity (proving it does not access entity state), and benchmark disabled calls. Native
server compilation and live profiling are separate integration checks; do not interpret
these microbenchmarks as a full-server speedup claim.

## Villager decision scheduler

`villager-decision-interval` (1 to 4, default 1, which is vanilla) lets an idle villager look for
a new behavior every N ticks instead of every tick. It is the only part of the engine that
changes timing, so it is off until an owner chooses it.

- Only `Brain.startEachNonRunningBehavior` waits. Running behaviors, sensors, memory expiry,
  movement, damage and every other mob keep every tick.
- Delay is at most N - 1 ticks. Staggering by tick count and entity id needs no queue and no scan.
- Never delayed: hurt, trading, sleeping, a hostile or a player in view, or an activity just
  changed (`Brain.decideNextTick`).
- Off when `brain-optimization` or the master switch is off. Values outside 1 to 4 are clamped.

Measured at 4 against 1 on Linux: distributed villagers 9 to 22% faster, mixed about 4 to 7%,
dense clusters flat because collisions dominate there. One run, so read these as approximate.
All 34 live checks pass at 4.

## Thread safety

- All AI code runs on the tick thread that owns the entity. There are no workers, queues or pools.
- Brain, its flat arrays and its scratch array belong to one entity. `DistanceSort` allocates per
  call. `RemoveBlockGoal`'s chunk memo lives only for one synchronous search.
- `NearestVisibleLivingEntities.mayContainType` caches per instance; the shared EMPTY instance
  returns before it writes.
- `AiDiagnostics` counters are registry sized LongAdders, read as approximate snapshots and never
  used to make decisions.
- Helper pre-sensing is disabled: sensors write live Brain memories, so they run on the owning tick.
- Async pathfinding was measured and not built: pathfinding is at most 3.3% of samples, even with
  5,000 zombies chasing a moving target through pillars.

## Testing

From `D:\skripts\launcher\tests-manual`:

- `ai_gameplay_plain.py`: 34 live checks on a plain server, engine on and off. Set
  `VILLAGER_DECISION_INTERVAL=4` to include the scheduler.
- `ai_brain_behaviors_test.py`, `ai_distance_sort_test.py`, `ai_brain_sensors_test.py`: differential
  tests against the original code.
- `ai_bench_linux.py`: matched benchmark. Use Linux. Windows rounds the main tick's wait to multiples
  of 15.6 ms. One run alone can swing 10% either way, so pool at least two.
- `AI_COVERAGE.md` from `ai_coverage_matrix.py`.

## Maintenance

- Edit `src/minecraft/java`, then run `fixupMinecraftSourcePatches` and only then
  `rebuildMinecraftSourcePatches`. Rebuilding alone drops patches for uncommitted edits.
- On a Minecraft update, recheck that `availableBehaviorsByPriority` still changes only through
  `addActivityAndRemoveMemoriesWhenStopped` and `removeAllBehaviors`; `ai_brain_behaviors_test.py`
  asserts this. A new mutator must clear `behaviorGroups`.
- Recheck that `SetLookAndInteract` and the two sorting sensors still match the fallback branches.
- A plugin that changes Brain's private behavior map by reflection is not seen until the next
  rebuild of the arrays. `ai-engine.brain-optimization: false` is the fallback.
