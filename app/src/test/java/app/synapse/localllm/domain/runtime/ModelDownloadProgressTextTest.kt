package app.synapse.localllm.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadProgressTextTest {
    @Test
    fun progressTextKeepsDecimalPrecisionPastOneGigabyte() {
        val downloadedBytes = 1_610_612_736L
        val totalBytes = 5_627_044_256L

        assertEquals("28.6% | 1.50 GB / 5.24 GB", formatModelDownloadProgressText(downloadedBytes, totalBytes))
    }

    @Test
    fun progressTextShowsMegabytesBeforeOneGigabyte() {
        val downloadedBytes = 512L * 1024L * 1024L
        val totalBytes = 2_019_377_440L

        assertEquals("26.6% | 512.0 MB / 1.88 GB", formatModelDownloadProgressText(downloadedBytes, totalBytes))
    }
}
