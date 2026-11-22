/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Reads the playlist on [pageUrl] and lets the user tick the songs to download. */
fun pickPlaylistSongs(activity: Activity, pageUrl: String) {
    val dl = MediaDownloads.get(activity)
    dl.loadPlaylist(
        pageUrl,
        onDone = { entries -> showPicker(activity, dl, pageUrl, entries) },
        onFail = { Toast.makeText(activity, "Could not read the playlist: $it", Toast.LENGTH_LONG).show() },
    )
}

private fun showPicker(activity: Activity, dl: MediaDownloads, pageUrl: String, entries: List<PlaylistEntry>) {
    if (activity.isFinishing || activity.isDestroyed) return
    val labels = arrayOf("Select all") + entries.map { it.title }.toTypedArray()
    val checked = BooleanArray(labels.size)
    val site = siteOf(pageUrl)

    fun queue(kind: Kind) {
        val picked = entries.filterIndexed { i, _ -> checked[i + 1] }
        picked.forEach { dl.enqueue(it.url, kind, site, quiet = true) }
        // Downloads run one at a time with short random pauses so the account is not flagged; about 30 s per song.
        val minutes = (picked.size * 30 + 59) / 60
        Toast.makeText(activity, "Queued ${picked.size} ${kind.label} downloads ($site), about $minutes min with safety pauses", Toast.LENGTH_LONG).show()
    }

    AlertDialog.Builder(activity)
        .setTitle("${entries.size} songs")
        .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
            checked[which] = isChecked
            if (which == 0) {
                val list = (dialog as AlertDialog).listView
                for (i in 1 until labels.size) { checked[i] = isChecked; list.setItemChecked(i, isChecked) }
            }
        }
        .setPositiveButton("Audio (MP3)") { _, _ -> queue(Kind.MP3) }
        .setNeutralButton("Video (MP4)") { _, _ -> queue(Kind.MP4) }
        .setNegativeButton("Cancel", null)
        .show()
}
