package app.roadleaf.browser

import android.view.accessibility.AccessibilityNodeInfo

/** Browser chrome verifies the host; only a focused editable web node can become the source. */
@Suppress("DEPRECATION")
object BrowserEditorNodes {
    /** Chromium marks rich editors as generic containers; other browsers expose a multi-line editable node. */
    private fun richEditor(node: AccessibilityNodeInfo): Boolean {
        val role = node.extras.getString("AccessibilityNodeInfo.chromeRole")
        return role == "genericContainer" || (role == null && (node.isMultiLine || (node.text?.length ?: 0) >= 200))
    }
    fun address(root: AccessibilityNodeInfo): String? {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): String? {
            if (++visited > 2500 || depth > 50) return null
            if (node.viewIdResourceName?.substringAfter(":id/") in BrowserStory.addressIds)
                return node.text?.toString().orEmpty()
            // The address is native browser UI. Never search the website for a claimed URL.
            if (node.className?.toString() == "android.webkit.WebView") return null
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return visit(root, 0)
    }
    fun verifiedAddress(root: AccessibilityNodeInfo) = address(root)?.let(BrowserStory::novelAiAddress) == true
    private fun insideWeb(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent; var depth = 0
        while (parent != null && depth++ < 50) {
            val current = parent
            if (current.extras.getString("AccessibilityNodeInfo.chromeRole") in setOf("dialog", "alertDialog")) { current.recycle(); return false }
            if (current.className?.toString() == "android.webkit.WebView") { current.recycle(); return true }
            parent = current.parent; current.recycle()
        }
        parent?.recycle()
        return false
    }
    fun focusedEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var depth = 0
        while (node != null && depth++ < 30) {
            val current = node
            if (current.isPassword) { current.recycle(); error("Leave the sign-in field and open the story first.") }
            if (current.isEditable && current.isVisibleToUser && richEditor(current) && insideWeb(current)) return current
            node = current.parent; current.recycle()
        }
        node?.recycle()
        // NovelAI chat mode has a separate reply box. That form field is never the story.
        // A sole visible rich editor can be designated without moving the user's input caret.
        val editors = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(current: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 2500 || depth > 50 || editors.size > 1) return
            if (current.isEditable && !current.isPassword && current.isVisibleToUser && richEditor(current) && insideWeb(current)) {
                editors.add(AccessibilityNodeInfo.obtain(current)); return
            }
            for (index in 0 until current.childCount) { val child = current.getChild(index) ?: continue; try { visit(child, depth + 1) } finally { child.recycle() } }
        }
        visit(root, 0)
        if (editors.size == 1) return editors.single()
        editors.forEach { it.recycle() }
        error("Tap inside the story-writing box, then Read story. Exactly one visible rich story editor is required; reply and settings fields are excluded.")
    }
    fun text(node: AccessibilityNodeInfo): String {
        require(node.isVisibleToUser && node.isEnabled) { "The selected editor is no longer visible. Open the story tab again." }
        require(richEditor(node)) { "Select the rich story-writing editor. Settings, memory, lorebook and ordinary form fields are excluded." }
        return BrowserStory.editorText(node.text?.toString().orEmpty(), node.isEditable, node.isPassword, insideWeb(node))
    }
}
