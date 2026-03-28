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

package dev.cubxity.plugins.metrics.folia

import kotlinx.coroutines.*
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@OptIn(InternalCoroutinesApi::class)
class FoliaDispatcher(private val plugin: JavaPlugin) : CoroutineDispatcher(), Delay {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = plugin.server.asyncScheduler.runDelayed(
            plugin,
            { continuation.apply { resumeUndispatched(Unit) } },
            timeMillis,
            TimeUnit.MILLISECONDS
        )
        continuation.invokeOnCancellation { task?.cancel() }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!context.isActive || !plugin.isEnabled) {
            return
        }
        plugin.server.globalRegionScheduler.execute(plugin, block)
    }
}
