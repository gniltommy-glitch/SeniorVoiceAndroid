package com.khkt.seniorvoice.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Tầng 1: Quản lý Nhận diện Giọng nói (STT) & Phát Giọng nói Tiếng Việt (TTS) Nội bộ Android
 * - Đã tinh chỉnh khoảng lặng chờ (Pause-Tolerance 3.5s - 4.0s) để không ngắt lời khi ông bà ngập ngừng (M8, T14).
 * - Cảnh báo trực quan nếu thiết bị thiếu gói giọng đọc TTS Tiếng Việt (T13).
 */
class VietnameseSpeechManager(
    private val context: Context,
    private val onFinalSpeechResult: (text: String, isConfirmationTurn: Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isConfirmationListeningMode = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isTtsAvailable = MutableStateFlow(true)
    val isTtsAvailable: StateFlow<Boolean> = _isTtsAvailable.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _audioLevelDb = MutableStateFlow(0f)
    val audioLevelDb: StateFlow<Float> = _audioLevelDb.asStateFlow()

    private val _statusMessage = MutableStateFlow("Chạm nút Micro lớn để nói với Cháu AI")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val viLocale = Locale("vi", "VN")
                val res = textToSpeech?.setLanguage(viLocale)
                isTtsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                _isTtsAvailable.value = isTtsReady
                if (!isTtsReady) {
                    _statusMessage.value = "Máy chưa cài gói giọng đọc Tiếng Việt (TTS). Cháu sẽ hiển thị chữ thật lớn trên màn hình cho bác đọc nhé!"
                }
                // Tốc độ đọc hơi chậm và trầm ấm phù hợp với thính lực người cao tuổi
                textToSpeech?.setSpeechRate(0.92f)
                textToSpeech?.setPitch(1.0f)
            } else {
                isTtsReady = false
                _isTtsAvailable.value = false
            }
        }
    }

    fun startListening(forConfirmation: Boolean = false) {
        mainHandler.post {
            stopSpeaking()
            isConfirmationListeningMode = forConfirmation

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _statusMessage.value = "Thiết bị chưa bật dịch vụ giọng nói Google. Bác có thể nhập câu lệnh vào ô bên dưới nhé."
                return@post
            }

            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                            _statusMessage.value = if (isConfirmationListeningMode) {
                                "Đang nghe bác xác nhận: Hãy nói 'Đồng ý' hoặc 'Hủy bỏ'..."
                            } else {
                                "Cháu đang lắng nghe bác nói (bác cứ nói thong thả)..."
                            }
                        }

                        override fun onBeginningOfSpeech() {
                            _partialText.value = ""
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            _audioLevelDb.value = rmsdB.coerceIn(0f, 12f)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                            _audioLevelDb.value = 0f
                            _statusMessage.value = "Đang phân tích câu nói của bác..."
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            _audioLevelDb.value = 0f
                            val errMsg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "Cháu chưa nghe rõ, bác bấm nút Micro nói lại hoặc nhập bên dưới nhé."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Cháu chưa nghe thấy tiếng, bác thử nói lại nhé."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Cần cấp quyền Micro để cháu nghe giọng bác."
                                else -> "Bác có thể bấm lại Micro hoặc gõ vào ô bên dưới nhé."
                            }
                            _statusMessage.value = errMsg
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            _audioLevelDb.value = 0f
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognized = matches?.firstOrNull()?.trim().orEmpty()
                            if (recognized.isNotBlank()) {
                                _partialText.value = recognized
                                onFinalSpeechResult(recognized, isConfirmationListeningMode)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (partial.isNotBlank()) {
                                _partialText.value = partial
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    // Tăng ngưỡng chờ khoảng lặng để ông bà lấy hơi / suy nghĩ không bị ngắt câu sớm (M8, T14)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                _statusMessage.value = "Không thể mở Micro: ${e.localizedMessage}"
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            _isListening.value = false
            _audioLevelDb.value = 0f
        }
    }

    fun speakVietnamese(
        text: String,
        autoListenForConfirmation: Boolean = false,
        onDone: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (!isTtsReady || textToSpeech == null) {
            _statusMessage.value = if (autoListenForConfirmation) {
                "$text (Bác hãy bấm nút ĐỒNG Ý màu xanh hoặc HỦY BỎ màu đỏ bên dưới nhé)"
            } else {
                text
            }
            onDone?.invoke()
            return
        }

        val utteranceId = "SENIOR_TTS_${System.currentTimeMillis()}"
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                _isSpeaking.value = true
                _statusMessage.value = "Cháu AI đang đọc phản hồi..."
            }

            override fun onDone(id: String?) {
                _isSpeaking.value = false
                mainHandler.post {
                    onDone?.invoke()
                    if (autoListenForConfirmation) {
                        _statusMessage.value = "Mời bác nói 'ĐỒNG Ý' hoặc bấm nút Xác nhận màu xanh..."
                        startListening(forConfirmation = true)
                    } else {
                        _statusMessage.value = "Cháu đã thực hiện xong. Bác cần gì cứ bấm Micro nhé!"
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                _isSpeaking.value = false
                mainHandler.post { onDone?.invoke() }
            }
        })

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stopSpeaking() {
        runCatching {
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
            }
        }
        _isSpeaking.value = false
    }

    fun shutdown() {
        runCatching {
            speechRecognizer?.destroy()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
    }
}
