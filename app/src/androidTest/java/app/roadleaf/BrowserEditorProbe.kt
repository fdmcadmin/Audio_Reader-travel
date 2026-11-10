package app.roadleaf

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import app.roadleaf.browser.BrowserEditorNodes
import app.roadleaf.browser.NovelAiWebApp
import app.roadleaf.browser.WebAppWindowGate
import android.os.SystemClock
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** Owner-requested live editor inspection: reports only metadata, never its story text. */
@Suppress("DEPRECATION")
class BrowserEditorProbe(private val instrumentation: Instrumentation, private val report: MutableList<String>) {
    fun run(webApp: Boolean = false) {
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val gate = WebAppWindowGate()
        if (webApp) {
            val installed = NovelAiWebApp.installed(instrumentation.targetContext).singleOrNull() ?: error("Exactly one verified installed NovelAI web app is required for this probe")
            val launchedAt = SystemClock.elapsedRealtime()
            val classes = java.util.concurrent.ConcurrentSkipListSet<String>()
            automation.setOnAccessibilityEventListener { event ->
                if (event.packageName?.toString() == "com.android.chrome" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val name = event.className?.toString().orEmpty(); classes.add(name)
                    gate.event(name, event.windowId, SystemClock.elapsedRealtime(), launchedAt)
                }
            }
            instrumentation.runOnMainSync { instrumentation.targetContext.startActivity(installed.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Thread.sleep(2500)
            report.add("Web-app launch activity events=${classes.toList()}")
        }
        var current: AccessibilityNodeInfo? = null
        repeat(20) { if (current == null) { Thread.sleep(500); current = automation.rootInActiveWindow } }
        var root = current ?: error("No active browser window; unlock the phone and leave the story visible")
        root.recycle(); Thread.sleep(2000)
        root = automation.rootInActiveWindow ?: error("The browser window closed")
        try {
            report.add("Browser root package=${root.packageName}; addressVerified=${BrowserEditorNodes.verifiedAddress(root)}")
            report.add("Verified installed NovelAI web apps=${NovelAiWebApp.installed(instrumentation.targetContext).map { it.packageName }}")
            if (webApp) { check(gate.allows(root.windowId)) { "Installed web-app window was not bound by its OS activity event" }; report.add("PASS verified NovelAI WebAPK launch binds its actual Chrome web-app window without trusting a title or missing address bar") }
            var nodes = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++nodes > 2500 || depth > 50) return
                if (node.className?.toString() == "android.webkit.WebView" || node.isEditable && node.isFocused) {
                    val extras = node.extras
                    report.add("Node class=${node.className}; editable=${node.isEditable}; focused=${node.isFocused}; visible=${node.isVisibleToUser}; password=${node.isPassword}; chars=${if (node.isPassword) 0 else node.text?.length ?: 0}; lineBreaks=${if (node.isPassword) 0 else node.text?.count { it == '\n' } ?: 0}; inputType=${node.inputType}; extraKeys=${extras.keySet().sorted()}")
                    listOf("AccessibilityNodeInfo.chromeRole", "AccessibilityNodeInfo.roleDescription", "AccessibilityNodeInfo.htmlTag").forEach { key -> extras.get(key)?.let { value -> report.add("Metadata $key=$value") } }
                    extras.getCharSequence("AccessibilityNodeInfo.targetUrl")?.let { report.add("Document target URL verifiedNovelAI=${app.roadleaf.browser.BrowserStory.novelAiAddress(it.toString())}") }
                }
                for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; try { visit(child, depth + 1) } finally { child.recycle() } }
            }
            visit(root, 0)
            val editor = BrowserEditorNodes.focusedEditor(root)
            try { report.add("PASS focused web editor exposes ${BrowserEditorNodes.text(editor).length} readable characters; no page/sidebar text collected") } finally { editor.recycle() }
        } finally { root.recycle(); automation.setOnAccessibilityEventListener(null) }
    }
}
