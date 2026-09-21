package org.jellyfin.androidtv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Removes the downloaded update package once the new version has replaced the old one.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver(), KoinComponent {
	private val updateManager by inject<UpdateManager>()

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
		updateManager.cleanupDownloadedPackages()
	}
}
