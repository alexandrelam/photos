package io.github.alexandrelam.photos.shared.format

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** Human-readable strings shared by every screen. */
object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayInYear = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    private val dayWithYear = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** "Today", "Yesterday", "Sat, Oct 10" or "Sat, Oct 10, 2019". */
    fun day(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> dayInYear.format(date)
            else -> dayWithYear.format(date)
        }
    }

    /** "Today, 18:42". */
    fun dayAndTime(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "${day(at.toLocalDate())}, ${time.format(at)}"
    }

    fun bytes(size: Long): String = when {
        size >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", size / 1e9)
        size >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", size / 1e6)
        size >= 1_000 -> "${size / 1_000} KB"
        else -> "$size B"
    }

    fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    fun photos(n: Int): String = "${count(n)} ${if (n == 1) "photo" else "photos"}"

    /** "0:14", "1:02:03". */
    fun duration(ms: Long): String {
        val total = max(0, ms / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "just now", "5 min ago", "2 h ago", "3 days ago". */
    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - epochMs) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }

    /** "2004–2015" when a folder spans several years, else the single year. */
    fun yearRange(oldest: Long?, newest: Long?): String? {
        if (oldest == null || newest == null) return null
        val a = localDate(oldest).year
        val b = localDate(newest).year
        return if (a == b) "$a" else "$a–$b"
    }
}
