package expo.modules.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * E3-3 — the "microphone is open" cue. Procedurally synthesised (no bundled asset): a short,
 * clean, ASCENDING electronic tone under 500ms — a two-oscillator glide (fundamental + a fifth
 * above) with a fast attack and an exponential tail, so it reads as an SF / assistant chirp
 * rather than a flat beep.
 *
 * Respects the system: nothing plays unless the ringer mode is NORMAL (silent / vibrate → mute),
 * and the amplitude tracks the device's media-volume fraction.
 */
object WakeSound {
  private const val SAMPLE_RATE = 44100
  private const val DURATION_MS = 380
  private const val F_START = 430.0   // Hz
  private const val F_END = 1180.0    // Hz — ascending glide

  @Volatile
  private var lastPlayedAt = 0L

  fun play(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastPlayedAt < 250) return // de-dupe rapid double calls
    lastPlayedAt = now

    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return // silent / vibrate → no sound

    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val volFrac = (am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    if (volFrac <= 0f) return

    thread(name = "BensonWakeSound") {
      try { synthAndPlay(volFrac) } catch (_: Exception) {}
    }
  }

  private fun synthAndPlay(volFrac: Float) {
    val n = SAMPLE_RATE * DURATION_MS / 1000
    val buf = ShortArray(n)
    val attack = (SAMPLE_RATE * 0.008).toInt()   // 8ms fast attack
    val peak = (0.72f * volFrac).coerceIn(0f, 0.95f)

    var phase1 = 0.0
    var phase2 = 0.0
    for (i in 0 until n) {
      val t = i.toDouble() / n // 0..1 progress
      // Exponential frequency glide start -> end (perceptually linear "rise").
      val f = F_START * Math.pow(F_END / F_START, t)
      phase1 += 2.0 * PI * f / SAMPLE_RATE
      phase2 += 2.0 * PI * (f * 1.5) / SAMPLE_RATE // a fifth above, quieter — adds the "synth" body

      // Envelope: fast attack, exponential decay over the tail.
      val env = when {
        i < attack -> i.toDouble() / attack
        else -> exp(-3.0 * (i - attack).toDouble() / (n - attack))
      }
      val s = (sin(phase1) * 0.75 + sin(phase2) * 0.25 + sin(phase1 * 2) * 0.06) * env
      buf[i] = (s * peak * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
    }

    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(SAMPLE_RATE)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(buf.size * 2)
      .setTransferMode(AudioTrack.MODE_STATIC)
      .build()

    track.write(buf, 0, buf.size)
    track.setNotificationMarkerPosition(n)
    track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
      override fun onMarkerReached(t: AudioTrack?) { try { t?.release() } catch (_: Exception) {} }
      override fun onPeriodicNotification(t: AudioTrack?) {}
    })
    track.play()
  }
}
