package com.moyeon.boardandgo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * 객실 소리: 음성(S1~S5)과 저음 차임.
 * 음성은 안드로이드 기본 TextToSpeech 로 낸다(에뮬레이터에서는 Pleos caas 음성 서비스가 엔진으로 등록돼 있음).
 * TTS 가 안 되면 화면 문구(승객 화면 자막) + 효과음으로 대신한다. 상태는 [ttsStatus]에 남는다.
 */
class CabinAudio(context: Context, private val onStatusChanged: () -> Unit) {
    companion object { const val TAG = "BoardAndGo" }

    private var tts: TextToSpeech? = null
    var ttsReady = false
        private set
    var ttsStatus = "초기화 중"
        private set
    var chimeStatus = "아직 안 냄"
        private set

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts
                    val lang = t?.setLanguage(Locale.KOREAN) ?: TextToSpeech.ERROR
                    t?.setPitch(0.8f) // 저음 화자에 가깝게
                    t?.setSpeechRate(0.9f)
                    t?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED && lang != TextToSpeech.ERROR
                    ttsStatus = if (ttsReady) "준비됨 (엔진 ${t?.defaultEngine}, 한국어 결과 $lang)"
                    else "한국어 미지원 (결과 $lang) — 화면 문구 + 효과음으로 대체"
                } else {
                    ttsReady = false
                    ttsStatus = "초기화 실패 (status $status) — 화면 문구 + 효과음으로 대체"
                }
                Log.i(TAG, "TTS $ttsStatus")
                onStatusChanged()
            }
        } catch (e: Exception) {
            ttsStatus = "생성 실패 ${e.javaClass.simpleName} — 화면 문구 + 효과음으로 대체"
            Log.w(TAG, "TTS $ttsStatus", e)
        }
    }

    /** 객실 음성. 실패하면 효과음(짧은 두 음)으로 대신하고, 문구는 승객 화면 자막으로 나간다. */
    fun speak(id: String, text: String) {
        val t = tts
        val result = if (ttsReady && t != null) {
            runCatching { t.speak(text, TextToSpeech.QUEUE_ADD, null, id) }.getOrDefault(TextToSpeech.ERROR)
        } else TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS speak 실패($result) $id → 효과음 대체")
            playTones(listOf(523.0 to 120, 392.0 to 200), "음성 대체 효과음")
        } else {
            Log.i(TAG, "TTS speak $id: $text")
        }
    }

    /** 초록 차임: 저음으로 한 번. */
    fun chime() = playTones(listOf(330.0 to 260, 262.0 to 420), "초록 차임")

    private fun playTones(tones: List<Pair<Double, Int>>, label: String) {
        Thread {
            try {
                val rate = 22050
                val samples = tones.sumOf { it.second * rate / 1000 }
                val buf = ShortArray(samples)
                var idx = 0
                tones.forEach { (hz, ms) ->
                    val n = ms * rate / 1000
                    for (i in 0 until n) {
                        val env = minOf(1.0, i / (rate * 0.01), (n - i) / (rate * 0.05))
                        buf[idx++] = (sin(2 * PI * hz * i / rate) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                chimeStatus = "$label 재생 호출 성공"
                Thread.sleep(tones.sumOf { it.second }.toLong() + 200)
                track.release()
            } catch (e: Exception) {
                chimeStatus = "$label 실패 ${e.javaClass.simpleName}"
                Log.w(TAG, chimeStatus, e)
            }
            onStatusChanged()
        }.start()
    }

    fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
