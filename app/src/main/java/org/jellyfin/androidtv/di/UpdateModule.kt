package org.jellyfin.androidtv.di

import okhttp3.OkHttpClient
import org.jellyfin.androidtv.update.UpdateClient
import org.jellyfin.androidtv.update.UpdateManager
import org.jellyfin.androidtv.update.UpdatePromptController
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.koin.dsl.module

/**
 * In-app updater. The manifest and the packages live on GitHub, so a plain OkHttp client is used
 * instead of the Jellyfin API client.
 */
val updateModule = module {
	single<OkHttpClient> { get<OkHttpFactory>().createClient(get<HttpClientOptions>()) }

	single { UpdateClient(get()) }
	single { UpdatePromptController() }

	// Created at start so the application can run the throttled check without touching the UI.
	single(createdAtStart = true) { UpdateManager(get(), get(), get()) }
}
