package org.jellyfin.androidtv.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.preference.SystemPreferences
import timber.log.Timber
import java.io.File

/**
 * Owns the update state machine: checking for a newer release, downloading it, and starting the
 * system installer.
 *
 * The download itself runs in [UpdateDownloadWorker] so it survives the user leaving the screen.
 * The worker reports back through the `internal` callbacks at the bottom of this class.
 */
class UpdateManager(
	private val context: Context,
	private val systemPreferences: SystemPreferences,
	private val updateClient: UpdateClient,
) {
	companion object {
		/** How long a successful or failed check is considered fresh for the non forced checks. */
		private const val CHECK_MAX_AGE_MS = 15 * 60 * 1000L

		/**
		 * Forced checks closer together than this are collapsed. Measured inside the process, so the
		 * app start check always runs even right after a previous run of the app.
		 */
		private const val CHECK_MIN_INTERVAL_MS = 60 * 1000L

		/** How long "Later" hides the prompt. A newer release is shown again right away. */
		private const val SNOOZE_DURATION_MS = 24 * 60 * 60 * 1000L

		/**
		 * Key of the artifact in the update manifest. Unlike the phone app there is only one build,
		 * so a single variant is enough.
		 */
		const val VARIANT = "tv"

		/** Directory holding downloaded packages, inside the app private storage. */
		const val UPDATE_DIRECTORY_NAME = "updates"

		/** The download progress is reported as a percentage. */
		const val PROGRESS_MAX = 100
	}

	private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
	val state: StateFlow<UpdateState> = _state.asStateFlow()

	/** Guards against overlapping checks and downloads. */
	private val mutex = Mutex()

	/** Used for the fire and forget work started from the user interface. */
	private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	/** When the last request was made in this process, see [CHECK_MIN_INTERVAL_MS]. */
	private var lastCheckAt = 0L

	/** Directory holding downloaded packages, inside the app private storage. */
	val updatesDirectory: File
		get() = File(context.filesDir, UPDATE_DIRECTORY_NAME)

	/**
	 * Looks for a newer release.
	 *
	 * @param force when false the last check must be older than [CHECK_MAX_AGE_MS], which keeps the
	 *   repeated calls (app start, every return to the library screen) from hammering the manifest.
	 * @param ignoreThrottle skips the in-process guard as well, used when the user asked explicitly.
	 */
	@Suppress("CyclomaticComplexMethod")
	suspend fun check(force: Boolean = false, ignoreThrottle: Boolean = false): UpdateState {
		val now = System.currentTimeMillis()

		if (!ignoreThrottle) {
			// The app start check and the check after landing on the library screen can happen
			// seconds apart, this keeps them from both hitting the network.
			if (force && now - lastCheckAt < CHECK_MIN_INTERVAL_MS) return state.value
			if (!force && now - systemPreferences[SystemPreferences.updateLastCheck] < CHECK_MAX_AGE_MS) return state.value
		}

		val manifestUrl = systemPreferences[SystemPreferences.updateManifestUrl].ifEmpty { UpdateClient.DEFAULT_MANIFEST_URL }

		return mutex.withLock {
			val previous = state.value
			if (previous is UpdateState.Downloading) return@withLock previous

			_state.value = UpdateState.Checking

			val release = updateClient.fetch(manifestUrl, VARIANT)
			val finishedAt = System.currentTimeMillis()
			systemPreferences[SystemPreferences.updateLastCheck] = finishedAt
			lastCheckAt = finishedAt

			val nextState = when {
				release == null -> when (previous) {
					// Keep a known update visible when a later check cannot reach the manifest.
					is UpdateState.Available -> previous
					is UpdateState.Downloaded -> previous
					else -> UpdateState.UpToDate
				}
				release.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(release)
				else -> UpdateState.UpToDate
			}

			Timber.i(
				"Update check finished: installed=%d manifest=%s state=%s",
				BuildConfig.VERSION_CODE,
				release?.versionCode?.toString() ?: "unavailable",
				nextState::class.simpleName,
			)

			nextState.also { _state.value = it }
		}
	}

	/**
	 * Manual check started by the user, for example from the About screen.
	 *
	 * Neither the throttles nor the snooze apply here, because the user asked for the check. Returns
	 * the newer release, or null when the installed version is current.
	 */
	suspend fun checkManually(): UpdateRelease? {
		check(force = true, ignoreThrottle = true)
		return (state.value as? UpdateState.Available)?.release
	}

	/**
	 * Whether the prompt should be shown right now. "Later" hides it for [SNOOZE_DURATION_MS], but a
	 * version newer than the snoozed one is offered again immediately.
	 */
	fun shouldPrompt(): Boolean {
		val release = (state.value as? UpdateState.Available)?.release ?: return false
		if (release.versionCode > systemPreferences[SystemPreferences.updateSnoozedVersionCode]) return true
		return System.currentTimeMillis() >= systemPreferences[SystemPreferences.updateSnoozeUntil]
	}

	/**
	 * Remembers that the user does not want to be asked again right now.
	 */
	fun snooze() {
		val release = state.value.releaseOrNull ?: return
		systemPreferences[SystemPreferences.updateSnoozedVersionCode] = release.versionCode
		systemPreferences[SystemPreferences.updateSnoozeUntil] = System.currentTimeMillis() + SNOOZE_DURATION_MS
	}

	/**
	 * Starts downloading the available release. The progress is reported through [state].
	 */
	fun download() {
		val release = (state.value as? UpdateState.Available)?.release ?: return
		coroutineScope.launch { UpdateDownloadWorker.start(context, release) }
	}

	/**
	 * Cancels a running download and forgets its progress.
	 */
	suspend fun cancelDownload() {
		UpdateDownloadWorker.cancel(context)
		_state.value = state.value.releaseOrNull?.let { UpdateState.Available(it) } ?: UpdateState.Unknown
	}

	/**
	 * Hands the verified package to the system installer. When Android does not allow the app to
	 * install packages yet, the matching settings screen is opened first and the call has to be
	 * repeated by the user.
	 */
	fun install(): Boolean {
		val downloaded = state.value as? UpdateState.Downloaded ?: return false

		if (!UpdateInstaller.canRequestPackageInstalls(context)) {
			Timber.i("Install permission missing, opening the unknown sources screen")
			UpdateInstaller.openUnknownSourcesSettings(context)
			return false
		}

		return UpdateInstaller.install(context, downloaded.file)
	}

	/**
	 * Removes downloaded packages that are not newer than the installed version, for example after a
	 * successful update.
	 */
	fun cleanupDownloadedPackages() {
		val files = updatesDirectory.listFiles() ?: return
		files.forEach { file ->
			val versionCode = UpdateRelease.FILE_NAME_REGEX.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
			if (versionCode == null || versionCode <= BuildConfig.VERSION_CODE) {
				if (file.delete()) Timber.i("Removed outdated update package %s", file.name)
			}
		}
	}

	// region Called by UpdateDownloadWorker

	internal fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
		val release = state.value.releaseOrNull ?: return
		val progress = when {
			totalBytes <= 0L -> 0
			else -> ((downloadedBytes * PROGRESS_MAX) / totalBytes).toInt().coerceIn(0, PROGRESS_MAX)
		}
		_state.value = UpdateState.Downloading(release, progress)
	}

	internal fun onDownloadStarted(release: UpdateRelease) {
		_state.value = UpdateState.Downloading(release, 0)
	}

	internal fun onDownloadFinished(release: UpdateRelease, file: File) {
		_state.value = UpdateState.Downloaded(release, file)
	}

	internal fun onDownloadFailed(release: UpdateRelease?, reason: String?) {
		_state.value = UpdateState.Failed(release, reason)
	}

	// endregion
}
