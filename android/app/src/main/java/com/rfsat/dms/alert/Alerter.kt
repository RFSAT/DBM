package com.rfsat.dms.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.Severity
import java.util.Locale

/** Audio alerts: short tone for WARNING, urgent tone + spoken message for CRITICAL. */
class Alerter(context: Context) {

    @Volatile var audioEnabled = true
    @Volatile var ttsEnabled = true

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 45)
    private var ttsReady = false
    private val tts = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) ttsReady = true }
        .apply { language = Locale.UK }
    private var lastSpokenMs = 0L

    fun alert(ev: RiskEventCandidate) {
        val now = System.currentTimeMillis()
        when (ev.severity) {
            Severity.CRITICAL -> {
                if (audioEnabled) tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600)
                if (ttsEnabled && ttsReady && now - lastSpokenMs > 4000) {
                    lastSpokenMs = now
                    tts.speak(ev.type.description, TextToSpeech.QUEUE_FLUSH, null, ev.type.name)
                }
            }
            Severity.WARNING -> { if (audioEnabled) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }
            Severity.INFO -> Unit
        }
    }

    fun release() { tone.release(); tts.shutdown() }
}
