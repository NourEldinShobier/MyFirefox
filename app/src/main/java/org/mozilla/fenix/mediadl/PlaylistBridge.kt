/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.app.Activity
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Reads the videos already loaded on a playlist page (so private playlists and lazily loaded lists work
 * without any extra request to the site) through a small built-in extension.
 */
object PlaylistBridge {
    private const val EXTENSION_ID = "playlist@myfirefox.org"
    private const val PORT = "mozacPlaylist"
    private const val EXTENSION_URL = "resource://android/assets/extensions/playlist/"

    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, PORT)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var started = false
    private var activity = WeakReference<Activity>(null)

    /** Installs the extension once and keeps every selected tab connected to it. */
    fun start(engine: Engine, store: BrowserStore, host: Activity) {
        activity = WeakReference(host)
        if (started) return
        started = true
        controller.install(engine, onSuccess = {
            Log.i("PlaylistBridge", "extension installed")
            scope.launch {
                store.flow()
                    .map { it.selectedTab?.engineState?.engineSession }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect {
                        Log.i("PlaylistBridge", "registering page handler")
                        controller.registerContentMessageHandler(it, PageMessages(), PORT)
                    }
            }
        }, onError = { Log.w("PlaylistBridge", "extension install failed", it) })
    }

    /** Asks the page for its loaded videos. False when the page is not connected (the caller then falls back). */
    fun request(engineSession: EngineSession?): Boolean {
        val connected = engineSession != null && controller.portConnected(engineSession, PORT)
        Log.i("PlaylistBridge", "page connected: $connected")
        if (engineSession == null || !connected) return false
        controller.sendContentMessage(JSONObject().put("action", "collect"), engineSession, PORT)
        return true
    }

    private class PageMessages : MessageHandler {
        override fun onPortConnected(port: Port) {
            Log.i("PlaylistBridge", "page port connected")
        }

        override fun onPortMessage(message: Any, port: Port) {
            val json = message as? JSONObject ?: return
            if (json.optString("action") != "items") return
            val page = json.optString("page")
            val items = json.optJSONArray("items")
            val entries = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
                .filter { it.optString("url").startsWith("http") }
                .map { PlaylistEntry(it.optString("url"), it.optString("title")) }
            Handler(Looper.getMainLooper()).post {
                val act = activity.get() ?: return@post
                if (entries.isEmpty()) {
                    Toast.makeText(act, "No videos found. Scroll the list so they load, then try again.", Toast.LENGTH_LONG).show()
                } else {
                    showPicker(act, page, entries)
                }
            }
        }
    }
}
