package com.example.offlineplayer

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private var plays: Map<String, Int> = store.plays

    var songs by mutableStateOf(store.songs); private set
    var playlists by mutableStateOf(store.playlists); private set
    var folders by mutableStateOf(store.folders); private set
    var apiKey by mutableStateOf(store.apiKey); private set
    var scanning by mutableStateOf(false); private set
    var aiLoading by mutableStateOf(false); private set
    var aiResult by mutableStateOf<List<AiPlaylist>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)

    var controller by mutableStateOf<MediaController?>(null); private set
    var current by mutableStateOf<MediaItem?>(null); private set
    var playing by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            current = mediaItem
            mediaItem?.mediaId?.let { id -> plays = plays + (id to ((plays[id] ?: 0) + 1)); store.plays = plays }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
        override fun onRepeatModeChanged(repeatMode: Int) { repeat = repeatMode }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlayerService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            val c = f.get()
            c.addListener(listener)
            current = c.currentMediaItem; playing = c.isPlaying; shuffle = c.shuffleModeEnabled; repeat = c.repeatMode
            controller = c
        }, ContextCompat.getMainExecutor(app))
    }

    // ---- Reproduccion ----
    private fun item(s: Song) = MediaItem.Builder().setMediaId(s.uri).setUri(s.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).build()).build()

    fun play(list: List<Song>, index: Int = 0) {
        val c = controller ?: return
        c.setMediaItems(list.map { item(it) }, index, 0L); c.prepare(); c.play()
    }
    fun playShuffled(list: List<Song>) {
        if (list.isEmpty()) return
        controller?.shuffleModeEnabled = true
        play(list, list.indices.random())
    }
    fun playUris(uris: List<String>, shuffled: Boolean = false) {
        val m = songs.associateBy { it.uri }
        val l = uris.mapNotNull { m[it] }
        if (shuffled) playShuffled(l) else if (l.isNotEmpty()) play(l, 0)
    }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
    fun prev() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ---- Carpetas ----
    fun addFolder(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folders = folders + uri.toString(); store.folders = folders; rescan()
    }
    fun removeFolder(u: String) { folders = folders - u; store.folders = folders; rescan() }
    fun rescan() {
        scanning = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { MusicScanner.scan(getApplication(), folders) }
            songs = r; store.songs = r; scanning = false
        }
    }

    // ---- Listas ----
    private fun savePl(l: List<Playlist>) { playlists = l; store.playlists = l }
    fun createPlaylist(name: String, uris: List<String> = emptyList()) {
        if (playlists.any { it.name == name }) { message = "Ya existe una lista con ese nombre"; return }
        savePl(playlists + Playlist(name, uris))
    }
    fun deletePlaylist(name: String) = savePl(playlists.filter { it.name != name })
    fun addToPlaylist(name: String, uri: String) =
        savePl(playlists.map { if (it.name == name && uri !in it.uris) it.copy(uris = it.uris + uri) else it })
    fun removeFromPlaylist(name: String, uri: String) =
        savePl(playlists.map { if (it.name == name) it.copy(uris = it.uris - uri) else it })

    // ---- IA ----
    fun saveKey(k: String) { apiKey = k.trim(); store.apiKey = apiKey; message = "Clave guardada" }
    fun recommend() {
        if (apiKey.isBlank()) { message = "A\u00f1ade tu clave de API en Ajustes"; return }
        if (songs.isEmpty()) { message = "Primero a\u00f1ade una carpeta con m\u00fasica"; return }
        aiLoading = true
        viewModelScope.launch {
            try { aiResult = withContext(Dispatchers.IO) { Ai.recommend(apiKey, songs, plays) } }
            catch (e: Exception) { message = "Error IA: ${e.message}" }
            aiLoading = false
        }
    }
}
