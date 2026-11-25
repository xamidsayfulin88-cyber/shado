package uz.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service() {

    companion object {
        const val ACTION_LISTEN_NOW = "uz.jarvis.app.LISTEN_NOW"
        private val WAKE_WORDS = listOf("jarvis", "jarviz", "jarvi", "жарвис", "джарвис", "жарвиз", "джарви", "жарви")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false          // Jarvis gapirayapti yoki AI javobini kutayapti
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("Jarvis ishga tushmoqda...")
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val loc = Locale.forLanguageTag(Prefs.get(this, Prefs.LANG, Prefs.DEFAULT_LANG))
                val r = tts?.setLanguage(loc)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { main.post { finishCycle() } }
                    override fun onDone(id: String?) { main.post { finishCycle() } }
                })
            }
        }
        main.postDelayed({ listen(false) }, 800)
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {}
    }

    /**
     * commandMode = false: "Jarvis" so'zini kutadi (doimiy tinglash).
     * commandMode = true: to'g'ridan-to'g'ri buyruqni tinglaydi.
     */
    private fun listen(commandMode: Boolean) {
        if (destroyed || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Bu telefonda ovoz tanish xizmati yo'q")
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {
                    updateNotification(if (commandMode) "Eshityapman..." else "\"Jarvis\" deb chaqiring")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        updateNotification("Mikrofon ruxsati yo'q")
                        stopSelf()
                        return
                    }
                    // Jim qolish / topilmadi / band: qayta tinglaymiz
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 400L
                    restartLoop(delay)
                }
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) { restartLoop(300); return }
                    if (commandMode) {
                        handle(text)
                    } else {
                        onHeard(text)
                    }
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.get(this, Prefs.LANG, Prefs.DEFAULT_LANG))
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            sr.startListening(i)
        }
    }

    private fun restartLoop(delayMs: Long) {
        if (destroyed) return
        main.postDelayed({ listen(false) }, delayMs)
    }

    /** Doimiy tinglash rejimida eshitilgan matn: "Jarvis" bormi? */
    private fun onHeard(text: String) {
        val lower = text.lowercase(Locale.ROOT)
        val wake = WAKE_WORDS.firstOrNull { lower.contains(it) }
        if (wake == null) { restartLoop(200); return }

        // "Jarvis" dan keyingi qismni buyruq sifatida olamiz
        val idx = lower.indexOf(wake)
        val rest = text.substring(idx + wake.length).trim(' ', ',', '.', '!', '?')
        if (rest.length >= 3) {
            handle(rest)
        } else {
            beep()
            main.postDelayed({ listen(true) }, 350)
        }
    }

    private fun handle(text: String) {
        busy = true
        updateNotification("O'ylayapman...")
        if (tryLocalCommand(text)) return
        val key = Prefs.get(this, Prefs.GROQ_KEY)
        val model = Prefs.get(this, Prefs.MODEL, Prefs.DEFAULT_MODEL).ifBlank { Prefs.DEFAULT_MODEL }
        if (key.isBlank()) { speak("Groq kaliti kiritilmagan."); return }
        thread {
            val reply = Groq.ask(key, model, text)
            main.post { speak(reply) }
        }
    }

    private fun tryLocalCommand(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        if (!t.contains("och")) return false
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, 0)
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            if (label.length >= 3 && t.contains(label.lowercase(Locale.ROOT))) {
                val i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName) ?: continue
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(i)
                    speak("$label ochilyapti.")
                } catch (e: Exception) {
                    speak("Ilovani ocha olmadim. Sozlamalarda boshqa ilovalar ustidan ko'rsatishga ruxsat bering.")
                }
                return true
            }
        }
        return false
    }

    private fun speak(text: String) {
        busy = true
        recognizer?.destroy()
        recognizer = null
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            finishCycle()
        }
    }

    private fun finishCycle() {
        busy = false
        recognizer?.destroy()
        recognizer = null
        restartLoop(500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LISTEN_NOW && !busy) {
            // Tugma varianti: darhol buyruqni tinglash
            main.removeCallbacksAndMessages(null)
            recognizer?.destroy()
            recognizer = null
            beep()
            main.postDelayed({ listen(true) }, 350)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
