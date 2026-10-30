package dev.eltermino.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eltermino.app.MonitorTab
import dev.eltermino.app.ResourceSample
import dev.eltermino.app.formatBytes
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.ProgressBar
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

/** CPU, memory, network and disks of a host, read every few seconds while the tab is open. */
@Composable
internal fun MonitorPane(tab: MonitorTab) {
    val samples by tab.samples.collectAsState()
    val error by tab.error.collectAsState()
    val colors = Carta.colors
    val latest = samples.lastOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Risorse di ${tab.label}", style = Carta.type.title)
        Text(
            listOfNotNull(latest?.let { "Acceso da ${formatUptime(it.uptimeSeconds)}" }, "aggiornato ogni 3 secondi").joinToString(" · "),
            style = Carta.type.small,
            color = colors.textDim,
        )
        error?.let { Text(it, color = colors.danger, modifier = Modifier.padding(top = 10.dp)) }
        if (latest == null) {
            if (error == null) Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HoppingSombrero()
                Text("Lettura delle risorse di ${tab.label}…", color = colors.textDim, modifier = Modifier.padding(top = 12.dp))
            }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { CpuCard(samples, latest, it) },
                { MemoryCard(samples, latest, it) },
                { NetworkCard(samples, latest, it) },
                { DisksCard(latest, it) },
            )
            val columns = if (maxWidth >= 760.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { card -> card(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, value: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Carta.colors
    Column(modifier.border(1.dp, colors.line, RoundedCornerShape(10.dp)).padding(14.dp)) {
        Text(title, style = Carta.type.label, color = colors.textDim)
        Text(value, style = Carta.type.title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        content()
    }
}

@Composable
private fun CpuCard(samples: List<ResourceSample>, latest: ResourceSample, modifier: Modifier) {
    Card("CPU", latest.cpuPercent?.let { percent(it) } ?: "—", modifier) {
        Sparkline(listOf(samples.map { it.cpuPercent } to Carta.colors.accent), max = 100.0)
        Caption("Carico a 1, 5 e 15 minuti: ${latest.load.joinToString(" · ") { decimal(it) }} su ${latest.cpus} CPU")
    }
}

@Composable
private fun MemoryCard(samples: List<ResourceSample>, latest: ResourceSample, modifier: Modifier) {
    val used = latest.memUsedKb * 100.0 / latest.memTotalKb
    Card("Memoria", "${formatBytes(latest.memUsedKb * 1024)} di ${formatBytes(latest.memTotalKb * 1024)}", modifier) {
        Sparkline(listOf(samples.map { it.memUsedKb * 100.0 / it.memTotalKb } to Carta.colors.accent), max = 100.0)
        Caption(
            "${percent(used)} in uso" +
                if (latest.swapTotalKb > 0) " · swap ${formatBytes(latest.swapUsedKb * 1024)} di ${formatBytes(latest.swapTotalKb * 1024)}" else " · niente swap",
        )
    }
}

@Composable
private fun NetworkCard(samples: List<ResourceSample>, latest: ResourceSample, modifier: Modifier) {
    val colors = Carta.colors
    val value = latest.rxBytesPerSecond?.let { rx -> "↓ ${rate(rx)}  ↑ ${rate(latest.txBytesPerSecond ?: 0.0)}" } ?: "—"
    Card("Rete", value, modifier) {
        Sparkline(listOf(samples.map { it.rxBytesPerSecond } to colors.accent, samples.map { it.txBytesPerSecond } to colors.textDim))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("↓ ricevuti", style = Carta.type.small, color = colors.accent)
            Text("↑ inviati", style = Carta.type.small, color = colors.textDim)
            Text("tutte le interfacce tranne lo", style = Carta.type.small, color = colors.textDim)
        }
    }
}

@Composable
private fun DisksCard(latest: ResourceSample, modifier: Modifier) {
    val colors = Carta.colors
    val fullest = latest.disks.maxByOrNull { it.usedKb * 1.0 / it.sizeKb }
    Card("Dischi", fullest?.let { percent(it.usedKb * 100.0 / it.sizeKb) + " su " + it.mount } ?: "—", modifier) {
        latest.disks.forEach { disk ->
            val used = disk.usedKb * 100.0 / disk.sizeKb
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(disk.mount, style = Carta.type.small, fontFamily = Carta.monospace, modifier = Modifier.weight(1f))
                Text("${formatBytes(disk.usedKb * 1024)} di ${formatBytes(disk.sizeKb * 1024)}", style = Carta.type.small, color = colors.textDim)
            }
            // Past 90% a disk is worth a look.
            ProgressBar((used / 100).toFloat(), Modifier.padding(top = 4.dp), color = if (used >= 90) colors.danger else colors.accent)
        }
        if (latest.disks.isEmpty()) Caption("df non ha elencato dischi")
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 8.dp))
}

/** Lines over the last samples, oldest at the left; gaps where a value is missing. */
@Composable
private fun Sparkline(series: List<Pair<List<Double?>, Color>>, max: Double? = null) {
    val grid = Carta.colors.line
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val top = max ?: series.flatMap { it.first }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
        val step = size.width / (MonitorTab.HISTORY - 1)
        series.forEach { (values, color) ->
            val path = Path()
            var drawing = false
            // Right-aligned, so the newest sample is always at the right edge.
            val offset = MonitorTab.HISTORY - values.size
            values.forEachIndexed { index, value ->
                if (value == null) {
                    drawing = false
                    return@forEachIndexed
                }
                val x = (offset + index) * step
                val y = size.height - (value / top).coerceIn(0.0, 1.0).toFloat() * (size.height - 2.dp.toPx())
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun decimal(value: Double) = (kotlin.math.round(value * 100) / 100).toString().replace('.', ',')

private fun percent(value: Double) = "${kotlin.math.round(value).toInt()}%"

private fun rate(bytesPerSecond: Double) = formatBytes(bytesPerSecond.toLong()) + "/s"

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> "$days g $hours h"
        hours > 0 -> "$hours h $minutes min"
        else -> "$minutes min"
    }
}

/** The icon in front of a Resources tab's title. */
@Composable
internal fun MonitorTabMark() {
    Icon(dev.eltermino.ui.components.CartaIcons.Pulse, contentDescription = "Risorse", tint = Carta.colors.textDim, size = 14.dp)
}
