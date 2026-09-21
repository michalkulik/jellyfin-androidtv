package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.update.UpdateManager
import org.koin.core.context.GlobalContext
import timber.log.Timber

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()

		// Clean up packages from an earlier update and check for a newer version in the background.
		// The prompt itself is offered once the library screen is ready, not during startup.
		val updateManager = GlobalContext.getOrNull()?.get<UpdateManager>() ?: return
		updateManager.cleanupDownloadedPackages()
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			runCatching { updateManager.check(force = true) }
				.onFailure { Timber.w(it, "Unable to check for app updates") }
		}
	}
}
