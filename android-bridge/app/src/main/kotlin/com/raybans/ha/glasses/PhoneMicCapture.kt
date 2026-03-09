package com.raybans.ha.glasses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PhoneMicCapture"

/**
 * Wraps Android SpeechRecognizer for push-to-talk voice input.
 *
 * SpeechRecognizer must be created and used on the main thread.
 * Results are delivered via [onResult] callback on the main thread;
 * the caller is responsible for dispatching to IO if needed.
 */
class PhoneMicCapture(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    /** Start listening. Must be called from the main thread (or posts to it). */
    fun startListening() {
        scope.launch(Dispatchers.Main) {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            Log.i(TAG, "Recognized: $text")
                            onResult?.invoke(text)
                        } else {
                            onError?.invoke("No speech recognized")
                        }
                    }

                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            else -> "Error $error"
                        }
                        Log.w(TAG, "STT error: $msg")
                        onError?.invoke(msg)
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Ready for speech")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.startListening(intent)
            Log.i(TAG, "Listening...")
        }
    }

    fun destroy() {
        scope.launch(Dispatchers.Main) {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
