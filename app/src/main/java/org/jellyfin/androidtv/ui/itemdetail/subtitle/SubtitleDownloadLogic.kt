package org.jellyfin.androidtv.ui.itemdetail.subtitle

import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.CultureDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import java.util.Locale

/**
 * Pure helpers behind the subtitle download dialog, kept out of the composable so they can be
 * tested without an Android device. The functions take primitives instead of SDK models wherever
 * possible, because the models cannot be built without a server.
 */
object SubtitleDownloadLogic {
	/** The value Jellyfin uses when the user did not pick a preferred subtitle language. */
	const val LANGUAGE_DEFAULT = "Default"

	/** Fallback used when nothing else is known. */
	const val LANGUAGE_FALLBACK = "eng"

	/**
	 * Languages offered before the full list is expanded: the common European ones, so the usual
	 * choice is a press or two away instead of scrolling through hundreds of cultures.
	 */
	private val COMMON_LANGUAGES = listOf(
		"pol", "eng", "ger", "fre", "spa", "ita", "rus", "ukr", "cze", "slo", "hun", "por", "dut", "swe",
	)

	/** Subtitles that are already stored for the item, in the order the server reports them. */
	fun localSubtitles(streams: List<MediaStream>?): List<MediaStream> = streams
		.orEmpty()
		.filter { it.type == MediaStreamType.SUBTITLE }

	/**
	 * Whether an existing subtitle can be removed. Only external subtitles are separate files the
	 * server can delete; embedded ones live inside the video container.
	 */
	fun canDelete(isExternal: Boolean, path: String?): Boolean = isExternal && !path.isNullOrEmpty()

	/**
	 * The languages of the item, subtitles first, so a subtitle is a better hint than the audio track.
	 * Untagged ("und") streams are ignored.
	 */
	fun itemLanguages(streams: List<MediaStream>?): List<String> = streams
		.orEmpty()
		.filter { !it.language.isNullOrBlank() && it.language != "und" }
		.sortedBy { stream -> if (stream.type == MediaStreamType.SUBTITLE) 0 else 1 }
		.mapNotNull { it.language }

	/**
	 * The language the dialog should start with.
	 *
	 * The library language is not exposed to regular API clients, so the order is: the preference the
	 * user configured on the server, then the language already present on the item, then the language
	 * of the device and finally English.
	 */
	fun resolveDefaultLanguage(preference: String?, itemLanguages: List<String>): String {
		val configured = preference
			?.takeIf { it.isNotBlank() && !it.equals(LANGUAGE_DEFAULT, ignoreCase = true) }
		if (configured != null) return configured

		itemLanguages.firstOrNull { it.isNotBlank() }?.let { return it }

		// The method call (instead of a property) is used because Kotlin does not expose
		// getISO3Language() under a predictable property name. It can also throw when the locale has
		// no three letter code.
		val deviceLanguage = runCatching { Locale.getDefault().getISO3Language() }.getOrNull()
		if (!deviceLanguage.isNullOrBlank()) return deviceLanguage

		return LANGUAGE_FALLBACK
	}

	/**
	 * The languages shown in the list: either a short list of common languages or every culture.
	 *
	 * [preferred] (the current language followed by the languages the item already uses) always comes
	 * first so it stays visible while scrolling.
	 *
	 * The server returns several cultures that share one three letter code (for example "spa" for
	 * Spanish and "Spanish; Castilian"), so only the first culture of a code is kept. Repeating a code
	 * would break the list, which draws every entry as a separate item.
	 */
	fun languageMenu(
		languages: List<CultureDto>,
		preferred: List<String>,
		showAll: Boolean,
	): List<CultureDto> {
		val byCode = languages
			.mapNotNull { culture -> culture.threeLetterIsoLanguageName?.lowercase()?.let { it to culture } }
			.groupBy({ it.first }, { it.second })

		val order = preferred + if (showAll) emptyList() else COMMON_LANGUAGES
		val known = order
			.mapNotNull { code -> byCode[code.lowercase()]?.first() }
			.distinctBy { it.threeLetterIsoLanguageName?.lowercase() }

		if (!showAll) return known

		val knownCodes = known.mapNotNull { it.threeLetterIsoLanguageName?.lowercase() }.toSet()
		val rest = byCode
			.filterKeys { it !in knownCodes }
			.values
			.mapNotNull { it.firstOrNull() }
			.sortedBy { it.displayName?.lowercase() }

		return known + rest
	}

	/** The display name of a language code, falling back to the code itself. */
	fun languageDisplayName(languages: List<CultureDto>, code: String?): String {
		if (code.isNullOrBlank()) return ""

		return languages
			.firstOrNull { it.threeLetterIsoLanguageName.equals(code, ignoreCase = true) }
			?.displayName
			?: code
	}

	/** The string resources of the badges of a search result, in display order. */
	fun subtitleResultFlags(result: RemoteSubtitleInfo): List<Int> = buildList {
		if (result.isHashMatch == true) add(R.string.subtitle_download_perfect_match)
		if (result.forced == true) add(R.string.subtitle_download_forced)
		if (result.hearingImpaired == true) add(R.string.subtitle_download_hearing_impaired)
		if (result.machineTranslated == true) add(R.string.subtitle_download_machine_translated)
		if (result.aiTranslated == true) add(R.string.subtitle_download_ai_translated)
	}

	/** The caption of a search result: format, downloads and frame rate. */
	fun subtitleResultCaption(format: String?, downloadCount: Int?, frameRate: Float?): String = buildList {
		format?.let { add(it.uppercase()) }
		downloadCount?.let { add("$it ↓") }
		frameRate?.let { add("${it}fps") }
	}.joinToString(" · ")
}
