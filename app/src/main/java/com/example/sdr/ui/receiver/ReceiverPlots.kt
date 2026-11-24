package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sdr.SpectrumFrame
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale

private val edge get() = RadiolaColors.border
private val warm get() = RadiolaColors.primary
private val muted get() = RadiolaColors.secondaryText

@androidx.compose.runtime.Immutable
internal data class ReceiverPlotState(
    val centerHz: Double, val spanHz: Int, val bandwidthHz: Int,
    val channelOffsetHz: Long, val passbandOffsetHz: Long,
    val remote: Boolean, val automatic: Boolean, val levels: DisplayLevels?,
    val showSpectrum: Boolean, val showWaterfall: Boolean, val fps: Int,
)

internal data class ReceiverPlotNavigation(
    val zoom: Float, val maximumZoom: Float,
    val onStep: (Long) -> Unit, val onZoom: (Float) -> Unit, val onContact: () -> Unit,
)

@Composable
internal fun ReceiverPlots(
    state: ReceiverPlotState,
    spectrum: State<SpectrumFrame?>,
    history: WaterfallHistory,
    favorites: List<com.example.sdr.favorites.FavoriteStation>,
    onAutomatic: () -> Unit,
    onFavorite: (com.example.sdr.favorites.FavoriteStation) -> Unit,
    navigation: ReceiverPlotNavigation,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clipToBounds()
            .border(1.dp, edge, RoundedCornerShape(6.dp))
            .background(RadiolaColors.plot, RoundedCornerShape(6.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            ReceiverPlotHeader(
                stations = favorites,
                centerHz = state.centerHz,
                spanHz = state.spanHz,
                axisLabel = if (!state.showSpectrum) null else if (state.remote) {
                    if (state.automatic) "dB" else "REL"
                } else "dBFS",
                automatic = state.automatic,
                onAutomatic = onAutomatic,
                onFavorite = onFavorite,
            )
            if (state.showSpectrum) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AmberSpectrum(spectrum, state.spanHz, state.bandwidthHz, state.channelOffsetHz, state.passbandOffsetHz.toInt(), state.centerHz, Modifier.fillMaxSize(), relative = state.remote,
                        displayRange = if (state.automatic) state.levels?.spectrum else null)
                    FavoriteFrequencyGuides(favorites, state.centerHz, state.spanHz,
                        Modifier.fillMaxSize().padding(start = ReceiverPlotGeometry.frequencyInset))
                }
            }
            if (state.showWaterfall) {
                Box(Modifier.fillMaxWidth().weight(1f).waterfallNavigation(state.spanHz, navigation.zoom, navigation.maximumZoom,
                    navigation.onStep, navigation.onZoom, navigation.onContact)) {
                    androidx.compose.runtime.key(history) {
                    AndroidView(
                        factory = { WaterfallView(it, history, minimumRenderWidth = if (state.remote) history.binCount else 1024, separateSurface = true) },
                        update = { view ->
                            view.setDisplayRange(if (state.automatic) state.levels?.waterfall else null)
                            view.setTuning(state.centerHz.toLong(), state.spanHz)
                            view.refresh()
                        },
                        modifier = Modifier.fillMaxSize().padding(start = ReceiverPlotGeometry.frequencyInset)
                    )
                    }
                    Canvas(Modifier.fillMaxSize().padding(start = ReceiverPlotGeometry.frequencyInset).clipToBounds()) {
                        val center = size.width * (0.5f + state.channelOffsetHz.toFloat() / state.spanHz)
                        val channelWidth = size.width * (state.bandwidthHz.toFloat() / state.spanHz)
                        val passbandCenter = size.width * (0.5f + (state.channelOffsetHz + state.passbandOffsetHz).toFloat() / state.spanHz)
                        val channelLeft = passbandCenter - channelWidth / 2f
                        val channelRight = passbandCenter + channelWidth / 2f
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(RadiolaColors.receiveChannel.copy(alpha = 20f / 255f), RadiolaColors.receiveChannel.copy(alpha = if (RadiolaColors.selected == RadiolaColors.Theme.LIGHT) .14f else 51f / 255f), RadiolaColors.receiveChannel.copy(alpha = 20f / 255f)),
                                startX = channelLeft, endX = channelRight
                            ),
                            Offset(channelLeft, 0f), Size(channelWidth, size.height)
                        )
                        drawLine(RadiolaColors.receiveChannel.copy(alpha = 0.5f), Offset(channelLeft, 0f), Offset(channelLeft, size.height), 1.dp.toPx())
                        drawLine(RadiolaColors.receiveChannel.copy(alpha = 0.5f), Offset(channelRight, 0f), Offset(channelRight, size.height), 1.dp.toPx())
                        drawLine(RadiolaColors.receiveChannel.copy(alpha = 201f / 255f), Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
                    }
                    Text(
                        "${state.fps} FPS",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color(0xA6111D30), RoundedCornerShape(4.dp))
                            .border(.5.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.White.copy(alpha = .9f), fontSize = 9.sp, lineHeight = 11.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().height(18.dp).padding(start = ReceiverPlotGeometry.frequencyInset, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (fraction in listOf(-0.5, -0.25, 0.0, 0.25, 0.5)) {
                    Text("%.3f".format(Locale.US, (state.centerHz + state.spanHz * fraction) / 1_000_000.0), color = muted, fontSize = 11.sp)
                }
            }
        }


    }
}

@Composable
private fun ReceiverPlotHeader(
    stations: List<com.example.sdr.favorites.FavoriteStation>,
    centerHz: Double,
    spanHz: Int,
    axisLabel: String?,
    automatic: Boolean,
    onAutomatic: () -> Unit,
    onFavorite: (com.example.sdr.favorites.FavoriteStation) -> Unit,
) {
    var controlsWidth by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().height(ReceiverPlotGeometry.headerHeight)) {
        if (axisLabel != null) {
            Text(axisLabel, Modifier.align(Alignment.CenterStart).padding(start = 5.dp),
                color = muted, fontSize = 11.sp)
        }
        FavoriteFrequencyMarkers(stations, centerHz, spanHz,
            Modifier.fillMaxSize().padding(start = ReceiverPlotGeometry.frequencyInset),
            endInset = with(LocalDensity.current) { controlsWidth.toDp() } + 8.dp,
            onSelect = onFavorite)
        Row(
            Modifier.align(Alignment.CenterEnd).onSizeChanged { controlsWidth = it.width }.padding(end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AUTO", color = if (automatic) warm else muted, fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = onAutomatic)
                    .background(if (automatic) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(.5.dp, if (automatic) warm else edge, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp))
            Text("SPAN %d kHz".format(Locale.US, spanHz / 1_000), color = muted, fontSize = 11.sp)
        }
    }
}
