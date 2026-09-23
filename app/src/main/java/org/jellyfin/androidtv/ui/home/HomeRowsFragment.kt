package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.UserDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository, userPreferences) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true
	private var rowsLoading = false
	private var libraryReloadAttempts = 0
	private var reloadRequested = false
	private var rowsBuilt = false
	private var lastLibraryReloadAt = 0L

	// Loading
	private val _loading = MutableStateFlow(true)
	private var libraryLoadWatchdog: Job? = null

	/**
	 * Whether the home is still being built and should be covered by a loader. Starts as `true`
	 * because the rows are always empty when the fragment is created.
	 */
	val loading: StateFlow<Boolean> = _loading.asStateFlow()

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	private companion object {
		/** How often the home rows are rebuilt in a row while the libraries stay empty. */
		private const val MAX_LIBRARY_RELOAD_ATTEMPTS = 3

		/**
		 * How long the loading overlay may stay up when the library rows never report that they are
		 * done. A request that gets lost must not cover the home forever.
		 */
		private const val LIBRARY_LOAD_TIMEOUT_MS = 15_000L

		/** How often the library rows are checked while the loading overlay is up. */
		private const val LIBRARY_LOAD_POLL_MS = 100L

		/**
		 * How long to wait before the rebuild attempts start counting again. The cap alone must not be
		 * permanent: a rebuild that keeps failing would otherwise leave the home empty until the app is
		 * restarted, which is exactly the bug this guards against.
		 */
		private const val LIBRARY_RELOAD_COOLDOWN_MS = 30_000L
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())

		loadRows()

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	/**
	 * Builds and shows the home rows. Safe to call more than once: when the initial load did not
	 * finish (for example the session was (re)created while the app was in the background) the rows
	 * are rebuilt so the home screen does not stay empty until the activity is recreated.
	 */
	@Suppress("TooGenericExceptionCaught")
	private fun loadRows(showLoader: Boolean = false) {
		Timber.i(
			"Home rows load requested (rowsLoading=%b, rowsBuilt=%b, rows=%d, showLoader=%b)",
			rowsLoading,
			rowsBuilt,
			adapter.size(),
			showLoader,
		)

		if (rowsLoading) {
			// A rebuild is already in flight (it can wait up to [withTimeout] for the session). Remember
			// the request instead of dropping it: otherwise a resume that happens while the first load
			// is still running does nothing, and if that load then fails the home stays empty until the
			// next resume.
			reloadRequested = true
			return
		}

		rowsLoading = true

		// The overlay only covers the home when there is nothing to look at yet, or when the rebuild
		// was asked for because the libraries were missing. Refreshing a home that is already on
		// screen must not blank it out.
		if (showLoader || adapter.size() == 0) setLoading(true)

		lifecycleScope.launch(Dispatchers.IO) {
			var built = false

			try {
				val currentUser = withTimeout(30.seconds) {
					userRepository.currentUser.filterNotNull().first()
				}

				val rows = buildRows(currentUser)

				withContext(Dispatchers.Main) {
					showRows(rows)
					built = true
				}
			} catch (e: TimeoutCancellationException) {
				// The session was not ready in time (for example it was recreated while the app was in
				// the background). Report it instead of failing silently, so the resume retry can pick it
				// up.
				Timber.w(e, "Timed out waiting for the current user, the home rows will be retried")
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Deliberately broad: a single unexpected failure while building the rows must not take
				// the whole home screen down, the resume retry rebuilds them.
				Timber.e(e, "Unable to build the home rows, they will be retried")
			} finally {
				rowsLoading = false
				rowsBuilt = built

				// Nothing was built, so there is nothing to wait for either: hiding the overlay lets the
				// user see the (possibly empty) home instead of a spinner that would never stop. The
				// resume retry picks the rows up again.
				if (!built) setLoading(false)

				if (reloadRequested) {
					reloadRequested = false
					// Only retry a load that produced nothing. A rebuild queued while a load that succeeded
					// was running (the resume that follows the create on every start) would otherwise rebuild
					// the rows a second time, which is visible as the home loading twice.
					if (!built) loadRows()
				}
			}
		}
	}

	/** Creates the configured home sections, in order. */
	private suspend fun buildRows(currentUser: UserDto): List<HomeFragmentRow> {
		// The library list must not take the whole home down with it when the request fails.
		val userViews = userViewsRepository.views
			.catch { error -> Timber.w(error, "Unable to load the user views for the home rows") }
			.firstOrNull()
			.orEmpty()

		// Check for coroutine cancellation
		if (!currentCoroutineContext().isActive) return emptyList()

		val rows = mutableListOf<HomeFragmentRow>()

		for (section in userSettingPreferences.activeHomesections) when (section) {
			HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded(userViews))
			HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(HomeFragmentViewsRow(small = false))
			HomeSectionType.LIBRARY_BUTTONS -> rows.add(HomeFragmentViewsRow(small = true))
			HomeSectionType.RESUME -> rows.add(helper.loadResumeVideo())
			HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
			HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
			HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
			HomeSectionType.NEXT_UP -> rows.add(helper.loadNextUp())
			HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
				rows.add(HomeFragmentLiveTVRow(requireActivity(), userRepository))
				rows.add(helper.loadOnNow())
			}

			HomeSectionType.NONE -> Unit
		}

		return rows
	}

	/** Replaces the rows of the adapter with [rows]. Runs on the main thread. */
	private fun showRows(rows: List<HomeFragmentRow>) {
		@Suppress("UNCHECKED_CAST")
		val rowsAdapter = adapter as MutableObjectAdapter<Row>
		val cardPresenter = CardPresenter()

		// Rebuild from scratch so a retry does not append duplicate rows
		rowsAdapter.clear()

		// Add rows in order
		notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
		nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
		for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)

		wireUpLiveTvSiblingRows(rowsAdapter)

		Timber.i("Showing %d home rows", rowsAdapter.size())

		// The rows are in place but the library rows fill themselves in asynchronously, so keep the
		// loading overlay up until they are done instead of letting the user watch them pop in.
		watchLibraryRowsLoading(rowsAdapter)
	}

	/** Shows or hides the loading overlay. Safe to call from any thread. */
	private fun setLoading(loading: Boolean) {
		if (_loading.value == loading) return

		_loading.value = loading
		Timber.i("Home loading overlay %s", if (loading) "shown" else "hidden")
	}

	/**
	 * Keeps the loading overlay up until every library row finished its first retrieve. The rows are
	 * added empty and filled in by their own request, so without this the home visibly rebuilds
	 * itself while the user is already looking at it.
	 *
	 * The timeout guards against a request that never reports back.
	 */
	private fun watchLibraryRowsLoading(rowsAdapter: MutableObjectAdapter<Row>) {
		/** Whether every library row on the home has finished retrieving its items. */
		fun librariesLoaded(): Boolean {
			for (index in 0 until rowsAdapter.size()) {
				val rowAdapter = (rowsAdapter.get(index) as? ListRow)?.adapter as? ItemRowAdapter ?: continue
				if (rowAdapter.queryType != QueryType.Views) continue
				if (rowAdapter.isCurrentlyRetrieving) return false
			}

			return true
		}

		libraryLoadWatchdog?.cancel()
		libraryLoadWatchdog = lifecycleScope.launch(Dispatchers.Main) {
			val deadline = System.currentTimeMillis() + LIBRARY_LOAD_TIMEOUT_MS
			while (System.currentTimeMillis() < deadline && !librariesLoaded()) {
				delay(LIBRARY_LOAD_POLL_MS)
			}

			setLoading(false)
		}
	}

	/** Lets the On Now row remove the Live TV buttons row when it has nothing to show. */
	private fun wireUpLiveTvSiblingRows(rowsAdapter: MutableObjectAdapter<Row>) {
		for (i in 1 until rowsAdapter.size()) {
			val itemAdapter = (rowsAdapter.get(i) as? ListRow)?.adapter as? ItemRowAdapter ?: continue
			if (itemAdapter.queryType != QueryType.LiveTvProgram) continue

			itemAdapter.setSiblingRow(rowsAdapter.get(i - 1))
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		// The initial load may not have finished, produced no rows at all or lost the libraries (for
		// example the session was (re)created while the app was in the background, or the request
		// failed because the app had been idle for a long time). Retry it so the user does not have
		// to leave and re-enter the app to get the libraries back.
		//
		// The loader is only asked for when the rebuild is caused by missing libraries: an empty
		// adapter already shows it, and a rebuild of a home that is on screen must stay invisible.
		val librariesMissing = rowsBuilt && adapter.size() > 0 && libraryRowsEmpty()
		if (!rowsBuilt || adapter.size() == 0 || librariesMissing) loadRows(showLoader = librariesMissing)

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	/**
	 * Whether the home should show library rows but has none with content. The rows are built by a
	 * single request that is not retried, so an app that was idle for a long time (stale session,
	 * failed request) can end up showing every other row while the libraries stay empty.
	 *
	 * The number of consecutive rebuild attempts is capped: an account without any library would
	 * otherwise trigger a new request on every resume.
	 */
	private fun libraryRowsEmpty(): Boolean {
		val sections = userSettingPreferences.activeHomesections
		val expectsLibraries = sections.any {
			it == HomeSectionType.LIBRARY_TILES_SMALL || it == HomeSectionType.LIBRARY_BUTTONS
		}
		if (!expectsLibraries) return false

		// The attempt budget only throttles the rebuilds; once the cooldown passed it starts over so
		// an empty home always recovers on its own instead of staying empty until a restart.
		if (libraryReloadAttempts >= MAX_LIBRARY_RELOAD_ATTEMPTS) {
			if (System.currentTimeMillis() - lastLibraryReloadAt < LIBRARY_RELOAD_COOLDOWN_MS) return false

			libraryReloadAttempts = 0
		}

		for (index in 0 until adapter.size()) {
			val rowAdapter = (adapter[index] as? ListRow)?.adapter as? ItemRowAdapter ?: continue
			if (rowAdapter.queryType != QueryType.Views) continue
			if (rowAdapter.size() > 0) {
				libraryReloadAttempts = 0
				return false
			}
		}

		// No library row with any item: rebuild the rows.
		libraryReloadAttempts++
		lastLibraryReloadAt = System.currentTimeMillis()
		Timber.i("Home has no libraries, rebuilding the rows (attempt %d)", libraryReloadAttempts)
		return true
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				//fill in default background
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

				backgroundService.setBackground(item.baseItem)
			}
		}
	}
}
