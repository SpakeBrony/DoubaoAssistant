package com.spake.brony.doubaoassistant.speech.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// SDK 回调只入队；解码和 AudioTrack 写入使用独立线程，不阻塞 SDK 或录音线程。
internal class TtsAudioTrackPlayer(
    private val onComplete: () -> Unit,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private val input = LinkedBlockingQueue<ByteArray>(4096)
    private val queuedBytes = AtomicInteger()
    private val trackLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var finished = false
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channels = 0
    private var writtenFrames = 0L
    private var segmentFrames = 0L
    private var maxSegmentFrames = Long.MAX_VALUE
    private var preSkip = 0
    private var timestamp = 0L
    private val info = MediaCodec.BufferInfo()
    private val worker = Thread(::consume, "TtsAudioTrack").apply { start() }

    @Synchronized fun append(data: ByteArray, length: Int) {
        if (cancelled) return
        check(!finished) { "TTS audio input already ended" }
        require(length in 1..data.size && length <= 1024 * 1024) { "Invalid TTS audio chunk" }
        val total = queuedBytes.addAndGet(length)
        if (total > 16 * 1024 * 1024 || !input.offer(data.copyOf(length))) {
            queuedBytes.addAndGet(-length)
            error("TTS audio queue exceeded its limit")
        }
    }

    @Synchronized fun finish() { finished = true }

    private fun consume() {
        try {
            val ogg = OggOpusStream(::configureDecoder, ::decode,
                onLastGranule = { maxSegmentFrames = (it - preSkip).coerceAtLeast(0) }, onEnd = ::endSegment)
            while (!cancelled) {
                val chunk = input.poll(20, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    queuedBytes.addAndGet(-chunk.size)
                    ogg.append(chunk)
                } else if (finished) {
                    ogg.finish()
                    val audio = checkNotNull(track) { "TTS returned no decoded PCM" }
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (!cancelled && (audio.playbackHeadPosition.toLong() and 0xffffffffL) < writtenFrames) {
                        check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack drain timed out" }
                        Thread.sleep(5)
                    }
                    if (!cancelled) onComplete()
                    return
                }
            }
        } catch (error: Exception) {
            if (!cancelled) onError(error)
        } finally {
            cancelled = true
            try { codec?.release() }
            catch (error: RuntimeException) { Log.w("DoubaoTts", "Decoder release failed", error) }
            codec = null
            synchronized(trackLock) {
                try { track?.release() }
                catch (error: RuntimeException) { Log.w("DoubaoTts", "AudioTrack release failed", error) }
                track = null
            }
            input.clear()
        }
    }

    private fun configureDecoder(header: ByteArray) {
        check(codec == null) { "Previous Opus stream did not end" }
        preSkip = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 65535
        segmentFrames = 0
        maxSegmentFrames = Long.MAX_VALUE
        timestamp = 0
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, header[9].toInt()).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(header))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(preSkip * 1_000_000_000L / 48000).apply { flip() })
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).apply { flip() })
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    private fun queue(packet: ByteArray?, flags: Int) {
        val decoder = checkNotNull(codec)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!cancelled) {
            val index = decoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                check((packet?.size ?: 0) <= buffer.remaining()) { "Opus packet exceeds decoder capacity" }
                if (packet != null) buffer.put(packet)
                decoder.queueInputBuffer(index, 0, packet?.size ?: 0, timestamp, flags)
                return
            }
            drain()
            check(SystemClock.elapsedRealtime() < deadline) { "Opus decoder input timed out" }
        }
    }

    private fun decode(packet: ByteArray) {
        if (cancelled) return
        val duration = OggOpusStream.durationUs(packet)
        queue(packet, 0)
        timestamp += duration
        drain()
    }

    private fun drain(): Boolean {
        val decoder = checkNotNull(codec)
        while (!cancelled) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> configureTrack(decoder.outputFormat)
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pcm = checkNotNull(decoder.getOutputBuffer(index))
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            writePcm(pcm)
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
                else -> return false
            }
        }
        return false
    }

    private fun endSegment() {
        if (cancelled || codec == null) return
        queue(null, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!cancelled && !drain()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Opus decoder drain timed out" }
            Thread.sleep(5)
        }
        codec?.release()
        codec = null
    }

    private fun configureTrack(format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        check(count in 1..2 && rate == 48000) { "Unexpected decoded PCM format: rate=$rate channels=$count" }
        check(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
        if (track != null) { check(rate == sampleRate && count == channels); return }
        val mask = if (count == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "AudioTrack buffer query failed: $minimum" }
        synchronized(trackLock) {
            if (cancelled) return
            val audio = AudioTrack.Builder().setAudioAttributes(VoiceAudioPlayer.playbackAttributes())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, rate / 10 * count * 2)).build()
            track = audio
            check(audio.state == AudioTrack.STATE_INITIALIZED)
            check(audio.setVolume(1.0f) == AudioTrack.SUCCESS)
            audio.play()
            sampleRate = rate
            channels = count
        }
        Log.i("DoubaoTts", "AudioTrack initialized usage=ASSISTANT rate=$rate channels=$count PCM16 gain=1.0")
    }

    private fun writePcm(pcm: ByteBuffer) {
        if (cancelled) return
        val audio = checkNotNull(track)
        val frameBytes = channels * 2
        check(pcm.remaining() % frameBytes == 0)
        val frames = minOf(pcm.remaining().toLong() / frameBytes, maxSegmentFrames - segmentFrames).coerceAtLeast(0)
        pcm.limit(pcm.position() + (frames * frameBytes).toInt())
        var deadline = SystemClock.elapsedRealtime() + 5000
        while (!cancelled && pcm.hasRemaining()) {
            val written = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0 && written % frameBytes == 0) { "AudioTrack write failed: $written" }
            if (written > 0) {
                writtenFrames += written / frameBytes
                segmentFrames += written / frameBytes
                deadline = SystemClock.elapsedRealtime() + 5000
            } else {
                check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack write timed out" }
                Thread.sleep(5)
            }
        }
    }

    @Synchronized override fun close() {
        cancelled = true
        input.clear()
        worker.interrupt()
        synchronized(trackLock) {
            try { track?.pause(); track?.flush() }
            catch (error: RuntimeException) { Log.w("DoubaoTts", "AudioTrack stop failed", error) }
        }
    }
}
