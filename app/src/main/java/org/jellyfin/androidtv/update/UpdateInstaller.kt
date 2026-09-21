package org.jellyfin.androidtv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * Starts the system package installer for a downloaded update.
 *
 * The package is shared through a [FileProvider] because the update directory lives in the app
 * private storage, which the installer cannot read.
 */
object UpdateInstaller {
	const val APK_MIME_TYPE = "application/vnd.android.package-archive"

	/**
	 * The provider authority declared in the manifest. Derived from the application id so the debug
	 * build gets its own authority.
	 */
	fun authority(context: Context): String = "${context.packageName}.updates"

	/**
	 * Whether Android allows this app to install packages. Always true before Android 8.
	 */
	fun canRequestPackageInstalls(context: Context): Boolean =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

	/**
	 * Opens the per app "install unknown apps" screen, where the user grants the permission that
	 * [canRequestPackageInstalls] reports.
	 */
	fun openUnknownSourcesSettings(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
			.setData("package:${context.packageName}".toUri())
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		runCatching { context.startActivity(intent) }
			.onFailure { Timber.e(it, "Unable to open the unknown sources settings") }
	}

	/**
	 * Launches the installer for [file].
	 *
	 * Debug builds are signed with a different key and use a different application id, so a release
	 * package can never be installed over them: the intent is built (which validates the provider
	 * configuration) but not launched.
	 */
	@Suppress("TooGenericExceptionCaught")
	fun install(context: Context, file: File): Boolean {
		val uri = try {
			getContentUri(context, file)
		} catch (e: Exception) {
			Timber.e(e, "Unable to create a content uri for %s", file.name)
			return false
		}

		if (BuildConfig.DEBUG) {
			Timber.i("Debug build, not launching the installer for %s", uri)
			return false
		}

		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, APK_MIME_TYPE)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}

		return try {
			context.startActivity(intent)
			true
		} catch (e: Exception) {
			Timber.e(e, "Unable to start the package installer")
			false
		}
	}

	/**
	 * Content uri of a downloaded package, used by [install].
	 */
	fun getContentUri(context: Context, file: File): Uri =
		FileProvider.getUriForFile(context, authority(context), file)
}
