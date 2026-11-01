package com.youtubedownloader.app

object Utils {
    /** Same check as the desktop app: the text must mention youtube.com or youtu.be. */
    fun isYoutubeUrl(text: String): Boolean =
        "youtube.com" in text || "youtu.be" in text

    private val YT_URL = Regex("""https?://\S*(?:youtube\.com|youtu\.be)\S*""", RegexOption.IGNORE_CASE)

    /** Pulls the first YouTube link out of arbitrary text (e.g. "Cool video https://youtu.be/abc"). */
    fun findYoutubeUrl(text: String?): String? =
        text?.let { YT_URL.find(it)?.value?.trimEnd('.', ',', ')', '"') }
}
