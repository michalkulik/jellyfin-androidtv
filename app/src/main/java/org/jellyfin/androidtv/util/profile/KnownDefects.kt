package org.jellyfin.androidtv.util.profile

import android.os.Build

/**
 * List of device models with known HEVC DoVi/HDR10+ playback issues.
 */
private val modelsWithDoViHdr10PlusBug = listOf(
	"AFTKRT", // Amazon Fire TV 4K Max (2nd Gen)
	"AFTKA", // Amazon Fire TV 4K Max (1st Gen)
	"AFTKM", // Amazon Fire TV 4K (2nd Gen)
	"AFTMM", // Amazon Fire TV 4K (1st Gen)
)

/**
 * List of device models with unreported Dolby Vision Profile 7 support.
 */
private val modelsWithUnreportedDoviProfile7Support = listOf(
	"SHIELD Android TV", // NVIDIA Shield TV Pro 2019 (mdarcy)
)

/**
 * List of device models whose hardware HEVC decoder reports support (including Main 10)
 * but fails to actually play HEVC, which leaves playback stuck on a black screen.
 * HEVC must not be advertised for these models so the server transcodes to H.264 instead.
 */
private val modelsWithBrokenHevcHardware = listOf(
	"DCTIW362_PLAY", // PlayBoxTV (Sagemcom DCTIW362_PLAY, Android 9)
)

object KnownDefects {
	val hevcDoviHdr10PlusBug = Build.MODEL in modelsWithDoViHdr10PlusBug
	val unreportedDoviProfile7Support = Build.MODEL in modelsWithUnreportedDoviProfile7Support
	val brokenHevcHardware = Build.MODEL in modelsWithBrokenHevcHardware
}
