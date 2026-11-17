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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sdr.decoders.fax.FaxDecodeWorker
import com.example.sdr.decoders.fax.FaxInformation
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun FaxInformationPanel(info: FaxInformation, decoder: FaxDecodeWorker) {
    var expanded by remember { mutableStateOf(false) }
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
    Row(Modifier.fillMaxWidth().height(160.dp)
        .border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .padding(start = 10.dp, end = 8.dp, top = 3.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(80.dp).padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FAX", color = RadiolaColors.primary, fontSize = 18.sp)
            Text("USB / 3 kHz", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            Box {
                Text("${info.lpm} / ${info.ioc} \u25be", color = RadiolaColors.secondaryText, fontSize = 10.sp,
                    modifier = Modifier.clickable { formatMenu = true }.padding(vertical = 4.dp))
                DropdownMenu(formatMenu, { formatMenu = false }) {
                    for (ioc in listOf(576, 288)) for (lpm in listOf(120, 60, 90, 240)) {
                        DropdownMenuItem(text = { Text("$lpm LPM / IOC $ioc") },
                            onClick = { formatMenu = false; decoder.format(lpm, ioc) })
                    }
                }
            }
            Text("${info.lines} lines", color = RadiolaColors.secondaryText, fontSize = 9.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 5.dp)
            .background(RadiolaColors.border))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(saveStatus.ifBlank { info.status }, color = RadiolaColors.secondaryText,
                    fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                FaxButton("DECODE", decoder.decoding) { decoder.enable(!decoder.decoding) }
                Text("CLEAR", color = RadiolaColors.secondaryText, fontSize = 11.sp,
                    modifier = Modifier.clickable { decoder.clear(); saveStatus = "" }.padding(horizontal = 8.dp))
                Text("SAVE", color = RadiolaColors.secondaryText.copy(alpha = if (info.lines > 0) 1f else .35f),
                    fontSize = 11.sp, modifier = Modifier.clickable(enabled = info.lines > 0) { saveImage() }.padding(horizontal = 8.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f)
                .border(.5.dp, RadiolaColors.border, RoundedCornerShape(3.dp))
                .padding(1.dp).clipToBounds().clickable(enabled = info.lines > 0) { expanded = true }) {
                if (info.lines > 0) FaxPicture(info, Modifier.fillMaxSize(), interactive = true)
            }
        }
    }
    if (expanded && info.lines > 0) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(RadiolaColors.surface).padding(12.dp)) {
            FaxPicture(info, Modifier.fillMaxSize(), interactive = true)
            Row(Modifier.align(Alignment.TopEnd).background(RadiolaColors.surface).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FaxButton("SAVE") { saveImage() }
                FaxButton("CLOSE") { expanded = false }
            }
        }
    }
}

@Composable
private fun FaxButton(label: String, active: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    Text(label, color = (if (active) RadiolaColors.primary else RadiolaColors.text).copy(alpha = if (enabled) 1f else .35f),
        fontSize = 10.sp, modifier = Modifier.clickable(enabled = enabled, onClick = action)
            .border(.5.dp, if (active) RadiolaColors.primary else RadiolaColors.border, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp))
}

@Composable
private fun FaxPicture(info: FaxInformation, modifier: Modifier, interactive: Boolean = false) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentInfo by rememberUpdatedState(info)
    val images = remember(info.revision) { info.pages.map { it.asImageBitmap() } }
    Canvas(modifier.clipToBounds().then(if (interactive) Modifier.pointerInput(Unit) {
        detectTransformGestures { centroid, movement, factor, _ ->
            val old = zoom; zoom = (zoom * factor).coerceIn(1f, 12f)
            val candidate = (pan - centroid) * (zoom / old) + centroid + movement
            val height = size.width.toFloat() / currentInfo.width * zoom * currentInfo.lines
            pan = Offset(candidate.x.coerceIn(size.width * (1 - zoom), 0f),
                candidate.y.coerceIn(-maxOf(0f, height - size.height), 0f))
        }
    } else Modifier)) {
        if (info.lines == 0) return@Canvas
        val scale = size.width / info.width * zoom
        val width = info.width * scale; val height = info.lines * scale
        val x = pan.x.coerceIn(minOf(0f, size.width - width), 0f)
        var y = pan.y.coerceIn(-maxOf(0f, height - size.height), 0f)
        for (image in images) {
            val bottom = y + image.height * scale
            drawImage(image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(width.roundToInt().coerceAtLeast(1), (bottom.roundToInt() - y.roundToInt()).coerceAtLeast(1)))
            y = bottom
        }
    }
}
