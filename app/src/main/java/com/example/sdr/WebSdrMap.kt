package com.example.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.RectF
import android.graphics.Paint
import kotlin.math.min
import kotlin.math.log2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** Offline map rendered by the app from public-domain Natural Earth geometry. */
@Composable
internal fun WebSdrMap(
    modifier: Modifier = Modifier,
    selectedReceiverUrl: String? = null,
    onReceiverSelected: (WebSdrReceiver) -> Unit = {},
) {
    val currentSelectionCallback by rememberUpdatedState(onReceiverSelected)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var listMode by rememberSaveable { mutableStateOf(false) }
    var cities by remember { mutableStateOf<List<MapCity>>(emptyList()) }
    val cityPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT } }
    val cityOutline = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT
        style = Paint.Style.STROKE
        color = RadiolaColors.background.toArgb()
        strokeJoin = Paint.Join.ROUND
    } }
    LaunchedEffect(Unit) {
        cities = WebSdrMapAssets.cities(context)
    }

    val directory = remember { WebSdrDirectory(context.applicationContext) }
    var mapReceivers by remember { mutableStateOf<List<WebSdrReceiver>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var directoryFailed by remember { mutableStateOf(false) }
    var cached by remember { mutableStateOf(false) }
    var updatedAt by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true
        directoryFailed = false
        try {
            val saved = withContext(Dispatchers.IO) { directory.cached() }
            if (saved != null && mapReceivers.isEmpty()) {
                mapReceivers = saved.receivers; updatedAt = saved.updatedAt; cached = true
            }
            if (refresh > 0 || saved == null || System.currentTimeMillis() - saved.updatedAt !in 0L..120_000L) {
                val live = withContext(Dispatchers.IO) { directory.fetch() }
                mapReceivers = live.receivers; updatedAt = live.updatedAt; cached = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { directoryFailed = true; cached = mapReceivers.isNotEmpty() }
        finally { loading = false }
    }
    var land by remember { mutableStateOf<List<Path>>(emptyList()) }
    var borders by remember { mutableStateOf<List<Path>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            land = WebSdrMapAssets.land(context)
            borders = WebSdrMapAssets.borders(context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var selectedUrl by rememberSaveable { mutableStateOf(selectedReceiverUrl) }
    val selectedReceiver = mapReceivers.firstOrNull { it.url == selectedUrl }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    val density = LocalDensity.current
    val labelSize = with(density) { 10.sp.toPx() }
    val labelGap = with(density) { 13.dp.toPx() }
    val labelSpacing = with(density) { 11.dp.toPx() }
    val labels = remember(cities, viewport, zoom, labelSize, labelGap, labelSpacing) {
        cityPaint.textSize = labelSize
        val unit = min(viewport.width / 360f, viewport.height / 180f) * zoom
        layoutCityLabels(cities, unit, 2f + 1.3f * log2(zoom), cityPaint, labelGap, labelSpacing)
    }
    fun point(receiver: WebSdrReceiver): Offset {
        val unit = min(viewport.width / 360f, viewport.height / 180f) * zoom
        return Offset(viewport.width / 2f + panX + receiver.lon * unit,
            viewport.height / 2f + panY - receiver.lat * unit)
    }
    fun transform(factor: Float, delta: Offset = Offset.Zero, anchor: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        val next = (zoom * factor).coerceIn(1f, 64f)
        val ratio = next / zoom
        val offset = anchor - Offset(viewport.width / 2f, viewport.height / 2f)
        val unit = min(viewport.width / 360f, viewport.height / 180f) * next
        val limitX = (unit * 180f - viewport.width / 2f).coerceAtLeast(0f)
        val limitY = (unit * 90f - viewport.height / 2f).coerceAtLeast(0f)
        panX = ((panX - offset.x) * ratio + offset.x + delta.x)
            .coerceIn(-limitX, limitX)
        panY = ((panY - offset.y) * ratio + offset.y + delta.y)
            .coerceIn(-limitY, limitY)
        zoom = next
    }
    Box(modifier.clipToBounds().background(Brush.verticalGradient(listOf(RadiolaColors.adaptive(Color(0xFFEAF2FC), Color(0xFF111D24)), RadiolaColors.adaptive(Color(0xFFDFEBFA), Color(0xFF080F15)))))
        .border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))) {
        if (!listMode) Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, scale, _ -> transform(scale, pan, centroid) } }
            .pointerInput(Unit) { detectTapGestures(
                onDoubleTap = { transform(1.8f, anchor = it) },
                onTap = { tap ->
                    val receiver = mapReceivers.minByOrNull { (point(it) - tap).getDistance() }
                        ?.takeIf { (point(it) - tap).getDistance() < 28.dp.toPx() }
                    selectedUrl = receiver?.url
                    receiver?.let { currentSelectionCallback(it) }
                },
            ) }) {
            val unit = min(size.width / 360f, size.height / 180f) * zoom
            val center = Offset(size.width / 2f + panX, size.height / 2f + panY)
            val gridStep = when {
                zoom >= 32f -> 1
                zoom >= 12f -> 2
                zoom >= 5f -> 5
                zoom >= 2f -> 15
                else -> 30
            }
            for (lon in -180..180 step gridStep) {
                val x = center.x + lon * unit
                if (x in 0f..size.width) drawLine(RadiolaColors.adaptive(Color(0xFFCAD9EC), Color(0xFF223039)).copy(alpha = .55f),
                    Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
            }
            for (lat in -90..90 step gridStep) {
                val y = center.y - lat * unit
                if (y in 0f..size.height) drawLine(RadiolaColors.adaptive(Color(0xFFCAD9EC), Color(0xFF223039)).copy(alpha = .55f),
                    Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
            }
            withTransform({ translate(center.x, center.y); scale(unit, unit, pivot = Offset.Zero) }) {
                land.forEach { path ->
                    drawPath(path, RadiolaColors.adaptive(Color(0xFFE0EAF6), Color(0xFF16232A)), style = Stroke(4.dp.toPx() / unit))
                    drawPath(path, RadiolaColors.adaptive(Color(0xFFD2E1F2), Color(0xFF26353A)))
                    drawPath(path, RadiolaColors.adaptive(Color(0xFFACBED5), Color(0xFF61706D)).copy(alpha = .65f), style = Stroke(0.6.dp.toPx() / unit))
                }
                borders.forEach { path ->
                    drawPath(path, RadiolaColors.adaptive(Color(0xFF8CA9CE), Color(0xFFAB9C80)).copy(alpha = .55f),
                        style = Stroke(0.65.dp.toPx() / unit))
                }
            }
            cityPaint.textSize = labelSize
            cityOutline.color = RadiolaColors.background.toArgb()
            cityPaint.color = RadiolaColors.text.toArgb()
            cityOutline.textSize = labelSize
            cityOutline.strokeWidth = 3.dp.toPx()
            labels.forEach { label ->
                val city = label.city
                val x = center.x + city.lon * unit
                val y = center.y - city.lat * unit
                val left = x + labelGap
                val top = y - labelSize / 2
                val textWidth = label.width
                // Cull only when both the point and its entire label are offscreen.
                // Visibility never depends on other labels, markers or map panels.
                val radius = 1.5.dp.toPx()
                if (maxOf(x + radius, left + textWidth + cityOutline.strokeWidth) < 0f ||
                    minOf(x - radius, left - cityOutline.strokeWidth) > size.width ||
                    maxOf(y + radius, top + labelSize + cityOutline.strokeWidth) < 0f ||
                    minOf(y - radius, top - cityOutline.strokeWidth) > size.height) return@forEach
                drawCircle(RadiolaColors.adaptive(Color(0xFF536E91), Color(0xFFADB5B1)), radius, Offset(x, y))
                val baseline = top - cityPaint.fontMetrics.ascent
                drawContext.canvas.nativeCanvas.drawText(city.name, left, baseline, cityOutline)
                drawContext.canvas.nativeCanvas.drawText(city.name, left, baseline, cityPaint)
            }
            mapReceivers.forEach { receiver ->
                val position = point(receiver)
                if (position.x < -30.dp.toPx() || position.y < -30.dp.toPx() || position.x > size.width + 30.dp.toPx() || position.y > size.height + 30.dp.toPx()) return@forEach
                val active = selectedUrl == receiver.url
                if (active) {
                    drawCircle(Brush.radialGradient(listOf(warm.copy(alpha = .24f), Color.Transparent),
                        center = position, radius = 23.dp.toPx()), 23.dp.toPx(), position)
                    drawCircle(warm.copy(alpha = .7f), 10.dp.toPx(), position, style = Stroke(1.dp.toPx()))
                }
                drawCircle(RadiolaColors.adaptive(Color(0xFFFCFDFF), Color(0xFF0C151B)), 6.dp.toPx(), position)
                drawCircle(if (active) RadiolaColors.adaptive(Color(0xFF0046CF), Color(0xFFFFD59A)) else warm.copy(alpha = .9f), 4.dp.toPx(), position)
                drawCircle(RadiolaColors.adaptive(Color(0xFFFCFDFF), Color(0xFF0C151B)), 1.5.dp.toPx(), position)

            }
        }
        if (listMode) WebSdrServerList(mapReceivers, selectedUrl,
            onSelect = { selectedUrl = it.url; currentSelectionCallback(it) },
            modifier = Modifier.fillMaxSize().padding(top = 100.dp, start = 12.dp, end = 12.dp, bottom = 12.dp))
        TextButton(onClick = { listMode = !listMode },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 56.dp)) {
            Text(if (listMode) "MAP" else "LIST", color = warm, fontSize = 10.sp)
        }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp)
            .background(RadiolaColors.adaptive(Color(0xF2FCFDFF), Color(0xE60C1013)), RoundedCornerShape(4.dp)).padding(8.dp)) {
            Text("WEBSDR", color = warm, fontSize = 12.sp, letterSpacing = 2.sp)
            val time = if (updatedAt > 0) SimpleDateFormat("dd MMM HH:mm", locale).format(Date(updatedAt)) else ""
            Text(when {
                loadFailed -> "Map unavailable"
                loading -> "Loading receivers…"
                directoryFailed && mapReceivers.isEmpty() -> "Unable to load receivers"
                else -> "${mapReceivers.size} receivers · ${if (cached) "cached" else "updated"} $time"
            }, color = muted, fontSize = 10.sp)
            if (directoryFailed && mapReceivers.isNotEmpty()) Text("Update failed · showing saved list", color = muted, fontSize = 10.sp)
            TextButton(onClick = { refresh++ }, enabled = !loading, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(30.dp)) {
                Text("Refresh", color = if (loading) muted else warm, fontSize = 11.sp)
            }
        }
        if (!listMode) Column(Modifier.align(Alignment.TopEnd).padding(12.dp)
            .background(RadiolaColors.adaptive(Color(0xF5FCFDFF), Color(0xF5151719)), RoundedCornerShape(5.dp))) {
            TextButton(onClick = { transform(1.5f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("+", color = warm, fontSize = 22.sp)
            }
            TextButton(onClick = { transform(1 / 1.5f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("−", color = warm, fontSize = 22.sp)
            }
            TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("↺", color = warm, fontSize = 20.sp)
            }
        }
        if (!listMode) Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 32.dp)
            .background(RadiolaColors.adaptive(Color(0xEEFCFDFF), Color(0xDD0C151B)), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("N ↑", color = muted, fontSize = 10.sp)
            val unit = min(viewport.width / 360f, viewport.height / 180f) * zoom
            if (unit > 0f) Text(String.format(Locale.US, "%.2f°%s  %.2f°%s",
                kotlin.math.abs(panY / unit), if (panY >= 0f) "N" else "S",
                kotlin.math.abs(panX / unit), if (panX <= 0f) "E" else "W"),
                color = muted, fontSize = 10.sp)
            Text(String.format(Locale.US, "%.1f×", zoom), color = warm, fontSize = 11.sp)
        }
        if (!listMode) selectedReceiver?.let { receiver ->
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)
                .widthIn(max = 440.dp).heightIn(max = 180.dp)
                .background(RadiolaColors.adaptive(Color(0xF5FCFDFF), Color(0xF5151719)), RoundedCornerShape(6.dp))
                .border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp)).padding(12.dp)
                .verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Html.fromHtml(receiver.name, Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                        modifier = Modifier.weight(1f), color = warm, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                }
                Text(listOfNotNull(receiver.location.takeIf { it.isNotBlank() }, receiver.users?.let { "$it listeners" })
                    .joinToString(" · "), color = RadiolaColors.text, fontSize = 12.sp)
                Text(receiver.url, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                receiver.bands.forEach { band ->
                    Text(String.format(Locale.US, "%.3f–%.3f MHz", band.lowMHz, band.highMHz), color = RadiolaColors.text, fontSize = 11.sp)
                    if (band.antenna.isNotBlank()) Text(Html.fromHtml(band.antenna, Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim(), color = muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("Natural Earth", color = muted, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

private data class CityLabel(val city: MapCity, val width: Float)

/** Resolve density in world coordinates; panning and viewport clipping never change this selection. */
private fun layoutCityLabels(cities: List<MapCity>, unit: Float, level: Float,
    paint: Paint, gap: Float, spacing: Float): List<CityLabel> {
    if (unit <= 0f) return emptyList()
    val cellSize = paint.textSize * 6f
    val cells = HashMap<Pair<Int, Int>, MutableList<RectF>>()
    return buildList {
        for (city in cities) {
            if (city.minZoom > level) continue
            val width = paint.measureText(city.name)
            val left = city.lon * unit + gap
            val top = -city.lat * unit - paint.textSize / 2
            val bounds = RectF(left - spacing, top - spacing / 2,
                left + width + spacing, top + paint.textSize + spacing / 2)
            val xs = kotlin.math.floor(bounds.left / cellSize).toInt()..kotlin.math.floor(bounds.right / cellSize).toInt()
            val ys = kotlin.math.floor(bounds.top / cellSize).toInt()..kotlin.math.floor(bounds.bottom / cellSize).toInt()
            if (xs.any { x -> ys.any { y -> cells[x to y]?.any { RectF.intersects(it, bounds) } == true } }) continue
            for (x in xs) for (y in ys) cells.getOrPut(x to y) { mutableListOf() }.add(bounds)
            add(CityLabel(city, width))
        }
    }
}
