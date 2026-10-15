package com.fajar.threadflow.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("threadflow_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_THREADS_COOKIE = "threads_cookie"
        private const val KEY_THREADS_USER_ID = "threads_user_id"
        private const val KEY_THREADS_USERNAME = "threads_username"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"

        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"

        private const val KEY_DELAY_MIN = "delay_min"
        private const val KEY_DELAY_MAX = "delay_max"
        private const val KEY_DAILY_CAP = "daily_cap"

        private const val KEY_FILTER_INDO = "filter_indo"
        private const val KEY_FILTER_NEGATIVE = "filter_negative"

        private const val KEY_CAMP_DESC = "camp_desc"
        private const val KEY_CAMP_LINK = "camp_link"
        private const val KEY_CAMP_TONE = "camp_tone"
        private const val KEY_CAMP_MEDIA = "camp_media"
    }

    var threadsCookie: String
        get() = prefs.getString(KEY_THREADS_COOKIE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_THREADS_COOKIE, value).apply()

    var threadsUserId: String
        get() = prefs.getString(KEY_THREADS_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_THREADS_USER_ID, value).apply()

    var threadsUsername: String
        get() = prefs.getString(KEY_THREADS_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_THREADS_USERNAME, value).apply()

    var isThreadsLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var aiBaseUrl: String
        get() = prefs.getString(KEY_AI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString(KEY_AI_BASE_URL, value).apply()

    var aiApiKey: String
        get() = prefs.getString(KEY_AI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AI_API_KEY, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value).apply()

    var delayMinSeconds: Int
        get() = prefs.getInt(KEY_DELAY_MIN, 60)
        set(value) = prefs.edit().putInt(KEY_DELAY_MIN, value).apply()

    var delayMaxSeconds: Int
        get() = prefs.getInt(KEY_DELAY_MAX, 180)
        set(value) = prefs.edit().putInt(KEY_DELAY_MAX, value).apply()

    var dailySafetyCap: Int
        get() = prefs.getInt(KEY_DAILY_CAP, 40)
        set(value) = prefs.edit().putInt(KEY_DAILY_CAP, value).apply()

    var filterIndonesianOnly: Boolean
        get() = prefs.getBoolean(KEY_FILTER_INDO, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_INDO, value).apply()

    var filterNegativeKeywords: Boolean
        get() = prefs.getBoolean(KEY_FILTER_NEGATIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_NEGATIVE, value).apply()

    var campaignDescription: String
        get() = prefs.getString(KEY_CAMP_DESC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAMP_DESC, value).apply()

    var campaignAffiliateLink: String
        get() = prefs.getString(KEY_CAMP_LINK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAMP_LINK, value).apply()

    var campaignTone: String
        get() = prefs.getString(KEY_CAMP_TONE, "Edukatif") ?: "Edukatif"
        set(value) = prefs.edit().putString(KEY_CAMP_TONE, value).apply()

    var campaignMediaUris: String
        get() = prefs.getString(KEY_CAMP_MEDIA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAMP_MEDIA, value).apply()

    fun clearThreadsSession() {
        prefs.edit()
            .remove(KEY_THREADS_COOKIE)
            .remove(KEY_THREADS_USER_ID)
            .remove(KEY_THREADS_USERNAME)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }
}
