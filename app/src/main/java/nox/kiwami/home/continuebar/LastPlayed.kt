package nox.kiwami.home.continuebar

import nox.kiwami.settings.saving.PrefManager

object LastPlayed {
    private const val KEY = "continueLastPlayed"
    private const val NO_NUMBER = "-"

    data class Entry(
        val anime: Boolean,
        val mediaId: Int,
        val number: Float?,
        val at: Long
    )

    fun record(anime: Boolean, mediaId: Int, number: Float?) {
        if (mediaId <= 0) return
        val type = if (anime) "A" else "M"
        val value = listOf(type, mediaId, number ?: NO_NUMBER, System.currentTimeMillis())
            .joinToString("|")
        PrefManager.setCustomVal(KEY, value)
    }

    fun get(): Entry? {
        val parts = PrefManager.getCustomVal(KEY, "").split("|")
        if (parts.size != 4) return null
        val id = parts[1].toIntOrNull() ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        return Entry(parts[0] == "A", id, parts[2].toFloatOrNull(), at)
    }
}
