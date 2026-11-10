package app.roadleaf.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** Any installed web browser can host browser reading. Roadleaf prefers no browser or search engine. */
object BrowserApps {
    /** Common browsers: the service's starting filter until Android reports what is installed. */
    val known = linkedMapOf(
        "com.android.chrome" to "Chrome", "com.chrome.beta" to "Chrome Beta", "com.chrome.dev" to "Chrome Dev", "com.chrome.canary" to "Chrome Canary",
        "org.mozilla.firefox" to "Firefox", "org.mozilla.firefox_beta" to "Firefox Beta", "org.mozilla.fenix" to "Firefox Nightly", "org.mozilla.focus" to "Firefox Focus",
        "com.microsoft.emmx" to "Edge", "com.brave.browser" to "Brave", "com.sec.android.app.sbrowser" to "Samsung Internet",
        "com.opera.browser" to "Opera", "com.opera.mini.native" to "Opera Mini", "com.opera.gx" to "Opera GX",
        "com.duckduckgo.mobile.android" to "DuckDuckGo", "com.vivaldi.browser" to "Vivaldi", "com.kiwibrowser.browser" to "Kiwi",
        "com.yandex.browser" to "Yandex", "org.torproject.torbrowser" to "Tor Browser", "com.ecosia.android" to "Ecosia",
        "com.mi.globalbrowser" to "Mi Browser", "com.UCMobile.intl" to "UC Browser", "com.naver.whale" to "Whale",
        "org.mozilla.fennec_fdroid" to "Fennec", "us.spotco.fennec_dos" to "Mull", "org.cromite.cromite" to "Cromite",
    )

    data class Installed(val packageName: String, val label: String)

    /** Apps that open ordinary https links; the owner's own browser is always among them. */
    @Suppress("DEPRECATION")
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        return runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }.filter { it != context.packageName }.distinct()
            .map { pkg -> Installed(pkg, known[pkg] ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
    }

    fun packages(context: Context): Set<String> = installed(context).map { it.packageName }.toSet().ifEmpty { known.keys }

    fun label(packageName: String?): String = known[packageName] ?: "your browser"
}
