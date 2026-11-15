package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WifiNetworkTest {
    @Test fun `only an active non VPN wifi network is eligible for LAN playback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        val wifi = WifiNetwork(context)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val active = connectivity.activeNetwork!!
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.setNetworkCapabilities(active, capabilities)
        assertEquals(active.networkHandle, wifi.current())
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        assertNull(wifi.current())
        shadowOf(capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertNull(wifi.current())
        shadow.setActiveNetworkInfo(null)
        assertNull(wifi.current())
    }
}
