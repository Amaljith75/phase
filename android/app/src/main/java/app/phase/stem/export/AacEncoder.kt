package app.phase.stem.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts the engine's 16-bit stereo WAV output to AAC-LC in an M4A
 * container using the device's built-in encoder (no bundled codec).
 */
object AacEncoder {
    fun wavToM4a(wav: File, out: File, bitrateKbps: Int, onProgress: (Float) -> Unit = {}) {
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val channels = h.getShort(22).toInt()
            val rate = h.getInt(24)
            if (String(header, 0, 4) != "RIFF" || channels !in 1..2) throw IOException("Unsupported WAV")
            val dataLen = raf.length() - 44
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(96, 320) * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            out.delete()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val bytesPerFrame = 2 * channels
                var read = 0L
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(16384)
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val want = minOf(buf.remaining().toLong(), chunk.size.toLong(), dataLen - read).toInt() / bytesPerFrame * bytesPerFrame
                            val pts = read / bytesPerFrame * 1_000_000L / rate
                            if (want <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                raf.readFully(chunk, 0, want)
                                buf.put(chunk, 0, want)
                                codec.queueInputBuffer(idx, 0, want, pts, 0)
                                read += want
                                onProgress(read.toFloat() / dataLen)
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        o >= 0 -> {
                            val ob = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                muxer.writeSampleData(track, ob, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } catch (t: Throwable) {
                out.delete()
                throw t
            } finally {
                runCatching { codec.stop() }
                codec.release()
                runCatching { if (muxing) muxer.stop() }
                muxer.release()
            }
        }
    }
}
