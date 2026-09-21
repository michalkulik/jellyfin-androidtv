package org.jellyfin.androidtv.update

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalShapes
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject

/**
 * The update prompt: shows the new version with its release notes and offers to install it, then
 * switches to the download progress and finally starts the system installer.
 *
 * It is hosted by [org.jellyfin.androidtv.ui.browsing.MainActivity] so it can be opened both by the
 * automatic check once the library screen is ready and from the About screen.
 */
@Composable
fun UpdateDialog(
	onDismissRequest: () -> Unit,
) {
	val updateManager = koinInject<UpdateManager>()
	val context = LocalContext.current
	val stateSnapshot by updateManager.state.collectAsStateWithLifecycle()
	val state = stateSnapshot
	val release = state.releaseOrNull

	DialogBase(
		visible = true,
		onDismissRequest = onDismissRequest,
	) {
		Column(
			modifier = Modifier
				.width(560.dp)
				.clip(LocalShapes.current.large)
				.background(JellyfinTheme.colorScheme.surface)
				.padding(Tokens.Space.spaceLg),
			verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		) {
			Text(
				text = stringResource(R.string.update_available_title),
				fontSize = Tokens.Typography.typographyFontSizeXl.value.sp,
				color = Tokens.Color.colorWhite,
			)

			val message = when (state) {
				is UpdateState.Failed -> stringResource(R.string.update_failed_message, state.reason.orEmpty())
				else -> stringResource(
					R.string.update_available_message,
					release?.version.orEmpty(),
					BuildConfig.VERSION_NAME,
				)
			}
			Text(text = message, color = Tokens.Color.colorWhite)

			val notes = release?.notes
			if (!notes.isNullOrBlank()) {
				Text(
					text = notes,
					color = Tokens.Color.colorWhite,
					modifier = Modifier
						.fillMaxWidth()
						.verticalScroll(rememberScrollState()),
				)
			}

			val downloading = state as? UpdateState.Downloading
			if (downloading != null) {
				DownloadProgressBar(progress = downloading.progress)
				Text(text = stringResource(R.string.update_downloading_progress, downloading.progress), color = Tokens.Color.colorWhite)
			}

			UpdateDialogActions(
				state = state,
				onPrimary = {
					when (state) {
						is UpdateState.Downloaded -> {
							if (!updateManager.install()) {
								val message = when {
									BuildConfig.DEBUG -> R.string.update_debug_no_install
									else -> R.string.update_install_permission_message
								}
								Toast.makeText(context, message, Toast.LENGTH_LONG).show()
							} else {
								onDismissRequest()
							}
						}

						else -> updateManager.download()
					}
				},
				onLater = {
					updateManager.snooze()
					onDismissRequest()
				},
			)
		}
	}
}

@Composable
private fun UpdateDialogActions(
	state: UpdateState,
	onPrimary: () -> Unit,
	onLater: () -> Unit,
) {
	val primaryLabel = when (state) {
		is UpdateState.Downloaded -> R.string.update_button_install
		is UpdateState.Failed -> R.string.update_button_retry
		else -> R.string.update_button_download
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm, Alignment.End),
	) {
		Button(
			onClick = onPrimary,
			enabled = state !is UpdateState.Downloading,
		) {
			Text(stringResource(primaryLabel))
		}

		Button(onClick = onLater) {
			Text(stringResource(R.string.update_button_later))
		}
	}
}

/**
 * Simple linear progress bar, the design system only ships a circular one.
 */
@Composable
private fun DownloadProgressBar(progress: Int) {
	val fraction = progress.coerceIn(0, UpdateManager.PROGRESS_MAX).toFloat() / UpdateManager.PROGRESS_MAX
	val trackShape = LocalShapes.current.extraSmall

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(8.dp)
			.clip(trackShape)
			.background(Color(0x33FFFFFF)),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(fraction)
				.height(8.dp)
				.clip(trackShape)
				.background(Color(0xFF00A4DC)),
		)
	}
}
