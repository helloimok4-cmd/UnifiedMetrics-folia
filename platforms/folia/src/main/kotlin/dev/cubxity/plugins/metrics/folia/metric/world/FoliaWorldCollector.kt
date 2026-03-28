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

package dev.cubxity.plugins.metrics.folia.metric.world

import dev.cubxity.plugins.metrics.api.metric.collector.Collector
import dev.cubxity.plugins.metrics.api.metric.collector.CollectorCollection
import dev.cubxity.plugins.metrics.api.metric.data.GaugeMetric
import dev.cubxity.plugins.metrics.api.metric.data.Metric
import dev.cubxity.plugins.metrics.api.util.fastForEach
import dev.cubxity.plugins.metrics.common.metric.Metrics
import dev.cubxity.plugins.metrics.folia.bootstrap.UnifiedMetricsFoliaBootstrap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Folia-safe world collector.
 *
 * Instead of reading live world state during collect() (which would require owning
 * the region thread for each world), we schedule a repeating task on the global
 * region scheduler to snapshot per-world counts into thread-safe atomics each tick.
 * collect() only reads those cached values, and is therefore safe from any thread.
 */
class FoliaWorldCollector(private val bootstrap: UnifiedMetricsFoliaBootstrap) : CollectorCollection {

    private data class WorldSnapshot(
        val entities: Int,
        val players: Int,
        val chunks: Int
    )

    @Volatile
    private var snapshots: Map<String, WorldSnapshot> = emptyMap()

    private var task: ScheduledTask? = null

    private val innerCollector = object : Collector {
        override fun collect(): List<Metric> {
            val current = snapshots
            val samples = ArrayList<Metric>(current.size * 3)
            current.forEach { (worldName, snap) ->
                val tags = mapOf("world" to worldName)
                samples.add(GaugeMetric(Metrics.Server.WorldEntitiesCount, tags, snap.entities))
                samples.add(GaugeMetric(Metrics.Server.WorldPlayersCount, tags, snap.players))
                samples.add(GaugeMetric(Metrics.Server.WorldLoadedChunks, tags, snap.chunks))
            }
            return samples
        }
    }

    override val collectors: List<Collector> = listOf(innerCollector)

    // isAsync = true so MetricsManagerImpl does NOT try to dispatch this through
    // a platform thread — snapshot reads are already thread-safe.
    override val isAsync: Boolean get() = true

    override fun initialize() {
        // GlobalRegionScheduler runs on the "global" region tick, which owns
        // server-wide and world-level state safely in Folia.
        task = bootstrap.server.globalRegionScheduler.runAtFixedRate(
            bootstrap,
            { updateSnapshots() },
            1L,
            20L  // update every second (20 ticks)
        )
    }

    override fun dispose() {
        task?.cancel()
        task = null
    }

    private fun updateSnapshots() {
        val worlds = bootstrap.server.worlds
        val next = HashMap<String, WorldSnapshot>(worlds.size)
        worlds.fastForEach { world ->
            next[world.name] = WorldSnapshot(
                entities = world.entityCount,
                players = world.playerCount,
                chunks = world.chunkCount
            )
        }
        snapshots = next
    }
}
