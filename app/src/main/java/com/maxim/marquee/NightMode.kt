package com.maxim.marquee

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Night mode: a gentle compressor with make-up gain and a limiter, so explosions don't jump out
 * and whispers don't disappear. On 5.1/7.1 it also lifts the centre (dialogue) channel and
 * tames the subwoofer. Works on decoded audio; toggling it on or off is instant.
 */
@OptIn(UnstableApi::class)
class NightModeProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled = false

    private var envelope = 0f
    private var attack = 0f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000).toFloat()
        attack = exp(-1f / (0.004f * sr))
        release = exp(-1f / (0.300f * sr))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        // Android's channel order for 5.1 / 7.1: FL, FR, FC, LFE, BL, BR (, SL, SR).
        val surround = ch >= 6
        val frames = size / (2 * ch)
        val frame = FloatArray(ch)
        repeat(frames) {
            var peak = 0f
            for (c in 0 until ch) {
                var v = input.short / 32768f
                if (surround && c == 2) v *= 1.41f // dialogue +3 dB
                if (surround && c == 3) v *= 0.5f // subwoofer -6 dB
                frame[c] = v
                if (!(surround && c == 3)) peak = maxOf(peak, abs(v))
            }
            envelope = if (peak > envelope) attack * envelope + (1 - attack) * peak else release * envelope + (1 - release) * peak
            // 3.5:1 above -18 dBFS, then +7 dB make-up.
            val threshold = 0.126f
            val gain = if (envelope > threshold) (threshold * (envelope / threshold).pow(1f / 3.5f)) / envelope else 1f
            val total = gain * 2.24f
            for (c in 0 until ch) {
                val v = (frame[c] * total).coerceIn(-0.97f, 0.97f)
                out.putShort((v * 32767f).toInt().toShort())
            }
        }
        // Any odd trailing bytes (shouldn't happen) are passed through untouched.
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    override fun onFlush() {
        envelope = 0f
    }

    override fun onReset() {
        envelope = 0f
    }
}

/**
 * Decoded 5.1 / 7.1 sound folded to stereo by the app instead of by Android. Android's own
 * downmix lowers the level a lot to be safe (dialogue ends up quiet on TV speakers); this one keeps
 * the voices at full level (centre channel into both sides), adds the surrounds, and uses a soft
 * limiter instead of turning everything down, so loud scenes don't distort.
 */
@OptIn(UnstableApi::class)
class StereoDownmixProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ch = inputAudioFormat.channelCount
        if (!enabled || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || (ch != 6 && ch != 8)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.85f) return x
        // Above 85 %: bend smoothly towards full scale instead of clipping.
        val over = (a - 0.85f) / 0.15f
        val y = 0.85f + 0.15f * (over / (1f + over))
        return if (x < 0) -y else y
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / (2 * ch)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val f = FloatArray(ch)
        // Android / FFmpeg order: FL, FR, FC, LFE, BL, BR (, SL, SR).
        repeat(frames) {
            for (c in 0 until ch) f[c] = input.short / 32768f
            val centre = 0.707f * f[2]
            val lfe = 0.3f * f[3]
            var l = f[0] + centre + lfe + 0.6f * f[4]
            var r = f[1] + centre + lfe + 0.6f * f[5]
            if (ch == 8) {
                l += 0.6f * f[6]
                r += 0.6f * f[7]
            }
            out.putShort((soft(l) * 32767f).toInt().toShort())
            out.putShort((soft(r) * 32767f).toInt().toShort())
        }
        while (input.hasRemaining()) input.get()
        out.flip()
    }
}
