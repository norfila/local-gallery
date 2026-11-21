package com.luma.gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luma.gallery.R

/**
 * Shown instead of the map until the user agreed to load it. Everything here is drawn on the device:
 * a real map cannot be blurred without first downloading it.
 *
 * [asked] is false on the first visit ("Load the online map?" with Not now) and true after Not now or
 * switching the map off ("The online map is off").
 */
@Composable
internal fun MapConsent(asked: Boolean, onAllow: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).testTag("map-consent"), contentAlignment = Alignment.Center) {
        OfflineMapSketch(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .28f)))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.padding(16.dp).widthIn(max = 440.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Outlined.PrivacyTip, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (asked) R.string.map_consent_off_title else R.string.map_consent_title),
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.map_consent_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (asked) Text(stringResource(R.string.map_consent_off_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!asked) TextButton(onClick = onDecline, modifier = Modifier.testTag("map-consent-decline")) {
                        Text(stringResource(R.string.map_consent_not_now))
                    }
                    Button(onClick = onAllow, modifier = Modifier.testTag("map-consent-allow")) { Text(stringResource(R.string.map_consent_allow)) }
                }
                Text(stringResource(R.string.map_consent_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A soft, made-up map (water, land, two roads, a few pins), blurred where Android supports it (12+). */
@Composable
private fun OfflineMapSketch(modifier: Modifier = Modifier) {
    val dark = LocalGalleryDarkTheme.current
    val water = if (dark) Color(0xFF1B2830) else Color(0xFFC9DDE8)
    val land = if (dark) Color(0xFF2A3A2C) else Color(0xFFE6E8D4)
    val green = if (dark) Color(0xFF30472F) else Color(0xFFCFE1BE)
    val road = if (dark) Color(0xFF4A5963) else Color.White
    val pin = MaterialTheme.colorScheme.primary
    Canvas(modifier.blur(6.dp)) {
        val w = size.width
        val h = size.height
        drawRect(water)
        // Shapes are fractions of the area, so the sketch looks alike on any screen.
        fun blob(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
            drawOval(color, topLeft = Offset((cx - rx) * w, (cy - ry) * h), size = Size(2 * rx * w, 2 * ry * h))
        blob(.28f, .30f, .30f, .21f, land)
        blob(.18f, .44f, .15f, .13f, land)
        blob(.70f, .64f, .31f, .25f, land)
        blob(.82f, .44f, .13f, .11f, land)
        blob(.56f, .14f, .08f, .05f, land)
        blob(.31f, .28f, .12f, .08f, green)
        blob(.67f, .69f, .14f, .09f, green)
        drawPath(Path().apply { moveTo(.04f * w, .36f * h); cubicTo(.30f * w, .18f * h, .46f * w, .52f * h, .96f * w, .56f * h) },
            road, style = Stroke(width = 3.dp.toPx()))
        drawPath(Path().apply { moveTo(.60f * w, .96f * h); cubicTo(.66f * w, .72f * h, .50f * w, .46f * h, .63f * w, .08f * h) },
            road, style = Stroke(width = 2.dp.toPx()))
        listOf(.30f to .31f, .70f to .60f, .75f to .67f, .19f to .45f).forEach { (x, y) ->
            drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x * w, y * h))
            drawCircle(pin, radius = 8.dp.toPx(), center = Offset(x * w, y * h))
        }
    }
}
