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

package dev.cubxity.plugins.metrics.prometheus.exporter

import dev.cubxity.plugins.metrics.api.UnifiedMetrics
import dev.cubxity.plugins.metrics.prometheus.PrometheusMetricsDriver
import dev.cubxity.plugins.metrics.prometheus.config.AuthenticationScheme
import dev.cubxity.plugins.metrics.prometheus.proto.Label
import dev.cubxity.plugins.metrics.prometheus.proto.Sample
import dev.cubxity.plugins.metrics.prometheus.proto.TimeSeries
import dev.cubxity.plugins.metrics.prometheus.proto.WriteRequest
import io.prometheus.client.Collector
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.xerial.snappy.Snappy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.math.max
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalSerializationApi::class)
class RemoteWriteExporter(
    private val api: UnifiedMetrics,
    private val driver: PrometheusMetricsDriver
) : PrometheusExporter {
    private val coroutineScope = CoroutineScope(Dispatchers.IO) + SupervisorJob()

    private var authHeader: String? = null

    override fun initialize() {
        val auth = driver.config.remoteWrite.authentication
        if (auth.scheme == AuthenticationScheme.Basic) {
            val encoded = Base64.getEncoder().encodeToString("${auth.username}:${auth.password}".toByteArray())
            authHeader = "Basic $encoded"
        }
        scheduleTasks()
    }

    override fun close() {
        coroutineScope.cancel()
    }

    private fun scheduleTasks() {
        val interval = driver.config.remoteWrite.interval * 1000

        coroutineScope.launch {
            while (true) {
                val time = measureTimeMillis {
                    try {
                        val samples = api.metricsManager.collect()
                        val extraLabels = mapOf(
                            "server" to api.serverName,
                            "platform" to api.platform.type.name
                        )
                        val mfs = samples.toPrometheus(extraLabels)
                        send(mfs)
                    } catch (error: Throwable) {
                        api.logger.severe("An error occurred whilst writing samples via remote_write", error)
                    }
                }
                delay(max(0, interval - time))
            }
        }
    }

    private fun send(mfs: List<Collector.MetricFamilySamples>) {
        val now = System.currentTimeMillis()

        val timeSeriesList = mfs.flatMap { family ->
            family.samples.map { sample ->
                // Build label list: __name__ + all labels, sorted alphabetically (Prometheus requirement)
                val labels = buildList {
                    add(Label("__name__", sample.name))
                    for (i in sample.labelNames.indices) {
                        add(Label(sample.labelNames[i], sample.labelValues[i]))
                    }
                }.sortedBy { it.name }

                TimeSeries(
                    labels = labels,
                    samples = listOf(Sample(value = sample.value, timestamp = now))
                )
            }
        }

        if (timeSeriesList.isEmpty()) return

        val writeRequest = WriteRequest(timeseries = timeSeriesList)
        val protoBytes = ProtoBuf.encodeToByteArray(writeRequest)
        val compressed = Snappy.compress(protoBytes)

        val url = URL(driver.config.remoteWrite.url)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-protobuf")
            conn.setRequestProperty("Content-Encoding", "snappy")
            conn.setRequestProperty("X-Prometheus-Remote-Write-Version", "0.1.0")
            conn.setRequestProperty("User-Agent", "UnifiedMetrics/0.3")
            authHeader?.let { conn.setRequestProperty("Authorization", it) }
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.outputStream.use { it.write(compressed) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
                throw java.io.IOException("remote_write returned HTTP $code: $body")
            }
        } finally {
            conn.disconnect()
        }
    }
}
