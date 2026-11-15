package com.example.sdr.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale

@Composable
internal fun FrequencyControl(
    tunedHz: Long, activeInput: String, editingFrequency: Boolean,
    changeInput: (String) -> Unit, applyInput: () -> Unit,
    onEditingChange: (Boolean) -> Unit, step: (Long) -> Unit, favorite: Boolean = false, favoriteEnabled: Boolean = true, onFavorite: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val paddedFrequency = String.format(Locale.US, "%d.%03d.%03d.%03d", tunedHz / 1_000_000_000,
            tunedHz / 1_000_000 % 1_000, tunedHz / 1_000 % 1_000, tunedHz % 1_000)
        val firstDigit = paddedFrequency.indexOfFirst { it in '1'..'9' }.let { if (it < 0) paddedFrequency.lastIndex else it }
        fun frequencyLabel(size: Float) = buildAnnotatedString {
            withStyle(SpanStyle(color = RadiolaColors.secondaryText.copy(alpha = .25f), fontSize = size.sp)) {
                append(paddedFrequency.take(firstDigit))
            }
            append(paddedFrequency.drop(firstDigit))
        }
        val frequencyText = frequencyLabel(56f)
        val frequencyStyle = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp)
        val frequencyWidth = textMeasurer.measure(frequencyText, frequencyStyle, softWrap = false).size.width
        val unitsWidth = textMeasurer.measure("MHz", TextStyle(fontSize = 12.sp)).size.width
        val snapWidth = if (editingFrequency) 0.dp else 76.dp
        val gaps = if (editingFrequency) 6.dp else 12.dp
        val availableWidth = with(LocalDensity.current) { maxWidth.toPx() - snapWidth.toPx() - gaps.toPx() } - unitsWidth
        val frequencyFontSize = (56f * .94f * (availableWidth / maxOf(1, frequencyWidth)).coerceIn(0.5f, 1f)).sp
        Row(
            Modifier.align(Alignment.Center).offset(y = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (editingFrequency) {
                BasicTextField(
                    value = activeInput,
                    onValueChange = changeInput,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    singleLine = true,
                    textStyle = TextStyle(color = RadiolaColors.text, fontSize = frequencyFontSize, fontWeight = FontWeight.Light),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(RadiolaColors.primary)
                )
                Text("✓", Modifier.clickable { applyInput(); onEditingChange(false) }, color = RadiolaColors.primary, fontSize = 36.sp)
            } else {
                var digitLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                var pressedDigit by remember { mutableStateOf<Rect?>(null) }
                val digitStep by rememberUpdatedState(step)
                Text(
                    frequencyLabel(frequencyFontSize.value),
                    Modifier.alignByBaseline().drawBehind {
                        pressedDigit?.let { bounds ->
                            drawRoundRect(RadiolaColors.primary.copy(alpha = .12f), bounds.topLeft, bounds.size,
                                CornerRadius(4.dp.toPx()))
                        }
                    }.pointerInput(paddedFrequency) {
                        detectTapGestures(
                            onPress = { point ->
                                val layout = digitLayout
                                val index = layout?.let {
                                    paddedFrequency.indices.firstOrNull { i ->
                                        val bounds = it.getBoundingBox(i)
                                        point.x >= bounds.left && point.x < bounds.right
                                    }
                                }
                                if (index != null && frequencyDigitStep(paddedFrequency, index) != null) {
                                    val bounds = layout.getBoundingBox(index)
                                    val top = if (point.y < size.height / 2f) 0f else size.height / 2f
                                    pressedDigit = Rect(bounds.left, top, bounds.right, top + size.height / 2f)
                                }
                                try { tryAwaitRelease() } finally { pressedDigit = null }
                            },
                            onLongPress = { onEditingChange(true) },
                            onTap = { point ->
                                val layout = digitLayout ?: return@detectTapGestures
                                val index = paddedFrequency.indices.firstOrNull { index ->
                                    val bounds = layout.getBoundingBox(index)
                                    point.x >= bounds.left && point.x < bounds.right
                                } ?: return@detectTapGestures
                                val delta = frequencyDigitStep(paddedFrequency, index) ?: return@detectTapGestures
                                digitStep(if (point.y < size.height / 2f) delta else -delta)
                            },
                        )
                    },
                    onTextLayout = { digitLayout = it },
                    color = RadiolaColors.text, fontSize = frequencyFontSize, fontWeight = FontWeight.Light,
                    letterSpacing = (frequencyFontSize.value / 80f).sp, maxLines = 1
                )
            }
            Text("MHz", Modifier.alignByBaseline(), color = RadiolaColors.secondaryText, fontSize = 12.sp, maxLines = 1, softWrap = false)
            if (!editingFrequency) {
                Text(if (favorite) "\u2605" else "\u2606",
                    Modifier.width(26.dp).height(40.dp).semantics { contentDescription = "Save current station" }
                        .clickable(enabled = favoriteEnabled, onClick = onFavorite).padding(top = 7.dp),
                    color = if (favorite) RadiolaColors.primary else RadiolaColors.secondaryText,
                    fontSize = 23.sp, textAlign = TextAlign.Center)
                val snapDeltaHz = ((tunedHz + 500L) / 1_000L) * 1_000L - tunedHz
                val canSnap = snapDeltaHz != 0L
                Text(
                    "≈1k",
                    Modifier.alignByBaseline().width(44.dp)
                        .semantics { contentDescription = "Round frequency to nearest kilohertz" }
                        .clickable(enabled = canSnap) {
                            step(snapDeltaHz)
                        }
                        .padding(vertical = 8.dp),
                    color = if (canSnap) RadiolaColors.primary else RadiolaColors.secondaryText.copy(alpha = .5f),
                    fontSize = 15.sp, lineHeight = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
