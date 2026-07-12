package dev.tclawyered.app.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import dev.tclawyered.app.core.Domain
import dev.tclawyered.app.ui.MainActivity

/**
 * Share-sheet entry point (the "Share -> T&C Lawyered" flow). Receives either a
 * link or a block of selected text from any app, does the cheap on-device prep
 * (pull a domain if there's a URL), then hands off to MainActivity to run the
 * summarize pipeline and render the result.
 *
 * This activity has no UI of its own (transparent theme); it forwards and finishes.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        } else {
            ""
        }

        if (shared.isEmpty()) {
            finish()
            return
        }

        // If the shared payload is a bare URL, MainActivity will fetch it; otherwise
        // treat it as the policy text itself (selected text from an in-app screen).
        val isUrl = Patterns.WEB_URL.matcher(shared).matches()
        val domain = if (isUrl) Domain.fromUrl(shared) else ""

        val forward = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_SHARED_IS_URL, isUrl)
            putExtra(MainActivity.EXTRA_SHARED_PAYLOAD, shared)
            putExtra(MainActivity.EXTRA_SHARED_DOMAIN, domain)
        }
        startActivity(forward)
        finish()
    }
}
