package org.jellyfin.androidtv.update

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.update.UpdateManager.Companion.UPDATE_DIRECTORY_NAME
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Downloads the update package and verifies it, then reports the result back to [UpdateManager].
 *
 * Runs in WorkManager so the download continues when the user leaves the screen that started it.
 */
class UpdateDownloadWorker(
	context: Context,
	parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
	companion object {
		private val tag = UpdateDownloadWorker::class.qualifiedName!!

		private const val KEY_URL = "url"
		private const val KEY_SHA256 = "sha256"
		private const val KEY_SIZE = "size"
		private const val KEY_VERSION = "version"
		private const val KEY_VERSION_CODE = "versionCode"
		private const val KEY_VARIANT = "variant"
		private const val KEY_NOTES = "notes"

		/** How often the progress and the state are updated. */
		private const val PROGRESS_INTERVAL_MS = 250L

		/** One byte of a checksum as two lowercase hex digits. */
		private const val HEX_BYTE_FORMAT = "%02x"

		suspend fun start(context: Context, release: UpdateRelease) {
			val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>().apply {
				addTag(tag)
				setInputData(
					workDataOf(
						KEY_URL to release.url,
						KEY_SHA256 to release.sha256,
						KEY_SIZE to release.size,
						KEY_VERSION to release.version,
						KEY_VERSION_CODE to release.versionCode,
						KEY_VARIANT to release.variant,
						KEY_NOTES to release.notes,
					),
				)
			}.build()

			WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request).await()
		}

		suspend fun cancel(context: Context) {
			WorkManager.getInstance(context).cancelUniqueWork(tag).await()
		}
	}

	private val updateManager by inject<UpdateManager>()
	private val okHttpClient by inject<OkHttpClient>()

	private val release: UpdateRelease = UpdateRelease(
		version = inputData.getString(KEY_VERSION) ?: "0.0.0",
		versionCode = inputData.getInt(KEY_VERSION_CODE, 0),
		notes = inputData.getString(KEY_NOTES),
		variant = inputData.getString(KEY_VARIANT) ?: UpdateManager.VARIANT,
		url = inputData.getString(KEY_URL).orEmpty(),
		size = inputData.getLong(KEY_SIZE, 0L),
		sha256 = inputData.getString(KEY_SHA256),
	)

	/**
	 * Any unexpected failure ends the download with a message instead of crashing the worker, so a
	 * broken manifest or an unreachable file cannot leave the updater stuck.
	 */
	@Suppress("TooGenericExceptionCaught")
	override suspend fun doWork(): Result {
		Timber.i("UpdateDownloadWorker started for %s", release.version)
		if (release.url.isEmpty()) {
			updateManager.onDownloadFailed(release, "Missing download url")
			return Result.failure()
		}

		updateManager.onDownloadStarted(release)

		val directory = File(applicationContext.filesDir, UPDATE_DIRECTORY_NAME)
		if (!directory.exists() && !directory.mkdirs()) {
			updateManager.onDownloadFailed(release, "Unable to create the update directory")
			return Result.failure()
		}

		val target = File(directory, release.fileName)
		val partial = File(directory, "${release.fileName}.part")

		return try {
			val digest = withContext(Dispatchers.IO) { download(release.url, partial) }

			val expected = release.sha256?.lowercase()
			val actual = digest?.toHexString()
			if (expected != null && actual != null && expected != actual) {
				Timber.e("Update package checksum mismatch: expected %s, got %s", expected, actual)
				partial.delete()
				updateManager.onDownloadFailed(release, "Checksum mismatch")
				return Result.failure()
			}

			if (target.exists()) target.delete()
			if (!partial.renameTo(target)) {
				partial.delete()
				updateManager.onDownloadFailed(release, "Unable to store the update package")
				return Result.failure()
			}

			Timber.i("Update package %s downloaded (%d bytes)", target.name, target.length())
			updateManager.onDownloadFinished(release, target)
			Result.success()
		} catch (e: CancellationException) {
			partial.delete()
			throw e
		} catch (e: IOException) {
			Timber.w(e, "Update download failed")
			partial.delete()
			updateManager.onDownloadFailed(release, e.message)
			Result.failure()
		} catch (e: Exception) {
			Timber.e(e, "Update download failed")
			partial.delete()
			updateManager.onDownloadFailed(release, e.message)
			Result.failure()
		}
	}

	/**
	 * Streams the package into [target] and returns the sha256 of the written bytes.
	 */
	@Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
	private suspend fun download(url: String, target: File): ByteArray? {
		val request = Request.Builder().url(url).build()
		val digest = MessageDigest.getInstance("SHA-256")

		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val body = response.body ?: throw IOException("Empty response")
			val total = body.contentLength().takeIf { it > 0L } ?: release.size

			body.byteStream().use { input ->
				target.outputStream().use { output ->
					copyStream(input, output, digest, total)
				}
			}
		}

		updateManager.onDownloadProgress(target.length(), target.length())
		return digest.digest()
	}

	/**
	 * Copies the download into [output], updating the checksum and reporting progress along the way.
	 */
	private suspend fun copyStream(input: InputStream, output: OutputStream, digest: MessageDigest, total: Long) {
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var downloaded = 0L
		var lastUpdate = 0L

		while (true) {
			currentCoroutineContext().ensureActive()
			val read = input.read(buffer)
			if (read == -1) break

			output.write(buffer, 0, read)
			digest.update(buffer, 0, read)
			downloaded += read

			val now = SystemClock.elapsedRealtime()
			if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
				lastUpdate = now
				updateManager.onDownloadProgress(downloaded, total)
			}
		}
	}

	private fun ByteArray.toHexString(): String = joinToString("") { byte -> HEX_BYTE_FORMAT.format(byte) }
}
