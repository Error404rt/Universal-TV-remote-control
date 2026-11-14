package com.universalremote.app.connection

import java.util.Locale

/**
 * Curated Android TV packages used when the TV is reached through Android TV
 * Remote. That protocol can launch an app by package name but cannot enumerate
 * installed apps; ADB provides the real device list.
 */
object TvAppCatalog {
    private val curated = listOf(
        TvApp("com.google.android.youtube.tv", "YouTube"),
        TvApp("com.google.android.youtube", "YouTube"),
        TvApp("com.google.android.apps.youtube.kids", "YouTube Kids"),
        TvApp("com.netflix.ninja", "Netflix"),
        TvApp("com.amazon.amazonvideo.livingroom", "Prime Video"),
        TvApp("com.disney.disneyplus", "Disney+"),
        TvApp("com.spotify.tv.android", "Spotify"),
        TvApp("tv.twitch.android.app", "Twitch"),
        TvApp("com.plexapp.android", "Plex"),
        TvApp("org.xbmc.kodi", "Kodi"),
        TvApp("org.videolan.vlc", "VLC"),
        TvApp("com.mxtech.videoplayer.ad", "MX Player"),
        TvApp("ru.ivi.client", "IVI"),
        TvApp("com.google.android.videos", "Google TV"),
        TvApp("com.android.vending", "Play Store")
    ).distinctBy { it.packageName }

    fun curatedApps(): List<TvApp> = curated

    fun knownName(packageName: String): String? = when (packageName) {
        "com.google.android.youtube.tv", "com.google.android.youtube" -> "YouTube"
        "com.google.android.apps.youtube.kids" -> "YouTube Kids"
        "com.netflix.ninja" -> "Netflix"
        "com.amazon.amazonvideo.livingroom" -> "Prime Video"
        "com.disney.disneyplus" -> "Disney+"
        "com.spotify.tv.android" -> "Spotify"
        "tv.twitch.android.app" -> "Twitch"
        "com.plexapp.android" -> "Plex"
        "org.xbmc.kodi" -> "Kodi"
        "org.videolan.vlc" -> "VLC"
        "com.mxtech.videoplayer.ad" -> "MX Player"
        "ru.ivi.client" -> "IVI"
        "com.google.android.videos" -> "Google TV"
        "com.google.android.tvlauncher" -> "Google TV"
        "com.android.vending" -> "Play Store"
        else -> curated.firstOrNull { it.packageName == packageName }?.displayName
    }

    fun fallbackLabel(packageName: String): String {
        knownName(packageName)?.let { return it }
        val tail = packageName.substringAfterLast('.')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
        return tail.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .ifBlank { packageName }
            .take(28)
    }

    /**
     * Merge the installed list with curated entries. Installed entries win for
     * components; curated names replace cryptic package-derived names.
     */
    fun merge(installed: List<TvApp>): List<TvApp> {
        val merged = LinkedHashMap<String, TvApp>()
        curated.forEach { merged[it.packageName] = it }
        installed.forEach { app ->
            val existing = merged[app.packageName]
            merged[app.packageName] = app.copy(
                displayName = knownName(app.packageName) ?: app.displayName,
                componentName = app.componentName ?: existing?.componentName
            )
        }
        return merged.values.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    fun resolveSaved(packages: List<String>, installed: List<TvApp>): List<TvApp> {
        val byPackage = installed.associateBy { it.packageName }
        return packages.distinct().map { packageName ->
            byPackage[packageName] ?: TvApp(packageName, fallbackLabel(packageName))
        }
    }
}
