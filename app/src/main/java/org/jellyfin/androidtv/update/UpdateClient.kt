package org.jellyfin.androidtv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Downloads and parses the update manifest.
 *
 * Only plain OkHttp is used because the manifest is hosted by GitHub and not by the Jellyfin
 * server.
 */
class UpdateClient(
	private val okHttpClient: OkHttpClient,
) {
	private val json = Json { ignoreUnknownKeys = true }

	/**
	 * Fetches [manifestUrl] and returns the release matching [variant] when it is usable on this
	 * device.
	 *
	 * Returns null when the manifest cannot be parsed or does not contain the variant.
	 */
	suspend fun fetch(manifestUrl: String, variant: String): UpdateRelease? = withContext(Dispatchers.IO) {
		val body = try {
			execute(manifestUrl)
		} catch (e: IOException) {
			Timber.w(e, "Unable to fetch the update manifest")
			return@withContext null
		}

		val manifest = try {
			json.decodeFromString(UpdateManifest.serializer(), body)
		} catch (e: SerializationException) {
			Timber.e(e, "Unable to parse the update manifest")
			return@withContext null
		}

		val updateVariant = manifest.variants[variant] ?: run {
			Timber.w("The update manifest has no %s variant", variant)
			return@withContext null
		}

		val versionCode = manifest.versionCode ?: getVersionCodeFromName(manifest.version) ?: run {
			Timber.w("The update manifest has no usable version code")
			return@withContext null
		}

		if (!isAllowedDownloadUrl(updateVariant.url)) {
			Timber.e("Refusing to download the update from %s", updateVariant.url)
			return@withContext null
		}

		UpdateRelease(
			version = manifest.version,
			versionCode = versionCode,
			notes = manifest.notes,
			variant = variant,
			url = updateVariant.url,
			size = updateVariant.size,
			sha256 = updateVariant.sha256,
		)
	}

	private suspend fun execute(manifestUrl: String): String = suspendCoroutine { continuation ->
		val request = Request.Builder()
			.url(manifestUrl)
			.header("Accept", "application/json")
			.build()

		okHttpClient.newCall(request).enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				continuation.resumeWithException(e)
			}

			override fun onResponse(call: Call, response: Response) {
				response.use {
					if (!it.isSuccessful) {
						continuation.resumeWithException(IOException("HTTP ${it.code}"))
					} else {
						val body = it.body?.string()
						if (body == null) {
							continuation.resumeWithException(IOException("Empty response"))
						} else {
							continuation.resume(body)
						}
					}
				}
			}
		})
	}

	companion object {
		private val GITHUB_HOSTS = setOf(
			"github.com",
			"api.github.com",
			"objects.githubusercontent.com",
			"github-releases.githubusercontent.com",
		)

		/**
		 * Update packages are only accepted over HTTPS from GitHub. Debug builds additionally allow
		 * any host so the flow can be tested against a local manifest and APK.
		 */
		fun isAllowedDownloadUrl(url: String): Boolean {
			val lowercased = url.lowercase(Locale.ROOT)
			if (BuildConfig.DEBUG) return lowercased.startsWith("http://") || lowercased.startsWith("https://")

			if (!lowercased.startsWith("https://")) return false
			val host = lowercased.removePrefix("https://").substringBefore('/').substringBefore(':')
			return host in GITHUB_HOSTS || host.endsWith(".githubusercontent.com")
		}

		/**
		 * The URL of the manifest that describes the newest release of the fork.
		 */
		const val DEFAULT_MANIFEST_URL =
			"https://github.com/michalkulik/jellyfin-androidtv/releases/latest/download/latest.json"
	}
}
