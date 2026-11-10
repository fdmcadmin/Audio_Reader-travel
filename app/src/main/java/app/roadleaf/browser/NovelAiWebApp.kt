package app.roadleaf.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** Recognize only an installed Chromium web app whose declared start/scope are NovelAI. */
object NovelAiWebApp {
    data class Installed(val packageName: String, val label: String, val launch: Intent)
    @Suppress("DEPRECATION")
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, "https://novelai.net/stories".toUri()).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            .map { it.activityInfo.packageName }.distinct().mapNotNull { pkg ->
                if (!pkg.startsWith("org.chromium.webapk.")) return@mapNotNull null
                val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA) }.getOrNull() ?: return@mapNotNull null
                val metadata = info.metaData ?: return@mapNotNull null
                if (!BrowserStory.novelAiAddress(metadata.getString("org.chromium.webapk.shell_apk.startUrl").orEmpty()) ||
                    !BrowserStory.novelAiAddress(metadata.getString("org.chromium.webapk.shell_apk.scope").orEmpty())) return@mapNotNull null
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                Installed(pkg, pm.getApplicationLabel(info).toString(), launch)
            }
    }
}

/** A fresh explicit app launch binds a Chromium web-app window, never a title/claimed page URL. */
class WebAppWindowGate {
    @Volatile private var bound: Int? = null
    fun reset() { bound = null }
    fun event(className: String, windowId: Int, now: Long, launchedAt: Long) {
        val webApp = className.startsWith("org.chromium.chrome.browser.webapps.") && (className.endsWith("WebApkActivity") || className.endsWith("WebappActivity"))
        if (webApp && bound == null && launchedAt > 0 && now - launchedAt in 0..15_000) bound = windowId
        else if (bound != null && (bound != windowId || className.contains("CustomTabActivity") || className == "com.google.android.apps.chrome.Main")) bound = null
    }
    fun allows(windowId: Int) = bound == windowId
}
