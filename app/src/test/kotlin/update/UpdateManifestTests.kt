package org.jellyfin.androidtv.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class UpdateManifestTests : FunSpec({
	test("getVersionCodeFromName mirrors VersionUtils.getVersionCode") {
		getVersionCodeFromName("0.1.1") shouldBe 10199
		getVersionCodeFromName("0.1.2") shouldBe 10299
		getVersionCodeFromName("1.1.1") shouldBe 1010199
		getVersionCodeFromName("0.7.0") shouldBe 70099
		getVersionCodeFromName("2.0.0-rc.3") shouldBe 2000003
		getVersionCodeFromName("99.99.99-rc.1") shouldBe 99999901
	}

	test("getVersionCodeFromName rejects unusable input") {
		getVersionCodeFromName("1.0").shouldBeNull()
		getVersionCodeFromName("a.b.c").shouldBeNull()
		getVersionCodeFromName("").shouldBeNull()
	}

	test("update release file name round trips through the regex") {
		val release = UpdateRelease(
			version = "0.1.2",
			versionCode = 10299,
			notes = null,
			variant = UpdateManager.VARIANT,
			url = "https://github.com/michalkulik/jellyfin-androidtv/releases/download/v0.1.2/app.apk",
			size = 0L,
			sha256 = null,
		)

		val match = UpdateRelease.FILE_NAME_REGEX.matchEntire(release.fileName)
		val parsed = match?.groupValues?.get(1)?.toIntOrNull()

		parsed shouldBe release.versionCode
	}
})
