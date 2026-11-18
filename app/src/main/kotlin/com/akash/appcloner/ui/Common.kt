package com.akash.appcloner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.ui.theme.StatusColors

/**
 * Renders a measurement or, when there is none, the reason there is none.
 *
 * This is the single place the dashboard turns a [Metric] into pixels, which is what keeps a
 * missing reading from ever being drawn as a zero.
 */
@Composable
fun <T> MetricCard(
    title: String,
    metric: Metric<T>?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            when (metric) {
                null -> Text(
                    "not sampled yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )

                is Metric.Value -> {
                    content(metric.value)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "source: ${metric.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.unknown,
                    )
                }

                is Metric.Unavailable -> {
                    Text(
                        "unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.warn,
                    )
                    Text(
                        metric.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.unknown,
                    )
                    metric.remedy?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.unknown,
                        )
                    }
                }
            }
        }
    }
}

/** Simple horizontal bar. Drawn by hand to keep the shape stable across Material revisions. */
@Composable
fun Meter(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val clamped = fraction?.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(StatusColors.unknown.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    ) {
        if (clamped != null && clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.unknown,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
