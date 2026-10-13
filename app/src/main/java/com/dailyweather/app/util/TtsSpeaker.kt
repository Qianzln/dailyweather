package com.dailyweather.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 天气语音播报（Android 平台自带 TextToSpeech，无需三方依赖）。
 *
 * 用法：应用初始化时创建（[TtsSpeaker] 持有一个 TextToSpeech 实例），
 * 播报前 [speak]，完毕自动置 [speaking]=false；[stop] 可中断。
 * 未初始化成功则安静失败（不崩溃）。
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var utteranceId: String? = null

    private val _speaking = MutableStateFlow(false)
    /** 是否正在播报 —— 驱动播报均衡器动画与按钮语义（南风 isSpeaking 的等价物）。 */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 初始化（幂等）。完成后即可 [speak]。 */
    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id == utteranceId) _speaking.value = true
                    }

                    override fun onDone(id: String?) {
                        if (id == utteranceId) _speaking.value = false
                    }

                    override fun onError(id: String?) {
                        if (id == utteranceId) _speaking.value = false
                    }
                })
            }
        }
    }

    /** 播报一段中文文案（替换队列）。未就绪则跳过。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        init()
        if (!ready) return
        utteranceId = "daily-weather-${System.currentTimeMillis()}"
        _speaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** 停止当前播报。 */
    fun stop() {
        utteranceId = null
        _speaking.value = false
        tts?.stop()
    }

    /** 关闭释放（应用退出或不再使用时调用）。 */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
