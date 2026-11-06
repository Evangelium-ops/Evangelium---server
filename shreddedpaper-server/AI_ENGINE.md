# AI configuration and diagnostics

The `ai-engine` section of `shreddedpaper.yml` uses the existing configuration loader:

```yaml
ai-engine:
  enabled: true
  goal-optimization: true
  spatial-optimization: true
  navigation-optimization: true
  brain-optimization: true
  profiling-enabled: false
  profiling-interval-ticks: 20
```

Each flag covers:

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

Only implemented optimizations have flags. Master disable selects original implementations;
missing configuration during startup also selects original implementations. Specialized AI,
Brain, sensors, physics and activation timing retain existing lifecycle behavior. There is
no asynchronous navigation or replacement scheduler in this infrastructure.

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
