package org.jellyfin.androidtv.update

import kotlinx.serialization.Serializable

/**
 * Parsed `latest.json`, published as an extra asset of every release next to the APK.
 *
 * The document is intentionally small and version independent so the app never needs to know which
 * release is the newest: it always fetches the manifest of the `releases/latest` release.
 */
@Serializable
data class UpdateManifest(
	val version: String,
	val versionCode: Int? = null,
	val publishedAt: String? = null,
	val notes: String? = null,
	val variants: Map<String, UpdateVariant> = emptyMap(),
)

/**
 * One downloadable artifact of a release.
 */
@Serializable
data class UpdateVariant(
	val url: String,
	val size: Long = 0L,
	val sha256: String? = null,
)

/**
 * A release that can actually be installed on this device, meaning the variant matching the running
 * app is present in the manifest.
 */
data class UpdateRelease(
	val version: String,
	val versionCode: Int,
	val notes: String?,
	val variant: String,
	val url: String,
	val size: Long,
	val sha256: String?,
) {
	/**
	 * Name of the file inside the update directory. The version code is part of the name so the
	 * cleanup can tell which downloads are outdated.
	 */
	val fileName: String
		get() = "jellyfin-androidtv-$versionCode.apk"

	companion object {
		/**
		 * Name pattern used by [fileName], used to read the version code back from the disk.
		 */
		val FILE_NAME_REGEX = Regex("""jellyfin-androidtv-(\d+)\.apk""")
	}
}

/**
 * Mirror of `buildSrc/src/main/kotlin/VersionUtils.kt` (MA.MI.PA-PR -> MAMIPAPR) so a manifest
 * without an explicit `versionCode` can still be compared with [org.jellyfin.androidtv.BuildConfig].
 *
 * Returns null for anything that cannot be parsed, because the manifest is untrusted input.
 */
@Suppress("MagicNumber")
fun getVersionCodeFromName(versionName: String): Int? {
	val (core, preRelease) = when (val index = versionName.indexOf('-')) {
		-1 -> versionName to null
		else -> versionName.substring(0, index) to versionName.substring(index + 1)
	}

	val parts = core.split('.').map { it.toIntOrNull() ?: return null }
	if (parts.size < 3) return null
	val (major, minor, patch) = parts

	val buildVersion = preRelease?.substringAfter('.')?.toIntOrNull()

	return major * 1000000 + minor * 10000 + patch * 100 + (buildVersion ?: 99)
}
