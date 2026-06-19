package app.synapse.localllm.domain.runtime

import java.util.Locale

fun formatModelDownloadByteCount(byteCount: Long): String =
    when {
        byteCount >= GIB -> "%.2f GB".format(Locale.US, byteCount.toDouble() / GIB.toDouble())
        byteCount >= MIB -> "%.1f MB".format(Locale.US, byteCount.toDouble() / MIB.toDouble())
        byteCount >= KIB -> "%.1f KB".format(Locale.US, byteCount.toDouble() / KIB.toDouble())
        else -> "$byteCount B"
    }

fun formatModelDownloadPercent(downloadedBytes: Long, totalBytes: Long): String =
    if (totalBytes > 0L) {
        "%.1f%%".format(
            Locale.US,
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) * 100.0,
        )
    } else {
        "0.0%"
    }

fun formatModelDownloadProgressText(downloadedBytes: Long, totalBytes: Long): String =
    "${formatModelDownloadPercent(downloadedBytes, totalBytes)} | " +
        "${formatModelDownloadByteCount(downloadedBytes)} / ${formatModelDownloadByteCount(totalBytes)}"

private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L
