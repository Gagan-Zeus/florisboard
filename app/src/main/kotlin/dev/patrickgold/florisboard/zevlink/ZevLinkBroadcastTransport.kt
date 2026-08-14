package dev.patrickgold.florisboard.zevlink

import android.content.Context
import android.content.Intent

/**
 * Transport that broadcasts captured clipboard items to the ZevLink app
 * (com.zevclip.sender) over a local Android broadcast intent.
 */
class ZevLinkBroadcastTransport(private val context: Context) : ZevLinkTransport {
    override fun send(item: ZevLinkClipboardItem) {
        val text = item.text ?: return
        
        val intent = Intent("com.zevclip.sender.action.ZEVBOARD_CLIPBOARD")
        intent.setPackage("com.zevclip.sender")
        intent.putExtra("clipboard_text", text)
        
        try {
            context.sendBroadcast(intent)
            ZevLinkDebugLogger.logEvent("Broadcasted clipboard to com.zevclip.sender")
        } catch (e: Exception) {
            ZevLinkDebugLogger.logWarn("Failed to broadcast clipboard: ${e.message}")
        }
    }
}
