/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** Reads the whole playlist with yt-dlp and opens the picker page. Used when the page can't list its own videos. */
fun pickPlaylistSongs(activity: Activity, pageUrl: String) {
    MediaDownloads.get(activity).loadPlaylist(
        pageUrl,
        onDone = { entries -> showPicker(activity, pageUrl, entries) },
        onFail = {
            Log.w("PlaylistPicker", "playlist read failed: $it")
            Toast.makeText(activity, "Could not read the playlist: $it", Toast.LENGTH_LONG).show()
        },
    )
}

/** Opens the in-app page that lists [entries] with thumbnails. */
fun showPicker(activity: Activity, pageUrl: String, entries: List<PlaylistEntry>) {
    if (activity.isFinishing || activity.isDestroyed) return
    PlaylistSession.current = PlaylistSession.Data(pageUrl, entries)
    activity.startActivity(Intent(activity, PlaylistPickerActivity::class.java))
}
