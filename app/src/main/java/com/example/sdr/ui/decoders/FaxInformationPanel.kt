package com.example.sdr.ui.decoders

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.decoders.fax.FaxDecodeWorker
import com.example.sdr.decoders.fax.FaxInformation
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun FaxInformationPanel(info: FaxInformation, decoder: FaxDecodeWorker, onExpand: () -> Unit = {}) {
    var formatMenu by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf<FaxInformation?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = saving; saving = null
        if (uri != null && image != null) scope.launch {
            saveStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = Bitmap.createBitmap(image.width, image.lines, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = android.graphics.Canvas(bitmap)
                        var y = 0f
                        image.pages.forEach { canvas.drawBitmap(it, 0f, y, null); y += it.height }
                        context.contentResolver.openOutputStream(uri).use { stream ->
                            check(stream != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Cannot save PNG" }
                        }
                    } finally { bitmap.recycle() }
                    "Saved"
                }.getOrElse { "Save failed: ${it.message}" }
            }
        }
    }
    fun saveImage() {
        if (info.lines > 0) { saving = info; save.launch("Radiola-FAX-${System.currentTimeMillis()}.png") }
    }
    DecoderCardFrame(modifier = Modifier.fillMaxWidth().height(160.dp), sidebar = {
        DecoderSidebar("FAX") {
            Text("USB / 3 kHz", color = RadiolaColors.secondaryText)
            Box {
                Text("${info.lpm} / ${info.ioc} \u25be", color = RadiolaColors.text,
                    fontSize = 13.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.clickable { formatMenu = true })
                DropdownMenu(formatMenu, { formatMenu = false }) {
                    for (ioc in listOf(576, 288)) for (lpm in listOf(120, 60, 90, 240)) {
                        DropdownMenuItem(text = { Text("$lpm LPM / IOC $ioc") },
                            onClick = { formatMenu = false; decoder.format(lpm, ioc) })
                    }
                }
            }
            Text("${info.lines} lines", color = RadiolaColors.secondaryText)
        }
    }) {
            DecoderToolbar {
                Text(saveStatus.ifBlank { info.status }.uppercase(java.util.Locale.ROOT), color = RadiolaColors.secondaryText,
                    style = DecoderToolbarText, maxLines = 1, modifier = Modifier.weight(1f).alignByBaseline(),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                DecoderAction("DECODE", checked = decoder.decoding) { decoder.enable(!decoder.decoding) }
                DecoderAction("CLEAR") { decoder.clear(); saveStatus = "" }
                DecoderAction("SAVE", enabled = info.lines > 0) { saveImage() }
            }
            DecoderDivider()
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (info.lines > 0) FaxPicture(info, Modifier.fillMaxSize(), interactive = true)
                Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                        RadiolaColors.surface.copy(alpha = .78f), RadiolaColors.surface.copy(alpha = .48f))))
                    .border(.5.dp, RadiolaColors.border.copy(alpha = .45f), RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Expand FAX image" }
                    .clickable(enabled = info.lines > 0, role = androidx.compose.ui.semantics.Role.Button, onClick = onExpand),
                    contentAlignment = Alignment.Center) {
                    val color = RadiolaColors.primary.copy(alpha = if (info.lines > 0) .85f else .3f)
                    Canvas(Modifier.size(14.dp)) {
                        val inset = 1.dp.toPx(); val arm = 4.5.dp.toPx()
                        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
                            val x = if (right) size.width - inset else inset
                            val y = if (bottom) size.height - inset else inset
                            drawLine(color, Offset(x, y), Offset(x + if (right) -arm else arm, y),
                                1.25.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(color, Offset(x, y), Offset(x, y + if (bottom) -arm else arm),
                                1.25.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
            }
            }
}

/** In-place viewer hosted by the receiver's plot/card area, leaving tuning controls visible. */
@Composable
internal fun FaxImageViewer(info: FaxInformation, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().clipToBounds()
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = {})
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FAX", color = RadiolaColors.primary, fontSize = 18.sp)
            Text("${info.lpm} / ${info.ioc}", color = RadiolaColors.secondaryText, fontSize = 11.sp)
            Text("${info.lines} lines", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            FaxCloseButton(onClose)
        }
        Box(Modifier.fillMaxWidth().weight(1f)
            .border(.5.dp, RadiolaColors.border, RoundedCornerShape(3.dp)).padding(1.dp).clipToBounds()) {
            FaxPicture(info, Modifier.fillMaxSize(), interactive = true)
        }
    }
}

@Composable
private fun FaxCloseButton(onClose: () -> Unit) {
    val color = RadiolaColors.primary
    Row(Modifier.clip(RoundedCornerShape(50)).background(RadiolaColors.primaryTint, RoundedCornerShape(50))
        .border(.65.dp, RadiolaColors.border, RoundedCornerShape(50))
        .clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(10.dp)) {
            val inset = 1.dp.toPx()
            drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset),
                1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset),
                1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Text("CLOSE", color = color, fontSize = 10.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, letterSpacing = .5.sp)
    }
}

@Composable
private fun FaxPicture(info: FaxInformation, modifier: Modifier, interactive: Boolean = false) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var followLatest by remember { mutableStateOf(true) }
    val currentInfo by rememberUpdatedState(info)
    val images = remember(info.revision) { info.pages.map { it.asImageBitmap() } }
    Canvas(modifier.clipToBounds().then(if (interactive) Modifier.pointerInput(Unit) {
        detectTransformGestures { centroid, movement, factor, _ ->
            // The displayed position may have advanced with incoming rows since
            // the last gesture. Start dragging from that position, without a jump.
            val oldHeight = size.width.toFloat() / currentInfo.width * zoom * currentInfo.lines
            val displayedPan = if (followLatest) pan.copy(y = -maxOf(0f, oldHeight - size.height)) else pan
            val old = zoom; zoom = (zoom * factor).coerceIn(1f, 12f)
            val candidate = (displayedPan - centroid) * (zoom / old) + centroid + movement
            val height = size.width.toFloat() / currentInfo.width * zoom * currentInfo.lines
            val overflow = maxOf(0f, height - size.height)
            pan = Offset(candidate.x.coerceIn(size.width * (1 - zoom), 0f),
                candidate.y.coerceIn(-overflow, 0f))
            followLatest = overflow == 0f || (movement.y <= 0f && factor == 1f && pan.y <= -overflow + 2f)
        }
    } else Modifier)) {
        if (info.lines == 0) return@Canvas
        val scale = size.width / info.width * zoom
        val width = info.width * scale; val height = info.lines * scale
        val x = pan.x.coerceIn(minOf(0f, size.width - width), 0f)
        val overflow = maxOf(0f, height - size.height)
        var y = if (followLatest) -overflow else pan.y.coerceIn(-overflow, 0f)
        for (image in images) {
            val bottom = y + image.height * scale
            drawImage(image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(width.roundToInt().coerceAtLeast(1), (bottom.roundToInt() - y.roundToInt()).coerceAtLeast(1)))
            y = bottom
        }
    }
}
