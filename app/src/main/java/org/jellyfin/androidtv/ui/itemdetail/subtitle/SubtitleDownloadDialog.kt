package org.jellyfin.androidtv.ui.itemdetail.subtitle

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalShapes
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.localizationApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CultureDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/** Tag used to find the already attached dialog host inside the details view. */
private const val HOST_TAG = "subtitle-download-dialog"

/** How long to wait for the dialog exit transition before the host view is removed. */
private const val HOST_REMOVE_DELAY_MS = 400L

// The API calls of the dialog. They are plain suspend functions so the composable stays readable.

private suspend fun ApiClient.loadSubtitleItem(itemId: UUID): Result<BaseItemDto> = runCatching {
	withContext(Dispatchers.IO) { userLibraryApi.getItem(itemId = itemId).content }
}

private suspend fun ApiClient.searchSubtitles(itemId: UUID, language: String): Result<List<RemoteSubtitleInfo>> =
	runCatching {
		withContext(Dispatchers.IO) { subtitleApi.searchRemoteSubtitles(itemId, language).content }
	}

private suspend fun ApiClient.downloadSubtitle(itemId: UUID, subtitleId: String): Result<Unit> = runCatching {
	withContext(Dispatchers.IO) { subtitleApi.downloadRemoteSubtitles(itemId, subtitleId) }
}

private suspend fun ApiClient.deleteSubtitle(itemId: UUID, index: Int): Result<Unit> = runCatching {
	withContext(Dispatchers.IO) { subtitleApi.deleteSubtitle(itemId, index) }
}

/**
 * Shows the subtitle download dialog over the given fragment.
 *
 * The dialog is a Compose window hosted by a [ComposeView] attached to the fragment, which keeps it
 * independent from the Leanback row layout of the details screen.
 */
object SubtitleDownloadDialogHost {
	@JvmStatic
	fun show(fragment: Fragment, itemId: UUID) {
		val container = fragment.view as? ViewGroup ?: run {
			Timber.w("Unable to show the subtitle dialog without a view")
			return
		}

		if (container.findViewWithTag<ComposeView>(HOST_TAG) != null) return

		val host = ComposeView(fragment.requireContext()).apply {
			tag = HOST_TAG
			isFocusable = false
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				SubtitleDownloadDialog(
					itemId = itemId,
					onDismiss = { removeHost(container) },
				)
			}
		}

		container.addView(
			host,
			ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
		)
	}

	private fun removeHost(container: ViewGroup) {
		// The dialog animates out, so the host is only removed once the exit transition finished.
		container.postDelayed({
			container.findViewWithTag<ComposeView>(HOST_TAG)?.let(container::removeView)
		}, HOST_REMOVE_DELAY_MS)
	}
}

private data class SubtitleDialogState(
	val item: BaseItemDto? = null,
	val languages: List<CultureDto> = emptyList(),
	val language: String = SubtitleDownloadLogic.LANGUAGE_FALLBACK,
	val results: List<RemoteSubtitleInfo>? = null,
	val loading: Boolean = false,
	val message: Int? = null,
	val showAllLanguages: Boolean = false,
)

@Composable
fun SubtitleDownloadDialog(
	itemId: UUID,
	onDismiss: () -> Unit,
) {
	val api = koinInject<ApiClient>()
	val userRepository = koinInject<UserRepository>()
	val scope = rememberCoroutineScope()
	var state by remember { mutableStateOf(SubtitleDialogState()) }
	var pendingDelete by remember { mutableStateOf<MediaStream?>(null) }
	var visible by remember { mutableStateOf(true) }

	val close = {
		visible = false
		onDismiss()
	}

	// Loads the item (to list its subtitles) and the language list once.
	LaunchedEffect(itemId) {
		val cultures = runCatching {
			withContext(Dispatchers.IO) { api.localizationApi.getCultures().content }
		}.getOrDefault(emptyList())

		state = state.copy(languages = cultures)

		val item = api.loadSubtitleItem(itemId)

		state = item.fold(
			onSuccess = { loaded ->
				state.copy(
					item = loaded,
					language = SubtitleDownloadLogic.resolveDefaultLanguage(
						preference = userRepository.currentUser.value?.configuration?.subtitleLanguagePreference,
						itemLanguages = SubtitleDownloadLogic.itemLanguages(loaded.mediaStreams),
					),
				)
			},
			onFailure = { error ->
				Timber.w(error, "Unable to load the item for the subtitle dialog")
				state.copy(message = R.string.subtitle_download_error)
			},
		)
	}

	DialogBase(
		visible = visible,
		onDismissRequest = close,
	) {
		Column(
			modifier = Modifier
				.width(760.dp)
				.heightIn(max = 620.dp)
				.clip(LocalShapes.current.large)
				.background(JellyfinTheme.colorScheme.surface)
				.padding(Tokens.Space.spaceMd),
		) {
			LazyColumn(
				verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
				modifier = Modifier.fillMaxSize(),
			) {
				item {
					ListSection(
						headingContent = { Text(stringResource(R.string.subtitle_download_title)) },
						captionContent = { state.item?.name?.let { name -> Text(name) } },
					)
				}

				subtitlesSection(state.item, onDeleteRequested = { pendingDelete = it })

				languageSection(
					state = state,
					onSelectLanguage = { state = state.copy(language = it, results = null) },
					onToggleShowAll = { state = state.copy(showAllLanguages = !state.showAllLanguages) },
				)

				// Full width rows instead of a button row: on a TV the focus order of a wrapped row is
				// unpredictable, while stacked rows are reached in order with the D-pad.
				item {
					ListButton(
						headingContent = { Text(stringResource(R.string.subtitle_download_search)) },
						leadingContent = {
							Icon(
								painter = painterResource(R.drawable.ic_search),
								contentDescription = null,
								tint = Tokens.Color.colorWhite,
								modifier = Modifier.size(20.dp),
							)
						},
						enabled = !state.loading && state.language.isNotEmpty(),
						onClick = {
							scope.launch {
								state = state.copy(loading = true, results = null, message = null)
								state = api.searchSubtitles(itemId, state.language).fold(
									onSuccess = { results -> state.copy(loading = false, results = results) },
									onFailure = { error ->
										Timber.w(error, "Unable to search for subtitles")
										state.copy(loading = false, message = R.string.subtitle_download_error)
									},
								)
							}
						},
					)
				}

				item {
					ListButton(
						headingContent = { Text(stringResource(R.string.lbl_close)) },
						onClick = close,
					)
				}

				if (state.loading) {
					item {
						Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
							CircularProgressIndicator(
								modifier = Modifier.size(28.dp),
								color = Tokens.Color.colorWhite,
							)
						}
					}
				}

				state.message?.let { message ->
					item {
						ListMessage {
							Text(stringResource(message))
						}
					}
				}

				resultsSection(state) { result ->
					scope.launch {
						state = state.copy(loading = true, message = null)
						state = api.downloadSubtitle(itemId, result.id.orEmpty()).fold(
							onSuccess = {
								Timber.i("Queued the subtitle download %s", result.id)
								state.copy(loading = false, message = R.string.subtitle_download_queued)
							},
							onFailure = { error ->
								Timber.w(error, "Unable to download the subtitle")
								state.copy(loading = false, message = R.string.subtitle_download_error)
							},
						)
					}
				}
			}
		}
	}

	pendingDelete?.let { stream ->
		ConfirmDeleteSubtitleDialog(
			name = stream.displayTitle ?: stream.language.orEmpty(),
			onConfirm = {
				pendingDelete = null
				scope.launch {
					state = api.deleteSubtitle(itemId, stream.index).fold(
						onSuccess = { state.copy(message = R.string.subtitle_download_deleted) },
						onFailure = { error ->
							Timber.w(error, "Unable to delete the subtitle")
							state.copy(message = R.string.subtitle_download_error)
						},
					)

					// Re-read the item so the list reflects the deletion.
					api.loadSubtitleItem(itemId).onSuccess { loaded -> state = state.copy(item = loaded) }
				}
			},
			onDismiss = { pendingDelete = null },
		)
	}
}

/** The subtitles the item already has, with a delete action for external files. */
private fun androidx.compose.foundation.lazy.LazyListScope.subtitlesSection(
	item: BaseItemDto?,
	onDeleteRequested: (MediaStream) -> Unit,
) {
	item {
		ListSection(headingContent = { Text(stringResource(R.string.subtitle_download_existing)) })
	}

	val subtitles = SubtitleDownloadLogic.localSubtitles(item?.mediaStreams)
	if (subtitles.isEmpty()) {
		item {
			ListMessage {
				Text(stringResource(R.string.subtitle_download_none))
			}
		}
		return
	}

	items(subtitles, key = { stream -> stream.index }) { stream ->
		val deletable = SubtitleDownloadLogic.canDelete(stream.isExternal, stream.path)

		ListButton(
			headingContent = { Text(stream.displayTitle ?: stream.language.orEmpty()) },
			captionContent = { stream.path?.takeIf { it.isNotEmpty() }?.let { path -> Text(path) } },
			enabled = deletable,
			onClick = { onDeleteRequested(stream) },
			trailingContent = {
				if (deletable) {
					Icon(
						painter = painterResource(R.drawable.ic_delete),
						contentDescription = stringResource(R.string.lbl_delete),
						tint = Tokens.Color.colorWhite,
						modifier = Modifier.size(24.dp),
					)
				}
			},
		)
	}
}

/** The language selector. A short list of common languages is shown first. */
private fun androidx.compose.foundation.lazy.LazyListScope.languageSection(
	state: SubtitleDialogState,
	onSelectLanguage: (String) -> Unit,
	onToggleShowAll: () -> Unit,
) {
	item {
		ListSection(headingContent = { Text(stringResource(R.string.subtitle_download_language)) })
	}

	val languages = SubtitleDownloadLogic.languageMenu(
		languages = state.languages,
		preferred = listOfNotNull(state.language) + SubtitleDownloadLogic.itemLanguages(state.item?.mediaStreams),
		showAll = state.showAllLanguages,
	)

	// Keyed by position: several cultures can share a three letter code and duplicate keys are fatal.
	itemsIndexed(languages) { index, culture ->
		val code = culture.threeLetterIsoLanguageName.orEmpty()
		val selected = code.equals(state.language, ignoreCase = true)

		ListButton(
			headingContent = { Text(culture.displayName ?: culture.name.orEmpty()) },
			onClick = { onSelectLanguage(code) },
			trailingContent = {
				if (selected) {
					Icon(
						painter = painterResource(R.drawable.ic_check),
						contentDescription = null,
						tint = Tokens.Color.colorWhite,
						modifier = Modifier.size(24.dp),
					)
				}
			},
		)
	}

	item {
		val label = if (state.showAllLanguages) R.string.lbl_close else R.string.subtitle_download_all_languages
		ListButton(
			headingContent = { Text(stringResource(label)) },
			onClick = onToggleShowAll,
		)
	}
}

/** The remote search results, grouped by provider as the server returns them. */
private fun androidx.compose.foundation.lazy.LazyListScope.resultsSection(
	state: SubtitleDialogState,
	onDownload: (RemoteSubtitleInfo) -> Unit,
) {
	val results = state.results ?: return

	item {
		ListSection(headingContent = { Text(stringResource(R.string.subtitle_download_results)) })
	}

	if (results.isEmpty()) {
		item {
			ListMessage {
				Text(stringResource(R.string.subtitle_download_no_results))
			}
		}
		return
	}

	// The server does not guarantee unique result ids, so the rows are keyed by position.
	itemsIndexed(results) { index, result ->
		ListButton(
			headingContent = { Text(result.name.orEmpty()) },
			overlineContent = { result.providerName?.let { provider -> Text(provider) } },
			captionContent = {
				Text(
					SubtitleDownloadLogic.subtitleResultCaption(
						format = result.format,
						downloadCount = result.downloadCount,
						frameRate = result.frameRate,
					),
				)
			},
			footerContent = {
				val labels = mutableListOf<String>()
				// A plain loop is used because the string resources can only be resolved in a
				// composable context, not inside a lambda passed to joinToString/map.
				for (flag in SubtitleDownloadLogic.subtitleResultFlags(result)) {
					labels += stringResource(flag)
				}
				if (labels.isNotEmpty()) Text(labels.joinToString(" · "))
			},
			enabled = !state.loading,
			onClick = { onDownload(result) },
		)
	}
}

@Composable
private fun ConfirmDeleteSubtitleDialog(
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
