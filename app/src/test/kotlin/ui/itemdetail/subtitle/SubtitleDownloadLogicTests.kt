package org.jellyfin.androidtv.ui.itemdetail.subtitle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.CultureDto

class SubtitleDownloadLogicTests : FunSpec({
	val json = Json { ignoreUnknownKeys = true }

	fun culture(code: String, name: String) = json.decodeFromString(
		CultureDto.serializer(),
		"""{ "Name": "$code", "DisplayName": "$name", "TwoLetterISOLanguageName": "xx", "ThreeLetterISOLanguageName": "$code", "ThreeLetterISOLanguageNames": [] }""",
	)

	test("the configured user preference wins over the item language") {
		SubtitleDownloadLogic.resolveDefaultLanguage("ger", listOf("pol")) shouldBe "ger"
	}

	test("the Jellyfin Default preference falls through to the item language") {
		SubtitleDownloadLogic.resolveDefaultLanguage("Default", listOf("pol", "eng")) shouldBe "pol"
		SubtitleDownloadLogic.resolveDefaultLanguage("", listOf("pol")) shouldBe "pol"
		SubtitleDownloadLogic.resolveDefaultLanguage(null, listOf("pol")) shouldBe "pol"
	}

	test("an item without languages still resolves to a usable language code") {
		// The exact value depends on the machine locale, but it must never be blank.
		SubtitleDownloadLogic.resolveDefaultLanguage(null, emptyList()).isBlank() shouldBe false
		SubtitleDownloadLogic.resolveDefaultLanguage(null, listOf("")).isBlank() shouldBe false
	}

	test("only external subtitle files with a path can be deleted") {
		SubtitleDownloadLogic.canDelete(isExternal = true, path = "/media/sub.srt") shouldBe true
		SubtitleDownloadLogic.canDelete(isExternal = false, path = "/media/sub.srt") shouldBe false
		SubtitleDownloadLogic.canDelete(isExternal = true, path = null) shouldBe false
		SubtitleDownloadLogic.canDelete(isExternal = true, path = "") shouldBe false
	}

	test("the short language menu starts with the preferred languages") {
		val languages = listOf(culture("eng", "English"), culture("pol", "Polish"), culture("ger", "German"))

		val menu = SubtitleDownloadLogic.languageMenu(
			languages = languages,
			preferred = listOf("pol", "ger"),
			showAll = false,
		)

		menu.mapNotNull { it.threeLetterIsoLanguageName } shouldBe listOf("pol", "ger", "eng")
	}

	test("the short language menu never repeats a language") {
		val languages = listOf(culture("pol", "Polish"))

		val menu = SubtitleDownloadLogic.languageMenu(
			languages = languages,
			preferred = listOf("pol", "pol"),
			showAll = false,
		)

		menu.size shouldBe 1
	}

	test("the full language menu appends the remaining cultures alphabetically") {
		val languages = listOf(culture("zul", "Zulu"), culture("eng", "English"), culture("ara", "Arabic"))

		val menu = SubtitleDownloadLogic.languageMenu(
			languages = languages,
			preferred = listOf("eng"),
			showAll = true,
		)

		menu.mapNotNull { it.threeLetterIsoLanguageName } shouldBe listOf("eng", "ara", "zul")
	}

	test("a language is shown by its display name") {
		val languages = listOf(culture("pol", "Polish"))

		SubtitleDownloadLogic.languageDisplayName(languages, "pol") shouldBe "Polish"
		SubtitleDownloadLogic.languageDisplayName(languages, "xyz") shouldBe "xyz"
		SubtitleDownloadLogic.languageDisplayName(languages, null) shouldBe ""
	}

	test("the result caption lists format, downloads and frame rate") {
		SubtitleDownloadLogic.subtitleResultCaption("srt", 3332, null) shouldBe "SRT · 3332 ↓"
		SubtitleDownloadLogic.subtitleResultCaption(null, null, 23.976f) shouldBe "23.976fps"
		SubtitleDownloadLogic.subtitleResultCaption(null, null, null) shouldBe ""
	}
})
