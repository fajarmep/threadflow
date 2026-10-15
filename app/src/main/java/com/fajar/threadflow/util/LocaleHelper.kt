package com.fajar.threadflow.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.fajar.threadflow.data.AppPreferences
import java.util.Locale

object LocaleHelper {
    fun onAttach(context: Context): Context {
        val lang = AppPreferences(context).appLanguage
        return setLocale(context, lang)
    }

    fun setLocale(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }

        return context.createConfigurationContext(configuration)
    }
}
