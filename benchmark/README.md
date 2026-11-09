# Performance comparison

CI performance harness for **MikuProtect vs CoreProtect Community Edition vs Prism**.

## What is measured

`BenchProbe` generates the same deterministic workload for every variant:

1. Creates a 48×48×3 stone platform, 200 hopper/chest pairs with items, and a zombie group in a fresh flat world.
2. Spawns a fixed number of TNT entities in one tick and kills the zombie group halfway through the measured window.
3. Measures 200 ticks after the burst:
   - measured task interval (avg/p50/p95/max),
   - Paper `getAverageTickTime()`,
   - Paper TPS,
   - startup time, peak RSS and storage footprint under `plugins/`;
4. Runs the plugin's own lookup and rollback commands and records console-output quiet time. These are wall-clock adapter measurements, not normalized API benchmarks.

Every run has a fresh server directory, world and database. The probe is loaded in every
variant, including the baseline.

## Variants

| Variant | Plugins |
|---|---|
| baseline | BenchProbe only |
| mikuprotect | BenchProbe + MikuProtect |
| coreprotect | BenchProbe + CoreProtect 24.2-dev (26.3 auto-build) |
| prism | BenchProbe + Prism 4.5 + NBT-API 2.16.1 |

Pinned inputs:

- Paper **26.3 build 244**
- CoreProtect **24.2-dev** from [Midnight-2004/CoreProtect-AutoBuild](https://github.com/Midnight-2004/CoreProtect-AutoBuild/releases/tag/latest-build) (26.3 auto-build; dev build can change)
- Prism **4.5**
- NBT-API **2.16.1**

## CI

`.github/workflows/performance.yml` runs manually or weekly. It:

- builds the plugin,
- builds the standalone probe,
- downloads pinned server/plugin jars and verifies hashes,
- runs each variant with a fresh world,
- writes `benchmark-results/results.json` and `summary.md`,
- publishes the Markdown table in the Actions job summary and uploads the raw logs.

Manual run inputs:

- `runs`: repetitions per variant, default 2 (median reported),
- `tnt`: TNT entities per run, default 500,
- `ticks`: measured ticks, default 200.

## Local run

Build the plugin and probe first, then:

```bash
python3 benchmark/run.py \
  --paper paper.jar \
  --probe benchmark/probe/build/libs/BenchProbe.jar \
  --miku build/libs/MikuProtect-1.1.1.jar \
  --coreprotect CoreProtect-24.2-dev.jar \
  --prism prism-paper-v4.5.jar \
  --nbtapi item-nbt-api-plugin-2.16.1.jar
```

## Limits

This is a **relative** comparison of event-logging overhead:

- no network players; the bundled Mineflayer/Via experiment cannot speak the 26.3 protocol reliably in this headless CI image;
- TNT block destruction plus hopper/container and zombie-death paths, not normal player mining/placing;
- storage engines are plugin defaults (DuckDB / SQLite / H2-capable defaults), so storage size and
  write throughput are not purely listener overhead;
- query/rollback commands differ by plugin; console-invalid commands are shown as `-`, not as timings; their output and confirmation behavior is adapter-specific;
- CoreProtect 24.2-dev is an auto-build; the workflow consumes the latest-build asset, not a frozen upstream release.

Do not treat a single run as a production TPS guarantee. Use the table for regression direction,
then profile the target server with Spark.
