package com.filestreaming.app

/**
 * Singleton przechowujący dane playlisty w pamięci.
 *
 * Rozwiązuje problem TransactionTooLargeException — Android ma limit ~500KB
 * na dane przekazywane przez Intent extras. Przy dużej ilości plików
 * (setki/tysiące) tablice URL-i mogą ten limit przekroczyć.
 *
 * Zamiast tego: MainActivity zapisuje playlistę tutaj,
 * a StreamPlayerActivity ją odczytuje.
 */
object PlaylistHolder {
    var urls: Array<String> = emptyArray()
    var names: Array<String> = emptyArray()
    var startIndex: Int = 0
    var isMuted: Boolean = false

    // Muzyka w tle ustawiona z MainActivity (opcja "Załaduj z JSON") —
    // String, nie Uri, bo Uri nie jest potrzebny do parcelowania, a String
    // wystarczy do Uri.parse() po stronie StreamPlayerActivity.
    var backgroundAudioUri: String? = null
    var backgroundAudioStartMs: Long = 0L

    fun clear() {
        urls = emptyArray()
        names = emptyArray()
        startIndex = 0
        isMuted = false
        backgroundAudioUri = null
        backgroundAudioStartMs = 0L
    }

    fun hasData(): Boolean = urls.isNotEmpty()
}

