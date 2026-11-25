package uz.jarvis.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, def: String = ""): String = sp(c).getString(key, def) ?: def
    fun put(c: Context, key: String, value: String) = sp(c).edit().putString(key, value).apply()

    const val GROQ_KEY = "groq_key"
    const val MODEL = "model"
    const val LANG = "lang"
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"
    const val DEFAULT_LANG = "uz-UZ"
}
