package org.jellyfin.androidtv.ui.itemdetail.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalShapes
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.design.Tokens

/** Confirmation shown before an external subtitle file is deleted. */
@Composable
fun ConfirmDeleteSubtitleDialog(
	name: String,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	DialogBase(
		visible = true,
		onDismissRequest = onDismiss,
	) {
		Column(
			modifier = Modifier
				.width(480.dp)
				.clip(LocalShapes.current.large)
				.background(JellyfinTheme.colorScheme.surface)
				.padding(Tokens.Space.spaceLg),
			verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		) {
			Text(
				text = stringResource(R.string.subtitle_download_delete_title),
				fontSize = Tokens.Typography.typographyFontSizeXl.value.sp,
				color = Tokens.Color.colorWhite,
			)
			Text(
				text = stringResource(R.string.subtitle_download_delete_message, name),
				color = Tokens.Color.colorWhite,
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm, Alignment.End),
			) {
				Button(onClick = onConfirm) {
					Text(stringResource(R.string.lbl_delete))
				}
				Button(onClick = onDismiss) {
					Text(stringResource(R.string.lbl_cancel))
				}
			}
		}
	}
}
