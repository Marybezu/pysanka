package ua.pysanka.clipboard

/**
 * Pysanka: removes tracking from links (utm_…, fbclid, gclid, YouTube and Spotify "si", X's "s"
 * and "t", and the like), so a copied link tells nobody who shared it, where and when. Only the
 * known tracking parameters go; everything else in the link stays exactly as it was. Works on the
 * phone, with plain text rules; nothing is looked up.
 */
object LinkCleaner {
    private val url = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

    /** Tracking on any site. */
    private val everywhere = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "gclsrc", "msclkid", "yclid", "ttclid", "twclid",
        "li_fat_id", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "igshid", "igsh", "srsltid",
        "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "wickedid", "rb_clickid", "_ga", "_gl",
        "ga_source", "ga_medium", "ga_campaign", "ga_content", "ga_term", "s_cid", "spm", "scm",
    )

    /** Tracking that only means tracking on these sites (elsewhere the same name may matter). */
    private val bySite: List<Pair<List<String>, Set<String>>> = listOf(
        listOf("youtube.com", "youtu.be") to setOf("si", "feature", "pp"),
        listOf("spotify.com", "spotify.link") to setOf("si", "nd", "context"),
        listOf("x.com", "twitter.com") to setOf("s", "t", "ref_src", "ref_url"),
        listOf("instagram.com") to setOf("igsh", "igshid", "utm_source"),
        listOf("facebook.com", "fb.watch", "fb.com") to setOf("mibextid", "rdid", "share_url", "ref", "sfnsn"),
        listOf("threads.net", "threads.com") to setOf("xmt", "slof"),
        listOf("linkedin.com") to setOf("trk", "trackingid", "lipi", "rcm", "midtoken", "midsig", "trkemail"),
        listOf("tiktok.com") to setOf("is_from_webapp", "sender_device", "sender_web_id", "_r", "_t", "share_app_id", "share_link_id"),
        listOf("reddit.com") to setOf("share_id", "rdt", "ref_source"),
        listOf("aliexpress.com") to setOf("aff_fcid", "aff_fsk", "aff_platform", "aff_trace_key", "sk", "terminal_id", "algo_pvid", "algo_exp_id"),
    )

    /** Characters that end a sentence rather than the link. */
    private const val TRAILING = ".,;:!?)]}…"

    /** [text] with tracking removed from every link in it; the same string if there was none. */
    fun clean(text: String): String {
        if (!text.contains("://")) return text
        return url.replace(text) { m ->
            var link = m.value
            var tail = ""
            while (link.isNotEmpty() && link.last() in TRAILING) {
                // A closing bracket that belongs to the link (Wikipedia) stays
                if (link.last() == ')' && link.count { it == '(' } >= link.count { it == ')' }) break
                tail = link.last() + tail
                link = link.dropLast(1)
            }
            cleanLink(link) + tail
        }
    }

    private fun host(link: String): String {
        val afterScheme = link.substringAfter("://")
        val h = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@').substringBefore(':')
        return h.lowercase().removePrefix("www.").removePrefix("m.")
    }

    private fun isTracking(name: String, host: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("utm_") || n in everywhere) return true
        if (n.startsWith("__cft__") || n.startsWith("__tn__")) return true // Facebook
        return bySite.any { (sites, names) -> n in names && sites.any { host == it || host.endsWith(".$it") } }
    }

    private fun cleanLink(link: String): String {
        val q = link.indexOf('?')
        if (q < 0) return link
        val hash = link.indexOf('#', q).let { if (it < 0) link.length else it }
        val base = link.substring(0, q)
        val query = link.substring(q + 1, hash)
        val fragment = link.substring(hash)
        val host = host(link)
        val parts = query.split('&')
        val kept = parts.filter { p -> p.isNotEmpty() && !isTracking(p.substringBefore('='), host) }
        if (kept.size == parts.count { it.isNotEmpty() }) return link
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }
}
