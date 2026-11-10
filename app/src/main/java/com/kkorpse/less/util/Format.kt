package com.kkorpse.less.util

import android.content.Context
import com.kkorpse.less.R
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

object Format {

    private val locale = Locale.SIMPLIFIED_CHINESE
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", locale)
    private val dayWithYearFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", locale)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", locale)
    private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(locale)

    fun dayKey(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun dayLabel(context: Context, date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when {
            date == today -> context.getString(R.string.date_today)
            date == today.minusDays(1) -> context.getString(R.string.date_yesterday)
            date.year == today.year -> dayFormatter.format(date)
            else -> dayWithYearFormatter.format(date)
        }
    }

    fun fullDate(millis: Long): String =
        fullDateFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun count(value: Int): String = numberFormat.format(value)

    fun fileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var index = 0
        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024
            index++
        }
        return if (index == 0) {
            "${size.roundToLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", size, units[index])
        }
    }

    fun duration(millis: Long): String {
        if (millis <= 0L) return ""
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
