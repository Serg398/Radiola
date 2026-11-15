package com.example.sdr.favorites

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.DemodulationMode
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.ReceiverSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesRepositoryTest {
    @Test fun preservesProtocolIdentityDefaultBandwidthAndEditsAcrossRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = "favorites-test"
        context.getSharedPreferences(storage, 0).edit().clear().commit()
        try {
            val repo = FavoritesRepository(context, storage)
            val saved = FavoriteStation(name = "CW station", frequencyHz = 7_030_000,
                mode = DemodulationMode.CW, bandwidthHz = null, source = ReceiverSource.OPEN_WEB_RX,
                serverUrl = "https://example.org/", serverName = "Receiver", bandId = "sdr|80m", bandName = "80 m", serverMode = "cw")
            repo.save(saved)
            repo.save(saved.copy(name = "Renamed"))
            val reloaded = FavoritesRepository(context, storage)
            assertEquals(listOf(saved.copy(name = "Renamed")), reloaded.stations)
            val ft = saved.copy(id = "ft", mode = DemodulationMode.USB, bandwidthHz = 3000, serverMode = null, ftx = FtxMode.FT8)
            reloaded.save(ft)
            assertFalse(ft.sameSettings(ft.copy(ftx = null, serverMode = "ft8")))
            assertTrue(saved.sameSettings(saved.copy(name = "Different", id = "different")))
            reloaded.delete(saved.id)
            assertEquals(listOf(ft), FavoritesRepository(context, storage).stations)
        } finally { context.getSharedPreferences(storage, 0).edit().clear().commit() }
    }
}
