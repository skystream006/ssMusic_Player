package com.ssytdlp.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaSessionManager

// Android has no service aliases. These themed browser entries expose the one playback service.
open class CarIconService : MediaBrowserServiceCompat() {
    private lateinit var browser: MediaBrowserCompat
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private val pending = mutableSetOf<() -> Unit>()
    private var failed = false

    override fun onCreate() {
        super.onCreate()
        browser = MediaBrowserCompat(this, ComponentName(this, PlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    if (sessionToken == null) setSessionToken(browser.sessionToken)
                    failed = false
                    finishWaiting(true)
                    // Separate options keep one-shot root loads from re-triggering this observer.
                    val options = Bundle().apply {
                        putInt(MediaBrowserCompat.EXTRA_PAGE, 0)
                        putInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, 1)
                    }
                    browser.subscribe(carRoot.mediaId, options, object : MediaBrowserCompat.SubscriptionCallback() {
                        override fun onChildrenLoaded(id: String, children: List<MediaBrowserCompat.MediaItem>, options: Bundle) =
                            notifyChildrenChanged(id)
                        override fun onError(id: String, options: Bundle) = notifyChildrenChanged(id)
                    })
                }

                override fun onConnectionFailed() = connectionLost()
                override fun onConnectionSuspended() = connectionLost()
            }, null)
        browser.connect()
    }

    private fun connectionLost() {
        failed = true
        DebugLog.event(DebugEvent.PLAYBACK_FAILURE, error = IllegalStateException("Car browser connection lost"))
        finishWaiting(false)
        pending.toList().forEach { it() }
        notifyChildrenChanged(carRoot.mediaId)
    }

    private fun finishWaiting(connected: Boolean) {
        waiting.toList().also { waiting.clear() }.forEach { it(connected) }
    }

    private fun connected(action: (Boolean) -> Unit) {
        when {
            browser.isConnected -> action(true)
            failed -> action(false)
            else -> waiting.add(action)
        }
    }

    private fun <T> load(result: Result<T>, request: ((T?) -> Unit) -> Unit) {
        result.detach()
        connected { ready ->
            if (!ready) result.sendResult(null) else {
                var finished = false
                lateinit var fail: () -> Unit
                val complete: (T?) -> Unit = { value ->
                    if (!finished) {
                        finished = true
                        pending.remove(fail)
                        result.sendResult(value)
                    }
                }
                fail = { complete(null) }
                pending.add(fail)
                request(complete)
            }
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val trusted = MediaSessionManager.getSessionManager(this).isTrustedForMediaControl(currentBrowserInfo)
        if (!isTrustedCarController(clientPackageName, clientUid, trusted)) return null
        if (clientPackageName == "com.google.android.projection.gearhead")
            grantUriPermission(clientPackageName, CarArtwork.ROOT,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        return BrowserRoot(carRoot.mediaId, Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        })
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        load(result) { complete ->
            browser.subscribe(parentId, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(id: String, children: List<MediaBrowserCompat.MediaItem>) {
                    browser.unsubscribe(parentId, this)
                    complete(children)
                }
                override fun onError(id: String) {
                    browser.unsubscribe(parentId, this)
                    complete(null)
                }
            })
        }
    }

    override fun onLoadItem(itemId: String, result: Result<MediaBrowserCompat.MediaItem>) {
        load(result) { complete ->
            browser.getItem(itemId, object : MediaBrowserCompat.ItemCallback() {
                override fun onItemLoaded(item: MediaBrowserCompat.MediaItem?) = complete(item)
                override fun onError(id: String) = complete(null)
            })
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        load(result) { complete ->
            browser.search(query, extras, object : MediaBrowserCompat.SearchCallback() {
                override fun onSearchResult(query: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) =
                    complete(items)
                override fun onError(query: String, extras: Bundle?) = complete(null)
            })
        }
    }

    override fun onDestroy() {
        finishWaiting(false)
        pending.toList().forEach { it() }
        browser.disconnect()
        super.onDestroy()
    }

    class Default : CarIconService()
    class MidnightLight : CarIconService()
    class MidnightDark : CarIconService()
    class RoyalPurpleLight : CarIconService()
    class RoyalPurpleDark : CarIconService()
    class GoldLight : CarIconService()
    class GoldDark : CarIconService()
    class GreenLight : CarIconService()
    class GreenDark : CarIconService()
    class PinkLight : CarIconService()
    class PinkDark : CarIconService()
    class BlackLight : CarIconService()
    class BlackDark : CarIconService()
}
