package com.filestreaming.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.util.Locale

/**
 * Ekran główny — przeglądarka plików na serwerze HTTP.
 *
 * Pozwala:
 * - wpisać adres serwera (np. http://192.168.1.100:8080)
 * - pobrać listę plików z endpointu /api/files
 * - nawigować po katalogach
 * - kliknąć plik, by streamować go w ExoPlayer
 *
 * Obsługuje tryby sortowania i wyciszenia przekazane z LauncherActivity.
 *
 * Serwer powinien wystawiać:
 *   GET /api/files?path=          → JSON z listą plików/katalogów
 *   GET /media/<ścieżka do pliku> → sam plik (z obsługą Range)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "file_streaming_prefs"
        private const val KEY_SERVER_URL = "server_url"

        private val MEDIA_EXTENSIONS = setOf(
            "mp3", "mp4", "avi", "mkv", "flv", "wmv",
            "mov", "m4v", "flac", "wav", "ogg", "aac",
            "wma", "m4a", "webm", "3gp", "ts", "m2ts",
            "m3u8"
        )
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var urlInput: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var pathLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnPlayFromJson: MaterialButton
    private lateinit var startIndexInput: EditText

    // --- Muzyka w tle (opcja "Załaduj z JSON") ---
    private lateinit var btnSelectBackgroundAudio: MaterialButton
    private lateinit var backgroundAudioLabel: TextView
    private lateinit var audioStartMinutesInput: EditText
    private lateinit var audioStartSecondsInput: EditText
    private var selectedBackgroundAudioUri: Uri? = null
    private lateinit var backgroundAudioOptionContainer: LinearLayout

    private val pickBackgroundAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onBackgroundAudioPicked(it) }
        }

    private var baseUrl: String = ""
    private var currentPath: String = ""
    private var fileList: MutableList<FileItem> = mutableListOf()
    private lateinit var adapter: FileAdapter

    // --- Tryb odtwarzania (z LauncherActivity) ---
    private var sortMode: Int = LauncherActivity.SORT_NATURAL
    private var isMuted: Boolean = false

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Odczytaj parametry trybu z Intentu
        sortMode = intent.getIntExtra(LauncherActivity.EXTRA_SORT_MODE, LauncherActivity.SORT_NATURAL)
        isMuted = intent.getBooleanExtra(LauncherActivity.EXTRA_MUTED, false)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        setupRecyclerView()

        // Przywróć ostatni adres
        val savedUrl = prefs.getString(KEY_SERVER_URL, "http://192.168.1.100:8080") ?: ""
        urlInput.setText(savedUrl)

        // Pokaż aktywny tryb
        modeLabel.text = when {
            isMuted -> getString(R.string.mode_muted)
            sortMode == LauncherActivity.SORT_LOCALE -> getString(R.string.mode_windows)
            else -> getString(R.string.mode_standard)
        }
    }

    // =========================================================================
    // Init
    // =========================================================================

    private fun initViews() {
        urlInput = findViewById(R.id.urlInput)
        btnConnect = findViewById(R.id.btnConnect)
        recyclerView = findViewById(R.id.recyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        statusLabel = findViewById(R.id.statusLabel)
        pathLabel = findViewById(R.id.pathLabel)
        modeLabel = findViewById(R.id.modeLabel)
        btnBack = findViewById(R.id.btnBack)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        setupStartIndexInput()
        setupPlayFromJsonButton()

        btnConnect.setOnClickListener { connectToServer() }
        btnBack.setOnClickListener { navigateUp() }
        btnPlayAll.setOnClickListener { playAllFiles() }

        swipeRefresh.setOnRefreshListener {
            if (baseUrl.isNotEmpty()) {
                fetchFiles(currentPath)
            } else {
                swipeRefresh.isRefreshing = false
            }
        }

        // Obsługa "Enter" w polu URL
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                connectToServer()
                true
            } else false
        }
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter(fileList) { item ->
            if (item.isDirectory) {
                // Wejdź do katalogu
                val newPath = if (currentPath.isEmpty()) item.name
                              else "$currentPath/${item.name}"
                fetchFiles(newPath)
            } else {
                // Streamuj plik
                playFile(item)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    /**
     * Tworzy programowo pole tekstowe "zacznij od pozycji N" obok btnPlayAll,
     * żeby można było wystartować odtwarzanie od wybranego miejsca na liście
     * (np. od 17-go pliku), bez konieczności przewijania i klikania w niego.
     *
     * Tworzone w kodzie (nie w XML), żeby nie wymagać zmian w istniejącym layoucie.
     */
    private fun setupStartIndexInput() {
        val parent = btnPlayAll.parent as? ViewGroup ?: return
        val btnIndex = parent.indexOfChild(btnPlayAll)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = "Zacznij od #:"
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }

        startIndexInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1"
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            textSize = 12f
        }

        row.addView(label)
        row.addView(startIndexInput)

        if (parent is LinearLayout) {
            parent.addView(row, btnIndex + 1)
        } else {
            parent.addView(row)
        }
    }

    /**
     * Tworzy programowo przycisk "Załaduj z JSON" obok btnConnect.
     *
     * Widoczny od razu po połączeniu z serwerem, niezależnie od katalogu,
     * w którym aktualnie znajduje się użytkownik w przeglądarce plików —
     * woła /api/playlist (tylko pliki z --playlist, w kolejności z JSON-a),
     * ignorując bieżący fileList z przeglądarki.
     *
     * Obok przycisku dodawane są też kontrolki muzyki w tle (wybór pliku +
     * minuty/sekundy startu) — to "opcja 2": katalog i "Odtwórz wszystko"
     * to opcja 1, a tu jest niezależna ścieżka z muzyką ustawioną z góry.
     *
     * Tworzony w kodzie (nie w XML), żeby nie wymagać zmian w istniejącym layoucie.
     */
    private fun setupPlayFromJsonButton() {
        val parent = btnConnect.parent as? ViewGroup ?: return
        val btnIndex = parent.indexOfChild(btnConnect)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun wrapParams(topMarginDp: Int) = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp) }
        } else {
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp) }
        }

        // --- Kontener "opcja 2": muzyka w tle + start od minuty/sekundy + Załaduj z JSON ---
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapParams(12)
            visibility = View.GONE
        }

        btnSelectBackgroundAudio = MaterialButton(this).apply {
            text = "Wybierz muzykę w tle"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { pickBackgroundAudio.launch(arrayOf("audio/*")) }
        }

        backgroundAudioLabel = TextView(this).apply {
            text = "Brak wybranej muzyki"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val timeLabel = TextView(this).apply {
            text = "Start audio (min:sek):"
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }

        audioStartMinutesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "min"
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            textSize = 12f
        }

        val timeSeparator = TextView(this).apply {
            text = ":"
            setPadding(dp(4), 0, dp(4), 0)
        }

        audioStartSecondsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "sek"
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            textSize = 12f
        }

        timeRow.addView(timeLabel)
        timeRow.addView(audioStartMinutesInput)
        timeRow.addView(timeSeparator)
        timeRow.addView(audioStartSecondsInput)

        btnPlayFromJson = MaterialButton(this).apply {
            text = "Załaduj z JSON"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { playFromJsonPlaylist() }
        }

        container.addView(btnSelectBackgroundAudio)
        container.addView(backgroundAudioLabel)
        container.addView(timeRow)
        container.addView(btnPlayFromJson)

        // container jest tym, co pokazujemy/chowamy po połączeniu — btnPlayFromJson
        // ma być widoczny razem z resztą kontrolek audio w tym samym momencie
        if (parent is LinearLayout) {
            parent.addView(container, btnIndex + 1)
        } else {
            parent.addView(container)
        }

        backgroundAudioOptionContainer = container
    }

    /**
     * Wywoływane po wybraniu pliku audio z systemowego pickera.
     * Zachowuje trwałe uprawnienia do odczytu, żeby URI działało
     * również po ponownym uruchomieniu aplikacji.
     */
    private fun onBackgroundAudioPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}

        selectedBackgroundAudioUri = uri
        backgroundAudioLabel.text = "Muzyka: ${getAudioFilenameFromUri(uri)}"
    }

    private fun getAudioFilenameFromUri(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "audio"
    }

    /**
     * Pobiera playlistę z /api/playlist (tylko pliki z --playlist, w kolejności
     * z JSON-a po stronie serwera) i odtwarza ją, ignorując bieżący katalog
     * i fileList z przeglądarki. Uwzględnia pole "Zacznij od #" oraz wybraną
     * muzykę w tle wraz z pozycją startu (minuty:sekundy).
     */
    private fun playFromJsonPlaylist() {
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_url), Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        statusLabel.text = getString(R.string.connecting)

        Thread {
            try {
                val apiUrl = "$baseUrl/api/playlist"
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    throw Exception("HTTP $responseCode")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()

                val array = JSONArray(response)
                val names = mutableListOf<String>()
                val paths = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    names.add(obj.getString("name"))
                    paths.add(obj.getString("path"))
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE

                    if (names.isEmpty()) {
                        statusLabel.text = getString(R.string.no_media_files)
                        Toast.makeText(this, getString(R.string.no_media_files), Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val urls = paths.map { p -> "$baseUrl/media/${p.replace(" ", "%20")}" }.toTypedArray()

                    // Odczytaj numer startowej pozycji (1-indexed dla użytkownika)
                    val typedNumber = startIndexInput.text.toString().toIntOrNull()
                    val startFrom = if (typedNumber != null && typedNumber >= 1) {
                        (typedNumber - 1).coerceAtMost(names.size - 1)
                    } else {
                        0
                    }

                    // Punkt startu muzyki w tle (minuty + sekundy -> ms)
                    val minutes = audioStartMinutesInput.text.toString().toLongOrNull() ?: 0L
                    val seconds = audioStartSecondsInput.text.toString().toLongOrNull() ?: 0L
                    val startMs = (minutes * 60 + seconds) * 1000L

                    PlaylistHolder.urls = urls
                    PlaylistHolder.names = names.toTypedArray()
                    PlaylistHolder.startIndex = startFrom
                    PlaylistHolder.isMuted = isMuted
                    PlaylistHolder.backgroundAudioUri = selectedBackgroundAudioUri?.toString()
                    PlaylistHolder.backgroundAudioStartMs = startMs

                    startActivity(Intent(this, StreamPlayerActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    statusLabel.text = getString(R.string.error_format, e.message ?: "?")
                    Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // =========================================================================
    // Połączenie z serwerem
    // =========================================================================

    private fun connectToServer() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_url), Toast.LENGTH_SHORT).show()
            return
        }

        baseUrl = url
        currentPath = ""

        // Zapisz URL
        prefs.edit().putString(KEY_SERVER_URL, url).apply()

        // Ukryj klawiaturę
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)

        fetchFiles("")
    }

    private fun fetchFiles(path: String) {
        progressBar.visibility = View.VISIBLE
        statusLabel.text = getString(R.string.connecting)

        Thread {
            try {
                val encodedPath = path.replace(" ", "%20")
                val apiUrl = if (encodedPath.isEmpty()) "$baseUrl/api/files"
                             else "$baseUrl/api/files?path=$encodedPath"

                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    throw Exception("HTTP $responseCode")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()

                val items = parseFileList(response)

                runOnUiThread {
                    currentPath = path
                    fileList.clear()
                    fileList.addAll(items)
                    adapter.notifyDataSetChanged()

                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false

                    val mediaCount = items.count { !it.isDirectory }
                    statusLabel.text = getString(R.string.loaded_format, items.size, mediaCount)
                    pathLabel.text = if (path.isEmpty()) "/" else "/$path"

                    btnBack.visibility = if (path.isEmpty()) View.GONE else View.VISIBLE
                    btnPlayAll.visibility = if (mediaCount > 0) View.VISIBLE else View.GONE

                    // Po pierwszym udanym połączeniu pokaż "opcję 2": kontrolki
                    // muzyki w tle + przycisk "Załaduj z JSON" (niezależnie od
                    // katalogu — zostaje widoczne przy nawigacji)
                    backgroundAudioOptionContainer.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    statusLabel.text = getString(R.string.error_format, e.message ?: "?")
                    Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun parseFileList(json: String): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val isDir = obj.optBoolean("is_dir", false)
            val size = obj.optLong("size", 0)

            items.add(FileItem(name, isDir, size))
        }

        // Sortuj: katalogi na górze, potem pliki
        return items.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    // =========================================================================
    // Nawigacja
    // =========================================================================

    private fun navigateUp() {
        if (currentPath.isEmpty()) return

        val parentPath = if (currentPath.contains("/")) {
            currentPath.substringBeforeLast("/")
        } else {
            ""
        }

        fetchFiles(parentPath)
    }

    // =========================================================================
    // Sortowanie plików multimedialnych wg trybu
    // =========================================================================

    /**
     * Sortuje pliki multimedialne wg wybranego trybu.
     * - SORT_NATURAL: sortowanie naturalne (1, 2, 10)
     * - SORT_LOCALE: sortowanie jak w Windows (Collator z bieżącym locale)
     */
    private fun sortMediaFiles(files: List<FileItem>): List<FileItem> {
        return when (sortMode) {
            LauncherActivity.SORT_LOCALE -> {
                val collator = Collator.getInstance()
                files.sortedWith(compareBy(collator) { it.name })
            }
            else -> {
                // Sortowanie naturalne: 1, 2, 10 (nie 1, 10, 2)
                files.sortedWith(compareBy { naturalSortKey(it.name) })
            }
        }
    }

    /**
     * Klucz sortowania naturalnego — uwzględnia liczby w nazwie pliku.
     * Przykład: "video2.mp4" < "video10.mp4"
     */
    private fun naturalSortKey(filename: String): NaturalSortKey {
        val lower = filename.lowercase(Locale.ROOT)
        val parts = mutableListOf<Comparable<*>>()
        val regex = Regex("(\\d+)")
        var lastEnd = 0

        for (match in regex.findAll(lower)) {
            if (match.range.first > lastEnd) {
                parts.add(lower.substring(lastEnd, match.range.first))
            }
            parts.add(match.value.toLongOrNull() ?: 0L)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < lower.length) {
            parts.add(lower.substring(lastEnd))
        }
        return NaturalSortKey(parts)
    }

    private class NaturalSortKey(private val parts: List<Comparable<*>>) : Comparable<NaturalSortKey> {
        override fun compareTo(other: NaturalSortKey): Int {
            for (i in 0 until minOf(parts.size, other.parts.size)) {
                val a = parts[i]
                val b = other.parts[i]
                @Suppress("UNCHECKED_CAST")
                val cmp = when {
                    a is String && b is String -> a.compareTo(b)
                    a is Long && b is Long -> a.compareTo(b)
                    a is String -> -1
                    else -> 1
                }
                if (cmp != 0) return cmp
            }
            return parts.size.compareTo(other.parts.size)
        }
    }

    // =========================================================================
    // Odtwarzanie
    // =========================================================================

    private fun playFile(item: FileItem) {
        // Pliki multimedialne w katalogu — posortowane wg wybranego trybu
        val mediaFiles = sortMediaFiles(
            fileList.filter { !it.isDirectory && isMediaFile(it.name) }
        )
        val urls = mediaFiles.map { f ->
            val p = if (currentPath.isEmpty()) f.name else "$currentPath/${f.name}"
            "$baseUrl/media/${p.replace(" ", "%20")}"
        }.toTypedArray()
        val names = mediaFiles.map { it.name }.toTypedArray()

        val startIndex = mediaFiles.indexOfFirst { it.name == item.name }.coerceAtLeast(0)

        // Zapisz playlistę w singletonie (zamiast Intent extras — unika limitu ~500KB)
        PlaylistHolder.urls = urls
        PlaylistHolder.names = names
        PlaylistHolder.startIndex = startIndex
        PlaylistHolder.isMuted = isMuted

        val intent = Intent(this, StreamPlayerActivity::class.java)
        startActivity(intent)
    }

    private fun playAllFiles() {
        val mediaFiles = sortMediaFiles(
            fileList.filter { !it.isDirectory && isMediaFile(it.name) }
        )
        if (mediaFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_media_files), Toast.LENGTH_SHORT).show()
            return
        }

        // Odczytaj numer startowej pozycji (1-indexed dla użytkownika, np. 17 = 17. plik)
        val typedNumber = startIndexInput.text.toString().toIntOrNull()
        val startFrom = if (typedNumber != null && typedNumber >= 1) {
            (typedNumber - 1).coerceAtMost(mediaFiles.size - 1)
        } else {
            0
        }

        playFile(mediaFiles[startFrom])
    }

    private fun isMediaFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    // =========================================================================
    // Back button
    // =========================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPath.isNotEmpty()) {
            navigateUp()
        } else {
            super.onBackPressed()
        }
    }
}
