package com.apadev.memoryfix.analytics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AttributionManager {

    private const val TAG = "AttributionManager"
    private const val PREFS_NAME = "memoryfix_attribution"
    private const val KEY_ANSWERED = "has_answered_referral_survey"
    private const val KEY_ANSWER = "referral_survey_selected"

    /**
     * GOOGLE FORM CONFIGURATION:
     * 1. Create a Google Form with 1 question ("Where did you find MemoryFix?").
     * 2. Replace GOOGLE_FORM_URL with your form's '/formResponse' endpoint:
     *    e.g. "https://docs.google.com/forms/d/e/1FAIpQLScXXXXXXXXXXXX/formResponse"
     * 3. Replace FORM_ENTRY_ID with your question's entry ID:
     *    e.g. "entry.123456789"
     */
    var GOOGLE_FORM_URL: String = "https://docs.google.com/forms/d/e/1FAIpQLSe-UKbrrfD1X0bgkq08otyc3zzNB0CK8gTE4lY5MFFYcVm9ww/formResponse"
    var FORM_ENTRY_ID: String = "entry.1337043566"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasAnswered(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ANSWERED, false)
    }

    fun submitAnswer(context: Context, answer: String) {
        // 1. Immediately persist locally so user is never prompted again
        getPrefs(context).edit()
            .putBoolean(KEY_ANSWERED, true)
            .putString(KEY_ANSWER, answer)
            .apply()

        Log.d(TAG, "Referral survey recorded locally: $answer")

        // 2. Submit to Google Form endpoint if configured
        val formUrl = GOOGLE_FORM_URL.trim()
        val entryId = FORM_ENTRY_ID.trim()

        if (formUrl.isNotEmpty() && entryId.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL(formUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    conn.setRequestProperty("Accept", "*/*")

                    val postData = if (answer.equals("Other", ignoreCase = true)) {
                        "$entryId=__other_option__&$entryId.other_option_response=Other"
                    } else {
                        "$entryId=${URLEncoder.encode(answer, "UTF-8")}"
                    }
                    conn.outputStream.use { os ->
                        os.write(postData.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }

                    val code = conn.responseCode
                    Log.d(TAG, "Google Form response code: $code")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Silent fail submitting survey to Google Form: ${e.message}")
                }
            }
        }
    }
}
