package com.example.sdr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

internal data class PhoneLocationReading(val point: RadioPoint?, val status: String, val request: () -> Unit)

/** Location is requested only in the visible USB digital panel, never in the background. */
@Composable
internal fun rememberPhoneLocation(enabled: Boolean): PhoneLocationReading {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val manager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(permitted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var point by remember { mutableStateOf<RadioPoint?>(null) }
    var status by remember { mutableStateOf("LOCATION ACCESS") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission = permitted(); revision++
    }
    val request: () -> Unit = {
        if (!permitted()) {
            requested = true
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else revision++
    }
    LaunchedEffect(enabled) { if (enabled && !permission && !requested) request() }
    DisposableEffect(enabled, permission, revision, owner) {
        var listening = false
        lateinit var listener: LocationListener
        fun stop() { runCatching { manager.removeUpdates(listener) }; listening = false }
        fun start() {
            permission = permitted()
            if (!enabled || !permission || listening) {
                if (!permission) { point = null; status = "LOCATION ACCESS" }
                return
            }
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val providers = manager.getProviders(true).filter {
                it == LocationManager.NETWORK_PROVIDER || fine && it == LocationManager.GPS_PROVIDER || it == "fused"
            }
            if (providers.isEmpty()) { point = null; status = "ENABLE LOCATION"; return }
            point = null; status = "LOCATING..."
            val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..120_000_000_000L }
                .maxByOrNull { it.elapsedRealtimeNanos }
            recent?.let { point = RadioPoint(it.latitude, it.longitude); status = "PHONE" }
            providers.forEach { provider ->
                if (runCatching { manager.requestLocationUpdates(provider, 10_000L, 0f, listener, Looper.getMainLooper()) }.isSuccess) listening = true
            }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (enabled) { point = RadioPoint(location.latitude, location.longitude); status = "PHONE" }
            }
            override fun onProviderEnabled(provider: String) { revision++ }
            override fun onProviderDisabled(provider: String) { point = null; revision++ }
            @Deprecated("Legacy provider callback")
            override fun onStatusChanged(provider: String?, value: Int, extras: Bundle?) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        owner?.lifecycle?.addObserver(observer)
        if (owner == null || owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner?.lifecycle?.removeObserver(observer); stop() }
    }
    return PhoneLocationReading(if (enabled && permission) point else null, status) {
        if (permission && status == "ENABLE LOCATION") context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        else request()
    }
}
