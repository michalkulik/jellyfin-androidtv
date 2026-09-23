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
		SubtitleDownloadLogic.languageDisplayName(languages, null) shouldBe ""
	}

	test("a language the culture list does not describe is still named") {
		// The library can be configured with a language while the culture list is unavailable, so the
		// device locales are the last resort before showing the bare code.
		SubtitleDownloadLogic.languageDisplayName(emptyList(), "pol").isBlank() shouldBe false
		SubtitleDownloadLogic.languageDisplayName(emptyList(), "xyz") shouldBe "xyz"
	}

	test("the configured library languages win over the user preference") {
		SubtitleDownloadLogic.resolveDefaultLanguage(
			preference = "ger",
			itemLanguages = listOf("pol"),
			configuredLanguages = listOf("pol", "eng"),
		) shouldBe "pol"
	}

	test("inside the configured languages the user preference is honored") {
		SubtitleDownloadLogic.resolveDefaultLanguage(
			preference = "eng",
			itemLanguages = listOf("pol"),
			configuredLanguages = listOf("pol", "eng"),
		) shouldBe "eng"
	}

	test("the first configured language is the fallback") {
		SubtitleDownloadLogic.resolveDefaultLanguage(
			preference = null,
			itemLanguages = emptyList(),
			configuredLanguages = listOf("pol", "eng"),
		) shouldBe "pol"
	}

	test("without a library configuration the previous order still applies") {
		SubtitleDownloadLogic.resolveDefaultLanguage(
			preference = "ger",
			itemLanguages = listOf("pol"),
			configuredLanguages = emptyList(),
		) shouldBe "ger"
	}

	test("the dropdown offers exactly the configured library languages") {
		val languages = listOf(culture("pol", "Polish"), culture("eng", "English"), culture("ger", "German"))

		val options = SubtitleDownloadLogic.languageOptions(
			configuredLanguages = listOf("eng", "pol"),
			languages = languages,
			preferred = listOf("pol"),
			showAll = false,
		)

		// The library order is kept, and nothing outside the configured list appears.
		options.map { it.code } shouldBe listOf("eng", "pol")
		options.map { it.name } shouldBe listOf("English", "Polish")
	}

	test("without a library configuration the dropdown keeps offering every language") {
		val languages = listOf(culture("eng", "English"), culture("pol", "Polish"), culture("ger", "German"))

		val options = SubtitleDownloadLogic.languageOptions(
			configuredLanguages = emptyList(),
			languages = languages,
			preferred = listOf("pol"),
			showAll = false,
		)

		options.map { it.code } shouldBe listOf("pol", "eng", "ger")
	}

	test("the result caption lists format, downloads and frame rate") {
		SubtitleDownloadLogic.subtitleResultCaption("srt", 3332, null) shouldBe "SRT · 3332 ↓"
		SubtitleDownloadLogic.subtitleResultCaption(null, null, 23.976f) shouldBe "23.976fps"
		SubtitleDownloadLogic.subtitleResultCaption(null, null, null) shouldBe ""
	}
})
