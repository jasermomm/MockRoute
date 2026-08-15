package com.jasermomm.mockroute

import android.app.Application
import com.jasermomm.mockroute.data.LocalStore
import com.jasermomm.mockroute.data.SettingsRepository
import com.jasermomm.mockroute.network.NominatimClient
import com.jasermomm.mockroute.network.OsrmClient

class MockRouteApp : Application() {
    val localStore by lazy { LocalStore(this) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val searchClient by lazy { NominatimClient(this) }
    val routeClient by lazy { OsrmClient() }

}
