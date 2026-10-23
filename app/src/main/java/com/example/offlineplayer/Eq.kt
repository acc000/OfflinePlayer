package com.example.offlineplayer

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Ecualizador del sistema Android enganchado a la sesion de audio del reproductor.
// El servicio lo crea (attach); la pantalla de Ajustes lo controla.
object Eq {
    private var eq: Equalizer? = null
    private var store: Store? = null

    var available by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var presetIndex by mutableIntStateOf(-1); private set
    var minLevel by mutableIntStateOf(-1500); private set
    var maxLevel by mutableIntStateOf(1500); private set
    var freqs by mutableStateOf<List<Int>>(emptyList()); private set
    var levels by mutableStateOf<List<Int>>(emptyList()); private set
    var presets by mutableStateOf<List<String>>(emptyList()); private set

    fun attach(ctx: Context, sessionId: Int) {
        if (sessionId == 0) return
        release()
        val st = store ?: Store(ctx.applicationContext).also { store = it }
        try {
            val e = Equalizer(0, sessionId)
            eq = e
            val n = e.numberOfBands.toInt()
            minLevel = e.bandLevelRange[0].toInt()
            maxLevel = e.bandLevelRange[1].toInt()
            freqs = List(n) { e.getCenterFreq(it.toShort()) / 1000 }
            presets = List(e.numberOfPresets.toInt()) { e.getPresetName(it.toShort()) }
            val saved = st.eqLevels
            levels = if (saved.size == n) saved else List(n) { e.getBandLevel(it.toShort()).toInt() }
            presetIndex = st.eqPreset
            active = st.eqEnabled
            levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            e.setEnabled(active)
            available = true
        } catch (ex: Exception) {
            eq = null
            available = false
        }
    }

    fun turn(on: Boolean) {
        active = on
        eq?.setEnabled(on)
        store?.eqEnabled = on
    }

    fun setBand(i: Int, level: Int) {
        eq?.setBandLevel(i.toShort(), level.toShort())
        levels = levels.toMutableList().also { it[i] = level }
        presetIndex = -1
        persist()
    }

    fun usePreset(i: Int) {
        val e = eq ?: return
        e.usePreset(i.toShort())
        levels = List(freqs.size) { e.getBandLevel(it.toShort()).toInt() }
        presetIndex = i
        persist()
        if (!active) turn(true)
    }

    fun resetFlat() {
        val e = eq ?: return
        levels = List(freqs.size) { 0 }
        levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
        presetIndex = -1
        persist()
    }

    private fun persist() {
        store?.eqLevels = levels
        store?.eqPreset = presetIndex
    }

    fun release() {
        eq?.release()
        eq = null
        available = false
    }
}
