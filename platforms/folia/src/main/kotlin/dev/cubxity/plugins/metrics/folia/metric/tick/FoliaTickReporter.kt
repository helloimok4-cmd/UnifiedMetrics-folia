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

package dev.cubxity.plugins.metrics.folia.metric.tick

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import dev.cubxity.plugins.metrics.api.metric.collector.MILLISECONDS_PER_SECOND
import dev.cubxity.plugins.metrics.folia.bootstrap.UnifiedMetricsFoliaBootstrap
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.concurrent.atomic.AtomicLong

// Folia fires ServerTickEndEvent once per region (one per world), not once per global tick.
// We deduplicate by only recording the first event in each 50ms tick window.
private const val TICK_WINDOW_MS = 50L

class FoliaTickReporter(
    private val metric: TickCollection,
    private val bootstrap: UnifiedMetricsFoliaBootstrap
) : TickReporter, Listener {
    private val lastTickSlot = AtomicLong(-1L)

    override fun initialize() {
        bootstrap.server.pluginManager.registerEvents(this, bootstrap)
    }

    override fun dispose() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onTick(event: ServerTickEndEvent) {
        val slot = System.currentTimeMillis() / TICK_WINDOW_MS
        if (lastTickSlot.getAndSet(slot) != slot) {
            metric.onTick(event.tickDuration / MILLISECONDS_PER_SECOND)
        }
    }
}
