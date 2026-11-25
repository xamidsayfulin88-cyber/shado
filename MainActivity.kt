package uz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var groq: EditText
    private lateinit var model: EditText
    private lateinit var lang: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "Jarvis"
            textSize = 28f
            gravity = Gravity.CENTER
        })

        root.addView(label("Groq API kaliti (gsk_...)"))
        groq = field("gsk_...", Prefs.get(this, Prefs.GROQ_KEY), true); root.addView(groq)

        root.addView(label("Groq modeli"))
        model = field(Prefs.DEFAULT_MODEL, Prefs.get(this, Prefs.MODEL, Prefs.DEFAULT_MODEL), false); root.addView(model)

        root.addView(label("Til (uz-UZ, ru-RU, en-US)"))
        lang = field(Prefs.DEFAULT_LANG, Prefs.get(this, Prefs.LANG, Prefs.DEFAULT_LANG), false); root.addView(lang)

        root.addView(btn("1. Saqlash") { save(); toast("Saqlandi") })
        root.addView(btn("2. Mikrofon va bildirishnoma ruxsati") { askPermissions() })
        root.addView(btn("3. Boshqa ilovalar ustidan ko'rsatish ruxsati") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("4. Batareya cheklovini o'chirish") {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else toast("Allaqachon o'chirilgan")
        })
        root.addView(btn("5. Jarvis'ni yoqish") {
            save()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Avval 2-tugma orqali mikrofon ruxsatini bering")
            } else if (Prefs.get(this, Prefs.GROQ_KEY).isBlank()) {
                toast("Groq API kalitini kiriting")
            } else {
                startForegroundService(Intent(this, JarvisService::class.java))
                toast("Yoqildi. \"Jarvis\" deb chaqiring")
            }
        })
        root.addView(btn("Hozir gapirish (tugma bilan)") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Avval 2-tugma orqali mikrofon ruxsatini bering")
            } else {
                save()
                startForegroundService(
                    Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_LISTEN_NOW)
                )
            }
        })
        root.addView(btn("O'chirish") {
            stopService(Intent(this, JarvisService::class.java))
            toast("O'chirildi")
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        Prefs.put(this, Prefs.GROQ_KEY, groq.text.toString().trim())
        Prefs.put(this, Prefs.MODEL, model.text.toString().trim())
        Prefs.put(this, Prefs.LANG, lang.text.toString().trim())
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
