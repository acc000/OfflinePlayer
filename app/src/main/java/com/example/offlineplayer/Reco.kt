package com.example.offlineplayer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DeezerRec(val artist: String, val because: String, val tracks: List<String>)

// API publica de Deezer: no necesita clave ni cuenta. Solo se envian nombres de artistas.
object Deezer {
    private fun get(path: String): JSONObject {
        val c = URL("https://api.deezer.com$path").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            val txt = c.inputStream.bufferedReader().readText()
            Thread.sleep(120) // respeta el limite de peticiones por segundo
            return JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(a: String) = a.substringBefore(';').substringBefore('/').trim()

    fun recommend(songs: List<Song>, plays: Map<String, Int>): List<DeezerRec> {
        val owned = songs.map { clean(it.artist).lowercase() }.toSet()
        val seeds = songs.filter { it.artist != "Desconocido" }
            .groupBy { clean(it.artist) }
            .entries.sortedByDescending { e -> e.value.size + e.value.sumOf { plays[it.uri] ?: 0 } }
            .take(10).map { it.key }.shuffled().take(5)
        val out = mutableListOf<DeezerRec>()
        val seen = mutableSetOf<String>()
        for (seed in seeds) {
            val found = get("/search/artist?limit=1&q=${enc(seed)}").optJSONArray("data")?.optJSONObject(0)
            val id = found?.optLong("id", 0L) ?: 0L
            if (id == 0L) continue
            val rel = get("/artist/$id/related?limit=10").optJSONArray("data") ?: continue
            val cands = (0 until rel.length()).map { rel.getJSONObject(it) }.shuffled()
            var added = 0
            for (r in cands) {
                if (added >= 3) break
                val name = r.optString("name")
                val rid = r.optLong("id", 0L)
                if (name.isBlank() || rid == 0L || name.lowercase() in owned || !seen.add(name.lowercase())) continue
                val top = get("/artist/$rid/top?limit=3").optJSONArray("data")
                val tracks = if (top == null) emptyList() else (0 until top.length()).map { i ->
                    val t = top.getJSONObject(i)
                    t.optString("title_short").ifBlank { t.optString("title") }
                }.filter { it.isNotBlank() }
                out += DeezerRec(name, seed, tracks)
                added++
            }
        }
        return out
    }
}
