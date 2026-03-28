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
 * TPS source (tiered):
 *   Tier 1 (Folia 1.21.11+) — public API:  `Server.getRegionTPS(world, chunkX, chunkZ)[0]`
 *   Tier 2 (older Folia)    — skipped (method not available at runtime)
 *
 * MSPT source (reflection into NMS, with graceful fallback if unavailable):
 *   `ServerLevel.regioniser`
 *     → `ThreadedRegionizer.getRegionAtSynchronised(chunkX, chunkZ)`
 *        → `ThreadedRegion.getData()` (TickRegionData)
 *           → `.getRegionSchedulingHandle()` (RegionScheduleHandle)
 *              → `.getTickReport5s(nanoTime)` (TickData.TickReportData)
 *                 → `.timePerTickData().segmentAll().average()` (nanoseconds per tick)
 *
 * Region enumeration:
 *   Iterates `world.loadedChunks`, calls `getRegionAtSynchronised` per chunk, deduplicates
 *   by `ThreadedRegion.id` (a public `long` field).  Center chunk is read from
 *   `ThreadedRegion.getCenterChunk()` (returns `net.minecraft.world.level.ChunkPos`).
 *   All NMS calls are thread-safe (`getRegionAtSynchronised` uses a lock).
 *
 * Metrics emitted (with labels `world` and `region` = "centerX,centerZ"):
 *   minecraft_region_tps              — TPS (5-second average)
 *   minecraft_region_mspt_seconds     — MSPT in seconds (5-second average), omitted if unavailable
 */
class FoliaRegionCollector(private val bootstrap: UnifiedMetricsFoliaBootstrap) : CollectorCollection {

    // ── Reflection handles (loaded once on first snapshot) ────────────────────

    private val handles: Handles? by lazy { tryLoadHandles() }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var snapshots: List<RegionSnapshot> = emptyList()
    private var task: ScheduledTask? = null

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
            bootstrap, { updateSnapshots() }, 1L, 20L
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
            val seen = HashSet<Long>()   // deduplicate by region id

            for (chunk in world.loadedChunks) {
                val region = h.getRegionAt(nmsWorld, chunk.x, chunk.z) ?: continue
                val regionId = h.getRegionId(region)
                if (!seen.add(regionId)) continue

                // Determine center chunk for the label
                val (cx, cz) = h.getCenterChunk(region) ?: (chunk.x to chunk.z)
                val label = "$cx,$cz"

                // TPS: public Folia API (Tier 1: 1.21.11+) ─────────────────────
                val tps: Double = try {
                    bootstrap.server.getRegionTPS(world, cx, cz)?.get(0) ?: continue
                } catch (_: Throwable) { continue }

                // MSPT: reflection chain (Tier 1 only) ──────────────────────────
                val msptSeconds: Double? = try {
                    val schedHandle = h.getRegionSchedulingHandle(h.getData(region)) ?: continue
                    val report     = h.getTickReport5s(schedHandle, now) ?: continue
                    val nanos      = h.getTimePerTickAvg(report)
                    nanos / 1_000_000_000.0   // nanoseconds → seconds
                } catch (_: Throwable) { null }

                next += RegionSnapshot(world.name, label, tps, msptSeconds)
            }
        }

        snapshots = next
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

            // 4. ThreadedRegion.id (long, public) — field name may vary across forks
            val idField: Field = try {
                regionClass.getField("id")
            } catch (_: NoSuchFieldException) {
                // Try declared field by name first, then fall back to first long field
                (regionClass.declaredFields.firstOrNull { it.name == "id" }
                    ?: regionClass.declaredFields.firstOrNull { it.type == Long::class.javaPrimitiveType }
                    ?: return logFail("ThreadedRegion id field not found (tried 'id' + long fallback)"))
                    .also { it.isAccessible = true }
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
            bootstrap.logger.info(
                "[UnifiedMetrics] Region profiling active — " +
                "enumeration=getRegionAtSynchronised, TPS=public-API, MSPT=${if (msptAvailable) "reflection-ok" else "unavailable"}"
            )

            Handles(
                getHandleMethod    = getHandleMethod,
                regioniserField    = regioniserField,
                getRegionAt        = getRegionAtMethod,
                idField            = idField,
                getCenterChunk     = getCenterChunkMethod,
                chunkPosX          = chunkPosX,
                chunkPosZ          = chunkPosZ,
                getData            = getDataMethod,
                getSchedulingHandle = getSchedulingHandleMethod,
                getTickReport5s    = getTickReport5sMethod,
                timePerTickData    = timePerTickDataMethod,
                segmentAll         = segmentAllMethod,
                average            = averageMethod
            )
        } catch (e: Exception) {
            bootstrap.logger.warn("[UnifiedMetrics] Region profiling unavailable: ${e.message}")
            null
        }
    }

    private fun logFail(msg: String): Handles? {
        bootstrap.logger.warn("[UnifiedMetrics] Region profiling unavailable: $msg")
        return null
    }

    // ── Handles container + helpers ───────────────────────────────────────────

    private inner class Handles(
        private val getHandleMethod: Method,
        private val regioniserField: Field,
        private val getRegionAt: Method,
        private val idField: Field,
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

        fun getRegionAt(nmsWorld: Any, chunkX: Int, chunkZ: Int): Any? = try {
            val regionizer = regioniserField.get(nmsWorld) ?: return null
            getRegionAt.invoke(regionizer, chunkX, chunkZ)
        } catch (_: Exception) { null }

        fun getRegionId(region: Any): Long = try {
            idField.getLong(region)
        } catch (_: Exception) { region.hashCode().toLong() }

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

