/*
 *     This file is part of UnifiedMetrics.
 *
 *     UnifiedMetrics is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     UnifiedMetrics is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with UnifiedMetrics.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.cubxity.plugins.metrics.folia.metric.region

import dev.cubxity.plugins.metrics.api.metric.collector.Collector
import dev.cubxity.plugins.metrics.api.metric.collector.CollectorCollection
import dev.cubxity.plugins.metrics.api.metric.data.GaugeMetric
import dev.cubxity.plugins.metrics.api.metric.data.Metric
import dev.cubxity.plugins.metrics.folia.bootstrap.UnifiedMetricsFoliaBootstrap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.World
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Per-region TPS and MSPT collector for Folia / Canvas.
 *
 * TPS source:
 *   Public API (Folia 1.21.11+): `Server.getRegionTPS(world, chunkX, chunkZ)[0]`
 *
 * MSPT source (reflection, graceful fallback):
 *   ServerLevel.regioniser → ThreadedRegionizer.getRegionAtSynchronised
 *     → ThreadedRegion.getData → getRegionSchedulingHandle
 *        → getTickReport5s → timePerTickData → segmentAll → average (nanoseconds)
 *
 * Region enumeration — two tiers:
 *   Fast path (O(regions)): reflect `ThreadedRegionizer.regions` map, iterate values
 *     under `synchronized(regionizer)`. Available on all known Folia forks.
 *   Fallback (O(chunks)):  iterate `world.loadedChunks`, deduplicate by region.id.
 *     Guarded at MAX_CHUNKS_PER_WORLD to prevent excessive overhead on huge worlds.
 *
 * Update interval: every 100 ticks (5 s) — matches the 5 s averaging window of the
 * underlying TPS/MSPT data, so updating faster would not add any precision.
 *
 * Metrics emitted (labels: `world`, `region` = "centerChunkX,centerChunkZ"):
 *   minecraft_region_tps              — TPS  (5 s average)
 *   minecraft_region_mspt_seconds     — MSPT in seconds (5 s average), omitted if unavailable
 */
private const val MAX_CHUNKS_PER_WORLD = 50_000
private const val UPDATE_INTERVAL_TICKS = 100L  // 5 seconds
class FoliaRegionCollector(private val bootstrap: UnifiedMetricsFoliaBootstrap) : CollectorCollection {

    // ── Reflection handles (loaded once on first snapshot) ────────────────────

    private val handles: Handles? by lazy { tryLoadHandles() }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var snapshots: List<RegionSnapshot> = emptyList()
    private var task: ScheduledTask? = null
    /** Worlds where chunk-fallback was skipped due to exceeding MAX_CHUNKS_PER_WORLD — log once only. */
    private val oversizedWarned = HashSet<String>()

    // ── Data class ────────────────────────────────────────────────────────────

    private data class RegionSnapshot(
        val worldName: String,
        val regionLabel: String,   // "centerChunkX,centerChunkZ"
        val tps: Double,
        val msptSeconds: Double?   // null when MSPT reflection is unavailable
    )

    // ── Collector / CollectorCollection ───────────────────────────────────────

    private val innerCollector = object : Collector {
        override fun collect(): List<Metric> {
            val current = snapshots
            if (current.isEmpty()) return emptyList()
            val out = ArrayList<Metric>(current.size * 2)
            for (snap in current) {
                val labels = mapOf("world" to snap.worldName, "region" to snap.regionLabel)
                out += GaugeMetric("minecraft_region_tps", labels, snap.tps)
                if (snap.msptSeconds != null) {
                    out += GaugeMetric("minecraft_region_mspt_seconds", labels, snap.msptSeconds)
                }
            }
            return out
        }
    }

    override val collectors: List<Collector> = listOf(innerCollector)
    override val isAsync: Boolean = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun initialize() {
        handles // trigger lazy init → log tier at startup
        task = bootstrap.server.globalRegionScheduler.runAtFixedRate(
            bootstrap, { updateSnapshots() }, 1L, UPDATE_INTERVAL_TICKS
        )
    }

    override fun dispose() {
        task?.cancel()
        task = null
    }

    // ── Snapshot update ───────────────────────────────────────────────────────

    private fun updateSnapshots() {
        val h = handles ?: return
        val next = ArrayList<RegionSnapshot>()
        val now = System.nanoTime()

        for (world in bootstrap.server.worlds) {
            val nmsWorld = h.getHandle(world) ?: continue

            // Fast path: O(regions) — enumerate directly from threadedRegionizer.regions map
            val regions = h.getAllRegionsDirect(nmsWorld)
            if (regions != null) {
                for (region in regions) {
                    collectRegion(h, world, region, now, next)
                }
                continue
            }

            // Fallback: O(chunks) — guard oversized worlds to avoid excessive overhead
            val chunks = world.loadedChunks
            if (chunks.size > MAX_CHUNKS_PER_WORLD) {
                if (oversizedWarned.add(world.name)) {
                    bootstrap.logger.warn(
                        "[UnifiedMetrics] World '${world.name}' has ${chunks.size} loaded chunks " +
                        "(> $MAX_CHUNKS_PER_WORLD). Region profiling skipped for this world. " +
                        "Consider upgrading to a Folia build that exposes ThreadedRegionizer.regions."
                    )
                }
                continue
            }
            oversizedWarned.remove(world.name)

            val seen = HashSet<Long>()
            for (chunk in chunks) {
                val region = h.getRegionAt(nmsWorld, chunk.x, chunk.z) ?: continue
                val regionId = h.getRegionId(region)
                if (!seen.add(regionId)) continue
                collectRegion(h, world, region, now, next)
            }
        }

        snapshots = next
    }

    private fun collectRegion(
        h: Handles,
        world: org.bukkit.World,
        region: Any,
        now: Long,
        out: MutableList<RegionSnapshot>
    ) {
        val (cx, cz) = h.getCenterChunk(region) ?: return
        val label = "$cx,$cz"

        val tps: Double = try {
            bootstrap.server.getRegionTPS(world, cx, cz)?.get(0) ?: return
        } catch (_: Throwable) { return }

        val msptSeconds: Double? = try {
            val schedHandle = h.getRegionSchedulingHandle(h.getData(region)) ?: return
            val report      = h.getTickReport5s(schedHandle, now) ?: return
            val nanos       = h.getTimePerTickAvg(report)
            if (nanos.isNaN()) null else nanos / 1_000_000_000.0
        } catch (_: Throwable) { null }

        out += RegionSnapshot(world.name, label, tps, msptSeconds)
    }

    // ── Reflection initialisation ─────────────────────────────────────────────

    private fun tryLoadHandles(): Handles? {
        return try {
            // 1. CraftWorld.getHandle() → ServerLevel
            val craftWorldClass = Class.forName("org.bukkit.craftbukkit.CraftWorld")
            val getHandleMethod = craftWorldClass.getMethod("getHandle")
            val serverLevelClass = getHandleMethod.returnType

            // 2. ServerLevel.regioniser → ThreadedRegionizer
            val regioniserField: Field = serverLevelClass.fields
                .firstOrNull { it.name == "regioniser" }
                ?: serverLevelClass.declaredFields
                    .firstOrNull { it.name == "regioniser" }
                    ?.also { it.isAccessible = true }
                ?: return logFail("ServerLevel.regioniser field not found")

            val regionizerClass = regioniserField.type

            // 3. getRegionAtSynchronised(int chunkX, int chunkZ)
            val getRegionAtMethod = regionizerClass.methods
                .firstOrNull { it.name == "getRegionAtSynchronised" && it.parameterCount == 2 }
                ?: return logFail("getRegionAtSynchronised not found")

            val regionClass = getRegionAtMethod.returnType

            // 3b. Optional fast-path: ThreadedRegionizer.regions — the internal map of all regions.
            //     Iterating this is O(regions) vs O(chunks) for loadedChunks enumeration.
            //     Access is guarded by synchronized(regionizer) to match Folia's own locking.
            val regionsMapField: Field? = try {
                regionizerClass.getDeclaredField("regions").also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) { null }

            // 4. ThreadedRegion.id (long) — only needed for chunk-fallback dedup; optional.
            //    Search public fields first (includes superclasses), then walk declared fields
            //    up the hierarchy, then try first long field anywhere in hierarchy.
            val idField: Field? = try {
                regionClass.getField("id")
            } catch (_: NoSuchFieldException) {
                findFieldInHierarchy(regionClass, name = "id")
                    ?: findFieldInHierarchy(regionClass, type = Long::class.javaPrimitiveType)
                    // null is OK — chunk-fallback will use hashCode() for deduplication
            }

            // 5. ThreadedRegion.getCenterChunk() → ChunkPos
            val getCenterChunkMethod = regionClass.methods
                .firstOrNull { it.name == "getCenterChunk" && it.parameterCount == 0 }
                ?: return logFail("ThreadedRegion.getCenterChunk not found")

            val chunkPosClass = getCenterChunkMethod.returnType
            val chunkPosX: Field = try { chunkPosClass.getField("x") }
                catch (_: NoSuchFieldException) {
                    chunkPosClass.declaredFields.firstOrNull { it.name == "x" && it.type == Int::class.javaPrimitiveType }
                        ?.also { it.isAccessible = true }
                        ?: return logFail("ChunkPos.x field not found")
                }
            val chunkPosZ: Field = try { chunkPosClass.getField("z") }
                catch (_: NoSuchFieldException) {
                    chunkPosClass.declaredFields.firstOrNull { it.name == "z" && it.type == Int::class.javaPrimitiveType }
                        ?.also { it.isAccessible = true }
                        ?: return logFail("ChunkPos.z field not found")
                }

            // 6. ThreadedRegion.getData() → TickRegionData
            val getDataMethod = regionClass.methods
                .firstOrNull { it.name == "getData" && it.parameterCount == 0 }
                ?: return logFail("ThreadedRegion.getData not found")

            val tickRegionDataClass = getDataMethod.returnType

            // 7. TickRegionData.getRegionSchedulingHandle() → RegionScheduleHandle
            val getSchedulingHandleMethod = tickRegionDataClass.methods
                .firstOrNull { it.name == "getRegionSchedulingHandle" && it.parameterCount == 0 }

            // 8. RegionScheduleHandle.getTickReport5s(long) → TickReportData
            val getTickReport5sMethod = getSchedulingHandleMethod?.returnType?.methods
                ?.firstOrNull { it.name == "getTickReport5s" && it.parameterCount == 1 }

            // 9. TickReportData.timePerTickData() → SegmentedData
            val timePerTickDataMethod = getTickReport5sMethod?.returnType?.methods
                ?.firstOrNull { it.name == "timePerTickData" && it.parameterCount == 0 }

            // 10. SegmentedData.segmentAll() → DataPoint
            val segmentAllMethod = timePerTickDataMethod?.returnType?.methods
                ?.firstOrNull { it.name == "segmentAll" && it.parameterCount == 0 }

            // 11. DataPoint.average() → double
            val averageMethod = segmentAllMethod?.returnType?.methods
                ?.firstOrNull { it.name == "average" && it.parameterCount == 0 }

            val msptAvailable = averageMethod != null
            val enumMode = if (regionsMapField != null) "direct-map(O(regions))" else "loadedChunks-fallback(O(chunks))"
            bootstrap.logger.info(
                "[UnifiedMetrics] Region profiling active — " +
                "enumeration=$enumMode, TPS=public-API, MSPT=${if (msptAvailable) "reflection-ok" else "unavailable"}"
            )

            Handles(
                getHandleMethod     = getHandleMethod,
                regioniserField     = regioniserField,
                regionsMapField     = regionsMapField,
                getRegionAt         = getRegionAtMethod,
                idField             = idField,
                getCenterChunk      = getCenterChunkMethod,
                chunkPosX           = chunkPosX,
                chunkPosZ           = chunkPosZ,
                getData             = getDataMethod,
                getSchedulingHandle = getSchedulingHandleMethod,
                getTickReport5s     = getTickReport5sMethod,
                timePerTickData     = timePerTickDataMethod,
                segmentAll          = segmentAllMethod,
                average             = averageMethod
            )
        } catch (e: Exception) {
            bootstrap.logger.warn("[UnifiedMetrics] Region profiling unavailable: ${e.message}")
            null
        }
    }

    /** Walk class hierarchy (including superclasses) to find a declared field matching name and/or type. */
    private fun findFieldInHierarchy(clazz: Class<*>, name: String? = null, type: Class<*>? = null): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val found = c.declaredFields.firstOrNull { f ->
                (name == null || f.name == name) && (type == null || f.type == type)
            }
            if (found != null) return found.also { it.isAccessible = true }
            c = c.superclass
        }
        return null
    }

    private fun logFail(msg: String): Handles? {
        bootstrap.logger.warn("[UnifiedMetrics] Region profiling unavailable: $msg")
        return null
    }

    // ── Handles container + helpers ───────────────────────────────────────────

    private inner class Handles(
        private val getHandleMethod: Method,
        private val regioniserField: Field,
        private val regionsMapField: Field?,
        private val getRegionAt: Method,
        private val idField: Field?,
        private val getCenterChunk: Method,
        private val chunkPosX: Field,
        private val chunkPosZ: Field,
        private val getData: Method,
        private val getSchedulingHandle: Method?,
        private val getTickReport5s: Method?,
        private val timePerTickData: Method?,
        private val segmentAll: Method?,
        private val average: Method?
    ) {
        fun getHandle(world: World): Any? = try {
            getHandleMethod.invoke(world)
        } catch (_: Exception) { null }

        /**
         * Fast path: returns a snapshot list of all live regions by reading
         * ThreadedRegionizer.regions directly under the regionizer's own monitor lock.
         * Returns null if the field was unavailable (fall back to chunk iteration).
         */
        fun getAllRegionsDirect(nmsWorld: Any): List<Any>? {
            if (regionsMapField == null) return null
            val regionizer = try { regioniserField.get(nmsWorld) } catch (_: Exception) { return null } ?: return null
            return try {
                @Suppress("UNCHECKED_CAST")
                synchronized(regionizer) {
                    (regionsMapField.get(regionizer) as? Map<*, *>)?.values?.filterNotNull()
                }
            } catch (_: Exception) { null }
        }

        fun getRegionAt(nmsWorld: Any, chunkX: Int, chunkZ: Int): Any? = try {
            val regionizer = regioniserField.get(nmsWorld) ?: return null
            getRegionAt.invoke(regionizer, chunkX, chunkZ)
        } catch (_: Exception) { null }

        fun getRegionId(region: Any): Long = try {
            idField?.getLong(region) ?: System.identityHashCode(region).toLong()
        } catch (_: Exception) { System.identityHashCode(region).toLong() }

        fun getCenterChunk(region: Any): Pair<Int, Int>? = try {
            val chunkPos = getCenterChunk.invoke(region) ?: return null
            chunkPosX.getInt(chunkPos) to chunkPosZ.getInt(chunkPos)
        } catch (_: Exception) { null }

        fun getData(region: Any): Any? = try {
            getData.invoke(region)
        } catch (_: Exception) { null }

        fun getRegionSchedulingHandle(regionData: Any?): Any? = try {
            regionData?.let { getSchedulingHandle?.invoke(it) }
        } catch (_: Exception) { null }

        fun getTickReport5s(handle: Any, nanoTime: Long): Any? = try {
            getTickReport5s?.invoke(handle, nanoTime)
        } catch (_: Exception) { null }

        fun getTimePerTickAvg(report: Any): Double = try {
            val tptData = timePerTickData?.invoke(report) ?: return Double.NaN
            val segment = segmentAll?.invoke(tptData) ?: return Double.NaN
            (average?.invoke(segment) as? Double) ?: Double.NaN
        } catch (_: Exception) { Double.NaN }
    }
}

