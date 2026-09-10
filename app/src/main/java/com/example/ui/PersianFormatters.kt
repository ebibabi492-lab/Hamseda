package com.example.ui

object PersianFormatters {

    fun formatDuration(durationMs: Long, usePersianDigits: Boolean = true): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val formatted = String.format("%02d:%02d", minutes, seconds)
        return if (usePersianDigits) toPersianDigits(formatted) else formatted
    }

    fun toPersianDigits(text: String): String {
        val persianNumbers = arrayOf("۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹")
        var result = text
        for (i in 0..9) {
            result = result.replace(i.toString(), persianNumbers[i])
        }
        return result
    }

    fun formatMsOffset(offsetMs: Long): String {
        val prefix = if (offsetMs > 0) "+" else ""
        return "$prefix$offsetMs ms"
    }

    fun formatLatency(ms: Long): String {
        return if (ms >= 1000) {
            val seconds = String.format("%.1f", ms / 1000.0)
            "${toPersianDigits(seconds)} ثانیه"
        } else {
            "${toPersianDigits(ms.toString())} میلی‌ثانیه"
        }
    }
}
