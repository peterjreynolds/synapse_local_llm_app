package app.synapse.localllm.data.remote

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidRemoteAudioWaveformExtractor(context: Context) {
    private val cacheDirectory = context.applicationContext.cacheDir

    suspend fun extract(localUri: String): List<Float> = withContext(Dispatchers.IO) {
        val source = validateRemoteAudioWaveformSource(cacheDirectory, localUri)
        decodeRemoteAudioWaveform(source)
    }
}

internal fun validateRemoteAudioWaveformSource(
    cacheDirectory: File,
    localUri: String,
): File {
    val uri = localUri.toUri()
    require(uri.scheme == "file") { "The voice message is not available in private storage." }
    val source = File(requireNotNull(uri.path)).canonicalFile
    require(source.isFile && source.toPath().startsWith(cacheDirectory.canonicalFile.toPath())) {
        "The voice message is not available in private storage."
    }
    return source
}

private fun decodeRemoteAudioWaveform(source: File): List<Float> {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var decoderStarted = false
    return try {
        extractor.setDataSource(source.path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return emptyList()
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mimeType = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationMicros = inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
        val estimatedFrameCount = (durationMicros * sampleRate / MICROS_PER_SECOND).coerceAtLeast(1L)
        val peaks = RemoteWaveformPeakAccumulator(WAVEFORM_BAR_COUNT, estimatedFrameCount)
        extractor.selectTrack(trackIndex)
        val activeDecoder = MediaCodec.createDecoderByType(mimeType).apply {
            configure(inputFormat, null, null, 0)
            start()
        }
        decoder = activeDecoder
        decoderStarted = true
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var outputChannelCount = channelCount
        var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = activeDecoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
                if (inputIndex >= 0) {
                    val inputBuffer = requireNotNull(activeDecoder.getInputBuffer(inputIndex)).apply { clear() }
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        activeDecoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        activeDecoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_MICROS)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = activeDecoder.outputFormat
                    outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (outputIndex >= 0) {
                    activeDecoder.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        if (bufferInfo.size > 0) {
                            appendDecodedAudioPeaks(
                                source = outputBuffer,
                                offset = bufferInfo.offset,
                                byteCount = bufferInfo.size,
                                channelCount = outputChannelCount,
                                encoding = outputEncoding,
                                peaks = peaks,
                            )
                        }
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeDecoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        peaks.normalizedPeaks()
    } finally {
        if (decoderStarted) runCatching { decoder?.stop() }
        decoder?.release()
        extractor.release()
    }
}

private fun appendDecodedAudioPeaks(
    source: ByteBuffer,
    offset: Int,
    byteCount: Int,
    channelCount: Int,
    encoding: Int,
    peaks: RemoteWaveformPeakAccumulator,
) {
    val samples = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
        position(offset)
        limit(offset + byteCount)
    }
    when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> while (samples.remaining() >= Float.SIZE_BYTES * channelCount) {
            var framePeak = 0f
            repeat(channelCount) { framePeak = maxOf(framePeak, abs(samples.float).coerceAtMost(1f)) }
            peaks.append(framePeak)
        }

        else -> while (samples.remaining() >= Short.SIZE_BYTES * channelCount) {
            var framePeak = 0f
            repeat(channelCount) {
                framePeak = maxOf(framePeak, abs(samples.short.toInt()) / Short.MAX_VALUE.toFloat())
            }
            peaks.append(framePeak.coerceAtMost(1f))
        }
    }
}

internal class RemoteWaveformPeakAccumulator(
    private val barCount: Int,
    private val estimatedFrameCount: Long,
) {
    private val peaks = FloatArray(barCount)
    private var appendedFrameCount = 0L

    init {
        require(barCount > 0) { "Waveform bar count must be positive." }
        require(estimatedFrameCount > 0L) { "Estimated audio frame count must be positive." }
    }

    fun append(amplitude: Float) {
        val bucket = ((appendedFrameCount * barCount) / estimatedFrameCount)
            .coerceIn(0L, (barCount - 1).toLong())
            .toInt()
        peaks[bucket] = maxOf(peaks[bucket], amplitude.coerceIn(0f, 1f))
        appendedFrameCount += 1L
    }

    fun normalizedPeaks(): List<Float> {
        val loudestPeak = peaks.maxOrNull() ?: 0f
        if (loudestPeak <= 0f) return emptyList()
        return peaks.map { peak -> (peak / loudestPeak).coerceIn(0f, 1f) }
    }
}

private const val CODEC_TIMEOUT_MICROS = 10_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val WAVEFORM_BAR_COUNT = 40
