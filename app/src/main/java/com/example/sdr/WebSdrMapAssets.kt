package com.example.sdr

import android.content.Context
import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class MapCity(val name: String, val lon: Float, val lat: Float, val minZoom: Float, val population: Long)

/** Immutable geometry shared by every map visit; parsing never runs on the UI thread. */
internal object WebSdrMapAssets {
    @Volatile private var savedCities: List<MapCity>? = null
    @Volatile private var savedLand: List<Path>? = null
    suspend fun cities(context: Context): List<MapCity> = withContext(Dispatchers.IO) {
        savedCities ?: synchronized(this@WebSdrMapAssets) {
            savedCities ?: run {
            val data = context.assets.open("map/cities.json").bufferedReader().use { JSONArray(it.readText()) }
            (0 until data.length()).map { i ->
                val city = data.getJSONObject(i)
                MapCity(city.getString("name"), city.getDouble("lon").toFloat(), city.getDouble("lat").toFloat(),
                    city.getDouble("zoom").toFloat(), city.getLong("population"))
            }.sortedByDescending { it.population }
            }.also { savedCities = it }
        }
    }
    suspend fun land(context: Context): List<Path> = withContext(Dispatchers.IO) {
        savedLand ?: synchronized(this@WebSdrMapAssets) {
            savedLand ?: run {
                val json = context.assets.open("map/land.geojson").bufferedReader().use { JSONObject(it.readText()) }
                buildList {
                    val features = json.getJSONArray("features")
                    for (i in 0 until features.length()) {
                        val geometry = features.getJSONObject(i).getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")
                        val polygons = if (geometry.getString("type") == "Polygon") listOf(coordinates)
                            else (0 until coordinates.length()).map { coordinates.getJSONArray(it) }
                        for (polygon in polygons) add(Path().apply {
                            for (r in 0 until polygon.length()) {
                                val ring = polygon.getJSONArray(r)
                                for (p in 0 until ring.length()) {
                                    val point = ring.getJSONArray(p)
                                    val x = point.getDouble(0).toFloat()
                                    val y = -point.getDouble(1).toFloat()
                                    if (p == 0) moveTo(x, y) else lineTo(x, y)
                                }
                                close()
                            }
                        })
                    }
                }
            }.also { savedLand = it }
        }
    }
}
