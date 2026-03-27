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

package dev.cubxity.plugins.metrics.folia.metric.events

import dev.cubxity.plugins.metrics.api.metric.collector.Collector
import dev.cubxity.plugins.metrics.api.metric.collector.CollectorCollection
import dev.cubxity.plugins.metrics.api.metric.collector.Counter
import dev.cubxity.plugins.metrics.api.metric.store.VolatileDoubleStore
import dev.cubxity.plugins.metrics.common.metric.Metrics
import dev.cubxity.plugins.metrics.folia.bootstrap.UnifiedMetricsFoliaBootstrap
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerListPingEvent

@Suppress("UNUSED_PARAMETER")
class EventsCollection(private val bootstrap: UnifiedMetricsFoliaBootstrap) : CollectorCollection, Listener {
    private val loginCounter = Counter(Metrics.Events.Login)
    private val joinCounter = Counter(Metrics.Events.Join, valueStoreFactory = VolatileDoubleStore)
    private val quitCounter = Counter(Metrics.Events.Quit, valueStoreFactory = VolatileDoubleStore)
    private val chatCounter = Counter(Metrics.Events.Chat)
    private val pingCounter = Counter(Metrics.Events.Ping)

    override val collectors: List<Collector> =
        listOf(loginCounter, joinCounter, quitCounter, chatCounter, pingCounter)

    // All counters use atomic/volatile stores — safe to read without dispatching to a platform thread.
    override val isAsync: Boolean get() = true

    override fun initialize() {
        bootstrap.server.pluginManager.registerEvents(this, bootstrap)
    }

    override fun dispose() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onLogin(event: AsyncPlayerPreLoginEvent) {
        loginCounter.inc()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        joinCounter.inc()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        quitCounter.inc()
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        chatCounter.inc()
    }

    @EventHandler
    fun onPing(event: ServerListPingEvent) {
        pingCounter.inc()
    }
}
