package dev.tclawyered.app.content

import dev.tclawyered.app.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a policy page by URL and returns its visible text (Slice 4). Used for
 * the share-a-link path. Runs on IO; throws a typed message the UI humanizes.
 */
class PolicyFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
) {
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw FetchException("FETCH_FAILED: The page returned ${res.code}.")
            val html = res.body?.string().orEmpty()
            val text = HtmlExtractor.extract(html)
            if (text.split(Regex("\\s+")).count { it.isNotBlank() } < Constants.MIN_POLICY_WORDS) {
                throw FetchException("FETCH_EMPTY: Couldn't read enough text from that page.")
            }
            text
        }
    }

    class FetchException(message: String) : Exception(message)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) TCLawyered/0.1"
    }
}
