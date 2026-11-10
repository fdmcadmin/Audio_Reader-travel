package app.roadleaf.browser

import java.util.UUID

/** Same-process, one-shot transfer: long stories never cross Binder as a giant text Bundle. */
class BrowserTextHandoff {
    private data class Payload(val id: String, val token: String, val text: String, val created: Long)
    private var payload: Payload? = null
    @Synchronized fun put(token: String, text: String): String {
        require(token.isNotBlank() && text.length in 40..BrowserStory.MAX_TEXT)
        return UUID.randomUUID().toString().also { payload = Payload(it, token, text, System.nanoTime()) }
    }
    @Synchronized fun take(id: String?, token: String): String? {
        val value = payload ?: return null
        if (value.id != id || value.token != token) return null
        payload = null
        return value.text.takeIf { System.nanoTime() - value.created < 60_000_000_000L }
    }
    @Synchronized fun clear() { payload = null }
}
