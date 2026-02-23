package com.filestreaming.app

import android.app.Application
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * Custom Application class dla wersji VR.
 *
 * Nadpisuje gęstość wyświetlania (density) na niższą wartość,
 * co na goglach Meta Quest skutkuje WIĘKSZYM panelem 2D.
 * System VR przydziela więcej pikseli, bo apka „myśli" że ekran jest mniej gęsty.
 *
 * Wartość VR_DENSITY_DPI = 120 (zamiast domyślnych ~400–640 dpi gogli)
 * daje panel ok. 3–5× większy niż domyślny.
 */
class VrApp : Application() {

    companion object {
        /**
         * Docelowa gęstość (dpi) dla trybu VR.
         * Im niższa wartość, tym większy panel na goglach.
         * 120 dpi = dobry kompromis między rozmiarem panelu a czytelnością UI.
         */
        const val VR_DENSITY_DPI = 120
    }

    override fun onCreate() {
        super.onCreate()
        overrideDensity()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overrideDensity()
    }

    /**
     * Nadpisuje gęstość wyświetlania we WSZYSTKICH kontekstach aplikacji.
     * Musi być wywołane w Application, żeby działało globalnie.
     */
    private fun overrideDensity() {
        val density = VR_DENSITY_DPI / 160f  // 120/160 = 0.75

        // Nadpisz w zasobach Application
        resources.displayMetrics.apply {
            densityDpi = VR_DENSITY_DPI
            this.density = density
            scaledDensity = density
        }
        resources.configuration.densityDpi = VR_DENSITY_DPI
    }
}

