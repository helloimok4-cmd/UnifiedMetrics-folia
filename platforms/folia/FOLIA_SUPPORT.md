# UnifiedMetrics — Folia Platform Support

## Compatibility

| Requirement | Version |
|---|---|
| **Server** | Folia **1.21.11**+ (or Canvas 1.21.11+, any fork of Folia 1.21.11+) |
| **Java** | **21** minimum |
| **Server API** | `api-version: 1.21` |
| **Tested on** | Canvas 1.21.11-653 |

> Older Folia builds (< 1.21.4) are **not supported** — the public `Server.getRegionTPS()` API used for per-region TPS was added in Folia 1.21.4 and stabilized in 1.21.11.

---

## Collected Metrics

| Metric | Label(s) | Source | Notes |
|---|---|---|---|
| `minecraft_players_count` | — | Public API | Global online players |
| `minecraft_players_max` | — | Public API | Configured max players |
| `minecraft_plugins` | — | Public API | Loaded plugin count |
| `minecraft_world_entities_count` | `world` | Public API | Per-world entity count |
| `minecraft_world_players_count` | `world` | Public API | Per-world player count |
| `minecraft_world_loaded_chunks` | `world` | Public API | Per-world loaded chunk count |
| `minecraft_tick_duration_seconds` | — | Event (histogram) | Global tick duration; deduplicates Folia's per-region events |
| `minecraft_region_tps` | `world`, `region` | Public API (`getRegionTPS`) | Per-region TPS, 5 s average |
| `minecraft_region_mspt_seconds` | `world`, `region` | **NMS reflection** | Per-region MSPT in seconds, 5 s average. May be unavailable (see below) |
| `minecraft_events_login` | — | Event counter | Async login attempts |
| `minecraft_events_join` | — | Event counter | Player joins |
| `minecraft_events_quit` | — | Event counter | Player quits |
| `minecraft_events_chat` | — | Event counter | Chat messages |
| `minecraft_events_ping` | — | Event counter | Server list pings |

JVM metrics (GC, memory, threads, CPU) are provided by the common module and are independent of the platform.

---

## Per-Region Metrics — How It Works

### TPS
Uses the **public Folia API** `Server.getRegionTPS(world, chunkX, chunkZ)` — stable, no reflection, no breakage risk.

### MSPT
Uses **NMS reflection** through this chain:
```
ServerLevel.regioniser
  → ThreadedRegionizer.getRegionAtSynchronised(x, z)
    → ThreadedRegion.getData()
      → TickRegionData.getRegionSchedulingHandle()
        → RegionScheduleHandle.getTickReport5s(nanoTime)
          → TickReportData.timePerTickData().segmentAll().average()
```
If **any step fails** (obfuscation, API change, fork difference), MSPT is silently omitted — TPS still works. The startup log will say:
- `MSPT=reflection-ok` — full data available
- `MSPT=unavailable` — MSPT disabled, TPS still collected

### Region enumeration
Two tiers (auto-selected at startup):

| Tier | Method | Complexity | When used |
|---|---|---|---|
| **Fast** | Read `ThreadedRegionizer.regions` map directly | O(regions) | When field is accessible (standard Folia / Canvas) |
| **Fallback** | Iterate `world.loadedChunks`, deduplicate by `region.id` | O(chunks) | If `regions` field is renamed/removed in a fork |

The startup log will say:
- `enumeration=direct-map(O(regions))` — fast path active
- `enumeration=loadedChunks-fallback(O(chunks))` — fallback active

Update interval is **every 5 seconds** (100 ticks) — matching the 5 s averaging window of the underlying TPS/MSPT data.

---

## Known Issues & Limitations

### 1. `minecraft_region_mspt_seconds` may be absent
**Cause:** NMS reflection chain depends on internal Folia class structure. Canvas or forked builds may rename internal classes/fields.  
**Impact:** Only MSPT is missing; TPS and all other metrics still work.  
**Diagnosis:** Check startup log for `MSPT=unavailable`.

### 2. Region profiling unavailable on startup
**Symptom:** `[UnifiedMetrics] Region profiling unavailable: <message>` in server log  
**Possible causes:**

| Log message | Meaning |
|---|---|
| `ServerLevel.regioniser field not found` | Fork renamed or removed the `regioniser` field |
| `getRegionAtSynchronised not found` | Fork renamed the method |
| `ThreadedRegion id field not found` | Fork removed/renamed the region ID field |
| `ChunkPos.x/z field not found` | Obfuscated build without the `x`/`z` public fields |
| `Array contains no element…` | Should no longer occur after the fix in this build |

**Fix:** Open an issue with the full startup log and your server jar version.

### 3. Chunk-fallback may skip a world on very large servers
**Symptom:** `[UnifiedMetrics] World 'X' has N loaded chunks (> 50000). Region profiling skipped for this world.`  
**Cause:** Fallback chunk iteration is capped at 50 000 chunks/world to prevent tick lag.  
**Fix:** This only fires if the fast-path (`regions` field) is unavailable. On standard Folia/Canvas, the fast path is used and there is no chunk limit.

### 4. `minecraft_tick_duration_seconds` represents one region's tick, not a true global tick
**Cause:** Folia fires `ServerTickEndEvent` once per region. The collector deduplicates using a 50 ms time window and records the **first** event per window — this typically comes from the most active region.  
**Impact:** The histogram is an approximation. Use `minecraft_region_mspt_seconds` for per-region precision.

### 5. Requires Java 21
Folia 1.21.11 mandates Java 21. The plugin JAR will refuse to load on Java 17 or earlier.

### 6. `kotlinx-coroutines` conflict with the server classloader
Paper/Canvas 1.21.11 bundles its own `kotlinx-coroutines`. Without relocation, a version mismatch causes `NoClassDefFoundError: JobCancellationException` on disable.  
**Status: Fixed** — `kotlinx.coroutines` and `kotlinx.serialization` are relocated into the plugin's own package namespace in the shadow JAR.

---

## Grafana Dashboards

Two dashboards are provided in `grafana/`:

| File | Description |
|---|---|
| `folia-native-dashboard.json` | **Recommended** — full standalone Folia/Canvas dashboard (36 panels, 6 sections) |
| `unifiedmetrics-folia.json` | Original UnifiedMetrics dashboard (14756) with Folia region profiling section injected |

Import either directly into Grafana. Required variables: `$datasource` (Prometheus), `$job`, `$instance`, `$interval`, `$world`.

---

## Building from Source

```bash
./gradlew :unifiedmetrics-platform-folia:shadowJar
# Output: platforms/folia/build/libs/unifiedmetrics-platform-folia-<version>.jar
```

Requires JDK 21 and the `dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT` artifact in your local Maven repository (published by Folia's build pipeline or a Canvas build).
