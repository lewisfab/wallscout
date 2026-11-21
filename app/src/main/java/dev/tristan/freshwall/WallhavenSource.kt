package dev.tristan.freshwall

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Wallpaper(
    val id: String,
    val imageUrl: String,
    val pageUrl: String,
    val width: Int,
    val height: Int,
)

/** Wallhaven's public search API (no key needed for SFW content, 45 requests/minute). */
object WallhavenSource {
    private const val USER_AGENT = "Wallscout/${BuildConfig.VERSION_NAME} (Android app)"

    class RateLimited : IOException("Wallhaven rate limit hit")

    class Page(val wallpapers: List<Wallpaper>, val lastPage: Int)

    /** [atleast] is Wallhaven's minimum size filter, "WIDTHxHEIGHT". */
    fun search(query: String, sort: Sort, portraitOnly: Boolean, atleast: String, page: Int): Page {
        val url = Uri.parse("https://wallhaven.cc/api/v1/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("categories", "101") // general + people, no anime
            .appendQueryParameter("purity", "100") // SFW only
            .appendQueryParameter("sorting", sort.sorting)
            .apply { sort.topRange?.let { appendQueryParameter("topRange", it) } }
            .apply { if (portraitOnly) appendQueryParameter("ratios", "portrait") }
            .appendQueryParameter("atleast", atleast)
            .appendQueryParameter("page", page.toString())
            .build()
            .toString()

        val json = JSONObject(String(get(url), Charsets.UTF_8))
        val data = json.getJSONArray("data")
        val wallpapers = (0 until data.length()).map { i ->
            val w = data.getJSONObject(i)
            Wallpaper(
                id = w.getString("id"),
                imageUrl = w.getString("path"),
                pageUrl = w.getString("url"),
                width = w.getInt("dimension_x"),
                height = w.getInt("dimension_y"),
            )
        }
        return Page(wallpapers, json.getJSONObject("meta").optInt("last_page", 1))
    }

    fun download(url: String): ByteArray = get(url, maxBytes = 60 shl 20)

    private fun get(url: String, maxBytes: Int = 5 shl 20): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            when (val code = conn.responseCode) {
                200 -> {}
                429 -> throw RateLimited()
                else -> throw IOException("Wallhaven returned HTTP $code")
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Response too large")
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
