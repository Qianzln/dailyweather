package com.dailyweather.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 天气语音播报（Android 平台自带 TextToSpeech，无需三方依赖）。
 *
 * 用法：应用初始化时创建（[TtsSpeaker] 持有一个 TextToSpeech 实例），
 * 播报前先 [speak]，完毕自动关闭。未初始化成功则安静失败（不崩溃）。
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 初始化（幂等）。完成后即可 [speak]。 */
    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.CHINESE
            }
        }
    }

    /** 播报一段中文文案。未就绪则跳过。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        init()
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "daily-weather-${System.currentTimeMillis()}")
    }

    /** 关闭释放（应用退出或不再使用时调用）。 */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
