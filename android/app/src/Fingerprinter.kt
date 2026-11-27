package com.yyoo.ototag

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** JNI bridge to the bundled libchromaprint (see app/jni/fp_jni.cpp). */
object FpNative {
    @Volatile private var loaded = false

    /** The native library loads only when a fingerprint is really computed. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) { System.loadLibrary("otofp"); loaded = true }
        }
    }

    external fun start(sampleRate: Int, channels: Int): Boolean
    external fun feed(data: ShortArray)
    external fun finish(): String?
}

/**
 * Computes a Chromaprint fingerprint for a local audio file by decoding
 * up to the first 120 seconds to PCM with MediaCodec. Returns
 * (fingerprint, durationSeconds), or null when the file cannot be
 * decoded (e.g. DSD) or the output is not 16-bit PCM.
 */
object Fingerprinter {
    fun fingerprint(file: File): Pair<String, Int>? {
        try { FpNative.ensureLoaded() } catch (e: Exception) { return null }
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i); fmt = f; break
                }
            }
            val format = fmt ?: return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var durationSec = if (format.containsKey(MediaFormat.KEY_DURATION))
                ((format.getLong(MediaFormat.KEY_DURATION) + 500_000) / 1_000_000).toInt()
            else 0
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var started = false
            var rate = 0; var channels = 0
            var fedFrames = 0L
            val maxFrames = 11025L * 120 * 12 // generous cap, refined once rate is known
            var frameCap = maxFrames
            val chunk = ShortArray(16384)
            var chunkLen = 0
            fun feedShorts(src: ShortArray, n: Int) {
                var off = 0
                while (off < n) {
                    val take = minOf(chunk.size - chunkLen, n - off)
                    System.arraycopy(src, off, chunk, chunkLen, take)
                    chunkLen += take; off += take
                    if (chunkLen == chunk.size) { FpNative.feed(chunk); chunkLen = 0 }
                }
            }
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (!started) {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) !=
                            android.media.AudioFormat.ENCODING_PCM_16BIT) return null
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        if (!FpNative.start(rate, channels)) return null
                        started = true
                        frameCap = rate.toLong() * channels * 120
                    }
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val shorts = ShortArray(sb.remaining())
                    sb.get(shorts)
                    // Do not feed past the 120 s cap.
                    val room = frameCap - fedFrames
                    val take = minOf(shorts.size.toLong(), room).toInt()
                    if (take > 0) { feedShorts(shorts, take); fedFrames += take }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (fedFrames >= frameCap) outDone = true
                }
            }
            if (!started) return null
            if (chunkLen > 0) FpNative.feed(chunk.copyOf(chunkLen))
            val fp = FpNative.finish() ?: return null
            if (durationSec <= 0 && rate > 0)
                durationSec = (fedFrames / channels / rate).toInt()
            return fp to durationSec
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}
