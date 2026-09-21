package org.jellyfin.androidtv.ui.settings.screen.about

import android.content.ClipData
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.util.copyAction
import org.jellyfin.androidtv.update.UpdateManager
import org.jellyfin.androidtv.update.UpdatePromptController
import org.jellyfin.androidtv.update.UpdateState
import org.koin.compose.koinInject

@Composable
fun SettingsAboutScreen(launchedFromLogin: Boolean = false) {
	val router = LocalRouter.current

	SettingsColumn {
		if (launchedFromLogin) item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_login).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		} else item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		}

		item {
			val heading = "Jellyfin app version"
			val caption = "jellyfin-androidtv ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_jellyfin), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
				modifier = Modifier.focusKey("version")
			)
		}

		item {
			UpdateCheckListButton()
		}

		item {
			val heading = stringResource(R.string.pref_device_model)
			val caption = "${Build.MANUFACTURER} ${Build.MODEL}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
				modifier = Modifier.focusKey("device_model")
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_guide), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.licenses_link)) },
				onClick = { router.push(Routes.LICENSES) },
				modifier = Modifier.focusKey(Routes.LICENSES)
			)
		}

		if (!launchedFromLogin) item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_flask), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_developer_link)) },
				onClick = { router.push(Routes.DEVELOPER) },
				modifier = Modifier.focusKey(Routes.DEVELOPER)
			)
		}
	}
}

/**
 * Manual update check. A newer version opens the same prompt as the automatic check, an up to date
 * install only updates the caption so nothing pops up when there is nothing to install.
 */
@Composable
private fun UpdateCheckListButton() {
	val updateManager = koinInject<UpdateManager>()
	val updatePromptController = koinInject<UpdatePromptController>()
	val scope = rememberCoroutineScope()

	var checking by remember { mutableStateOf(false) }
	var result by remember { mutableStateOf<String?>(null) }

	val upToDateMessage = stringResource(R.string.update_check_up_to_date)
	val failedMessage = stringResource(R.string.update_check_failed)

	val caption = when {
		checking -> stringResource(R.string.update_checking)
		result != null -> result
		else -> null
	}

	ListButton(
		leadingContent = { Icon(painterResource(R.drawable.ic_loop), contentDescription = null) },
		headingContent = { Text(stringResource(R.string.update_check_for_updates)) },
		captionContent = caption?.let { { Text(it) } },
		modifier = Modifier.focusKey("check_for_updates"),
		onClick = {
			if (checking) return@ListButton

			checking = true
			result = null
			scope.launch {
				val release = updateManager.checkManually()
				checking = false

				if (release != null) {
					updatePromptController.show()
				} else {
					result = when (updateManager.state.value) {
						is UpdateState.UpToDate -> upToDateMessage
						else -> failedMessage
					}
				}
			}
		},
	)
}
