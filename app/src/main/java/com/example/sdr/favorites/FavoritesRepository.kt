package com.example.sdr.favorites

import android.content.Context
import androidx.compose.runtime.*
import com.example.sdr.DemodulationMode
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.ReceiverSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FavoriteStation(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val frequencyHz: Long, val mode: DemodulationMode, val bandwidthHz: Int?,
    val source: ReceiverSource, val serverUrl: String? = null, val serverName: String = "",
    val bandId: String? = null, val bandName: String = "",
    val serverMode: String? = null, val ftx: FtxMode? = null,
) {
    val modeLabel get() = ftx?.name ?: serverMode?.uppercase() ?: mode.label
    fun sameSettings(other: FavoriteStation) = copy(id = "", name = "", serverName = "", bandName = "") ==
        other.copy(id = "", name = "", serverName = "", bandName = "")
}

/** Small, versioned local collection. Persistence is independent of receiver and UI. */
internal class FavoritesRepository(context: Context, storageName: String = "favorites") {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    var stations by mutableStateOf(read()); private set
    private fun read(): List<FavoriteStation> = runCatching {
        val rows = JSONObject(prefs.getString("collection", "{}")!!).optJSONArray("stations") ?: JSONArray()
        (0 until rows.length()).mapNotNull { index -> runCatching {
            val j = rows.getJSONObject(index)
            fun optional(key: String) = j.optString(key).takeIf { it.isNotBlank() }
            FavoriteStation(j.getString("id"), j.getString("name"), j.getLong("frequency"),
                DemodulationMode.valueOf(j.getString("mode")), if (j.has("bandwidth")) j.getInt("bandwidth") else null,
                ReceiverSource.valueOf(j.getString("source")), optional("url"), j.optString("server"),
                optional("bandId"), j.optString("band"), optional("serverMode"), optional("ftx")?.let(FtxMode::valueOf))
                .takeIf { it.frequencyHz > 0 && it.name.isNotBlank() }
        }.getOrNull() }
    }.getOrDefault(emptyList())
    fun save(value: FavoriteStation) {
        val station = value.copy(name = value.name.trim().take(100))
        if (station.name.isBlank()) return
        persist(if (stations.any { it.id == station.id }) stations.map { if (it.id == station.id) station else it }
            else stations + station)
    }
    fun delete(id: String) = persist(stations.filterNot { it.id == id })
    private fun persist(values: List<FavoriteStation>) {
        stations = values
        val rows = JSONArray()
        values.forEach { v -> rows.put(JSONObject().put("id", v.id).put("name", v.name)
            .put("frequency", v.frequencyHz).put("mode", v.mode.name).put("bandwidth", v.bandwidthHz)
            .put("source", v.source.name).put("url", v.serverUrl).put("server", v.serverName)
            .put("bandId", v.bandId).put("band", v.bandName).put("serverMode", v.serverMode).put("ftx", v.ftx?.name)) }
        prefs.edit().putString("collection", JSONObject().put("version", 1).put("stations", rows).toString()).apply()
    }
}
