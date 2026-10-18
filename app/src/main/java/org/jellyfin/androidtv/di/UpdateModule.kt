package org.jellyfin.androidtv.di

import okhttp3.OkHttpClient
import org.jellyfin.androidtv.update.UpdateClient
import org.jellyfin.androidtv.update.UpdateManager
import org.jellyfin.androidtv.update.UpdatePromptController
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.koin.dsl.module
import java.time.Duration

/**
 * In-app updater. The manifest and the packages live on GitHub, so a plain OkHttp client is used
 * instead of the Jellyfin API client.
 */
val updateModule = module {
	// The SDK default client sets a 30 second callTimeout, and a callTimeout covers the *whole*
	// call — including reading the response body. A release APK is tens of megabytes, so on a
	// slower connection the download never finishes within that window and fails with a timeout.
	// For the updater we drop the total-call limit and raise the read timeout: a stalled
	// connection is still caught, while a slow but steady one completes.
	single<OkHttpClient> {
		get<OkHttpFactory>().createClient(get<HttpClientOptions>()).newBuilder()
			.connectTimeout(Duration.ofSeconds(30))
			.readTimeout(Duration.ofSeconds(60))
			.writeTimeout(Duration.ofSeconds(60))
			.callTimeout(Duration.ZERO)
			.build()
	}

	single { UpdateClient(get()) }
	single { UpdatePromptController() }

	// Created at start so the application can run the throttled check without touching the UI.
	single(createdAtStart = true) { UpdateManager(get(), get(), get()) }
}
