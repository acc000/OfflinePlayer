package com.example.offlineplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Song(val uri: String, val title: String, val artist: String, val album: String, val genre: String, val durationMs: Long)
data class Playlist(val name: String, val uris: List<String>)
data class AiPlaylist(val name: String, val reason: String, val uris: List<String>)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)

    var folders: Set<String>
        get() = sp.getStringSet("folders", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("folders", v).apply() }

    var apiKey: String
        get() = sp.getString("key", "")!!
        set(v) { sp.edit().putString("key", v).apply() }

    var songs: List<Song>
        get() {
            val a = JSONArray(sp.getString("songs", "[]"))
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Song(o.getString("u"), o.getString("t"), o.getString("a"), o.getString("al"), o.getString("g"), o.getLong("d"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("u", it.uri).put("t", it.title).put("a", it.artist).put("al", it.album).put("g", it.genre).put("d", it.durationMs)) }
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
        fun walk(d: DocumentFile) {
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f)
                else {
                    val ext = f.name.orEmpty().substringAfterLast('.').lowercase()
                    if (f.type?.startsWith("audio/") == true || ext in exts) out += read(ctx, f)
                }
            }
        }
        folders.forEach { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.let { d -> walk(d) } }
        return out.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }

    private fun read(ctx: Context, f: DocumentFile): Song {
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
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            Song(f.uri.toString(), fallback, "Desconocido", "Desconocido", "Desconocido", 0L)
        } finally {
            r.release()
        }
    }
}

object Ai {
    const val MODEL = "claude-sonnet-5-5"

    // Solo se envian metadatos (titulo, artista, genero, reproducciones). Nunca los archivos.
    fun recommend(key: String, songs: List<Song>, plays: Map<String, Int>): List<AiPlaylist> {
        val list = (songs.sortedByDescending { plays[it.uri] ?: 0 }.take(60) + songs.shuffled().take(140))
            .distinctBy { it.uri }.take(200)
        val lines = list.mapIndexed { i, s -> "$i|${s.title}|${s.artist}|${s.genre}|${plays[s.uri] ?: 0}" }.joinToString("\n")
        val prompt = "Eres un curador musical. Esta es la biblioteca del usuario en formato id|titulo|artista|genero|reproducciones:\n$lines\n\n" +
            "Crea de 4 a 6 listas de reproduccion tematicas (estado de animo, genero, energia, momento del dia...) usando solo los ids dados. " +
            "Responde UNICAMENTE con JSON: {\"playlists\":[{\"name\":\"...\",\"reason\":\"frase corta en espanol\",\"ids\":[0,1]}]}"
        val body = JSONObject().put("model", MODEL).put("max_tokens", 2000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) error("HTTP $code: ${txt.take(160)}")
        val text = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
        val arr = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).getJSONArray("playlists")
        return List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            val ids = p.getJSONArray("ids")
            AiPlaylist(p.getString("name"), p.optString("reason"), (0 until ids.length()).mapNotNull { list.getOrNull(ids.getInt(it))?.uri })
        }.filter { it.uris.isNotEmpty() }
    }
}
