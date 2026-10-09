package org.jellyfin.androidtv.data.model

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant
import java.util.UUID

class DataRefreshService {
	var lastDeletedItemId: UUID? = null
	var lastPlayback: Instant? = null
	var lastMoviePlayback: Instant? = null
	var lastTvPlayback: Instant? = null
	var lastLibraryChange: Instant? = null
	var lastFavoriteUpdate: Instant? = null
	var lastPlayedItem: BaseItemDto? = null

	/** Item of the last reported playback stop. */
	var lastPlaybackItemId: UUID? = null

	/**
	 * The server's user data for the item immediately after the last reported playback stop. It
	 * already reflects the server's resume settings (the minimum/maximum watched percentage and
	 * the minimum resume duration), so the detail screen uses it instead of the raw stop position
	 * when the item was loaded before the server stored the stop event.
	 */
	var lastPlaybackUserData: UserItemDataDto? = null
}
