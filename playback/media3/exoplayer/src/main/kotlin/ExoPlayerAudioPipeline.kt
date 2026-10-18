package org.jellyfin.playback.media3.exoplayer

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import timber.log.Timber

/**
 * Applies volume normalization to the audio output using the platform [LoudnessEnhancer]
 * audio effect.
 *
 * The effect is bound to a specific audio session id. Whenever the session changes (or
 * playback stops) the previous effect must be explicitly disabled and released, otherwise a
 * leftover effect can stay attached to a stale session and keep amplifying (and distorting)
 * the audio until the app is restarted.
 */
class ExoPlayerAudioPipeline {
	private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
	private var loudnessEnhancer: LoudnessEnhancer? = null

	var normalizationGain: Float? = null
		set(value) {
			if (field == value) return
			Timber.d("Normalization gain changed to $value")
			field = value
			applyGain()
		}

	fun setAudioSessionId(audioSessionId: Int) {
		Timber.d("Audio session id changed to $audioSessionId")

		this.audioSessionId = audioSessionId

		// Always drop the previous effect before creating a new one, so no effect is left
		// attached to a session that is no longer feeding audio.
		releaseEnhancer()

		// A session id of 0 means "unset". Attaching an effect to it would target the global
		// output mix and amplify (and distort) unrelated audio of other apps as well.
		if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
			loudnessEnhancer = createEnhancer(audioSessionId)
		}

		// Re-apply current normalization gain
		applyGain()
	}

	/**
	 * Disable and release the audio effect and forget the current normalization gain. Call this
	 * when playback stops so a pending gain cannot leak into the next playback.
	 */
	fun release() {
		// Forget the current gain so a stale value cannot be applied to the next playback, and
		// release the effect so nothing keeps boosting a session that is going away. The session
		// id is kept so the effect can be re-created lazily: ExoPlayer keeps the same audio
		// session across stop/start, so onAudioSessionIdChanged may not fire again.
		normalizationGain = null
		releaseEnhancer()
	}

	private fun createEnhancer(audioSessionId: Int): LoudnessEnhancer? =
		runCatching { LoudnessEnhancer(audioSessionId) }
			.onFailure { Timber.w(it, "Failed to create LoudnessEnhancer") }
			.getOrNull()

	private fun releaseEnhancer() {
		loudnessEnhancer?.let { enhancer ->
			// Make sure it does not boost anything anymore before releasing it.
			runCatching { enhancer.enabled = false }
			runCatching { enhancer.release() }
		}
		loudnessEnhancer = null
	}

	private fun applyGain() {
		val targetGain = normalizationGain
			// Convert to millibels
			?.times(100f)
			// Round to integer
			?.toInt()
			// Ignore if zero (so the enhancer will be disabled)
			?.takeIf { it != 0 }

		// (Re)create the effect lazily: it may have been released on stop while the audio session
		// is still valid and a new normalization gain is being applied.
		val enhancer = loudnessEnhancer ?: if (targetGain != null && audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
			createEnhancer(audioSessionId)?.also { loudnessEnhancer = it }
		} else {
			null
		}

		if (enhancer == null) {
			Timber.d("LoudnessEnhancer is not initialized")
			return
		}

		Timber.d("Applying gain (targetGain=$targetGain)")
		runCatching {
			enhancer.setTargetGain(targetGain ?: 0)
		}.onSuccess {
			enhancer.setEnabled(targetGain != null)
		}.onFailure { error ->
			Timber.e(error, "Failed to apply gain of $targetGain")
			enhancer.setEnabled(false)
		}
	}
}
