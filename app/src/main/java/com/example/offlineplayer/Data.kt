package com.example.offlineplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Song(val uri: String, val title: String, val artist: String, val album: String, val genre: String, val durationMs: Long, val folder: String = "")
data class Playlist(val name: String, val uris: List<String>)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)

    var folders: Set<String>
        get() = sp.getStringSet("folders", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("folders", v).apply() }

    var apiKey: String
        get() = sp.getString("key", "")!!
        set(v) { sp.edit().putString("key", v).apply() }

    var eqEnabled: Boolean
        get() = sp.getBoolean("eqOn", false)
        set(v) { sp.edit().putBoolean("eqOn", v).apply() }

    var eqPreset: Int
        get() = sp.getInt("eqPreset", -1)
        set(v) { sp.edit().putInt("eqPreset", v).apply() }

    var eqLevels: List<Int>
        get() = sp.getString("eqLv", "")!!.split(",").mapNotNull { it.toIntOrNull() }
        set(v) { sp.edit().putString("eqLv", v.joinToString(",")).apply() }

    var fontIndex: Int
        get() = sp.getInt("font", 2)
        set(v) { sp.edit().putInt("font", v).apply() }

    var songs: List<Song>
        get() {
            val a = JSONArray(sp.getString("songs", "[]"))
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Song(o.getString("u"), o.getString("t"), o.getString("a"), o.getString("al"), o.getString("g"), o.getLong("d"), o.optString("f", ""))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("u", it.uri).put("t", it.title).put("a", it.artist).put("al", it.album).put("g", it.genre).put("d", it.durationMs).put("f", it.folder)) }
            sp.edit().putString("songs", a.toString()).apply()
        }

    var playlists: List<Playlist>
        get() {
            val a = JSONArray(sp.getString("pl", "[]"))
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val u = o.getJSONArray("u")
                Playlist(o.getString("n"), List(u.length()) { u.getString(it) })
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("n", it.name).put("u", JSONArray(it.uris))) }
            sp.edit().putString("pl", a.toString()).apply()
        }

    var plays: Map<String, Int>
        get() {
            val o = JSONObject(sp.getString("plays", "{}"))
            return o.keys().asSequence().associateWith { o.getInt(it) }
        }
        set(v) { sp.edit().putString("plays", JSONObject(v).toString()).apply() }
}

object MusicScanner {
    private val exts = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac")

    fun scan(ctx: Context, folders: Set<String>): List<Song> {
        val out = mutableListOf<Song>()
        fun walk(d: DocumentFile, root: String) {
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f, root)
                else {
                    val ext = f.name.orEmpty().substringAfterLast('.').lowercase()
                    if (f.type?.startsWith("audio/") == true || ext in exts) out += read(ctx, f, root)
                }
            }
        }
        folders.forEach { root -> DocumentFile.fromTreeUri(ctx, Uri.parse(root))?.let { d -> walk(d, root) } }
        return out.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }

    private fun read(ctx: Context, f: DocumentFile, root: String): Song {
        val fallback = f.name.orEmpty().substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, f.uri)
            fun m(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
            Song(
                f.uri.toString(),
                m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallback,
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Desconocido",
                m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido",
                m(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Desconocido",
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                root
            )
        } catch (e: Exception) {
            Song(f.uri.toString(), fallback, "Desconocido", "Desconocido", "Desconocido", 0L, root)
        } finally {
            r.release()
        }
    }
}
