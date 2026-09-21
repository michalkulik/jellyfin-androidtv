package org.jellyfin.androidtv.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides whether the update prompt should be on screen.
 *
 * The prompt is owned by the [org.jellyfin.androidtv.ui.browsing.MainActivity] so it can be opened
 * from the automatic check after the library screen is ready and from the About screen when the
 * user asks for it explicitly.
 */
class UpdatePromptController {
	private val _visible = MutableStateFlow(false)
	val visible: StateFlow<Boolean> = _visible.asStateFlow()

	fun show() {
		_visible.value = true
	}

	fun hide() {
		_visible.value = false
	}
}
