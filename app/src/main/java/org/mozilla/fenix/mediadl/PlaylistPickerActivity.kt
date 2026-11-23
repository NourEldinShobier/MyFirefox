/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.fenix.theme.FirefoxTheme
import java.net.URL
import mozilla.components.ui.icons.R as iconsR

/** What the picker page shows: set by whoever opens it. */
object PlaylistSession {
    class Data(val pageUrl: String, val entries: List<PlaylistEntry>)

    @Volatile var current: Data? = null
}

/** Full-screen page listing the videos of a playlist with thumbnails, to tick the ones to download. */
class PlaylistPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = PlaylistSession.current ?: run { finish(); return }
        setContent { FirefoxTheme { PickerScreen(data) { finish() } } }
    }
}

private object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(300)

    suspend fun load(url: String): ImageBitmap? = cache[url] ?: withContext(Dispatchers.IO) {
        runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }?.also { cache.put(url, it) }
}

@Composable
private fun PickerScreen(data: PlaylistSession.Data, onClose: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(data.entries) }
    var loadingAll by remember { mutableStateOf(false) }
    var loadedAll by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val site = siteOf(data.pageUrl)
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    val count = entries.count { checked[it.url] == true }

    fun queue(kind: Kind) {
        val dl = MediaDownloads.get(context)
        val picked = entries.filter { checked[it.url] == true }
        picked.forEach { dl.enqueue(it.url, kind, site, quiet = true) }
        // One at a time with random pauses so the account is not flagged: about 30 s per song.
        val minutes = (picked.size * 30 + 59) / 60
        Toast.makeText(
            context,
            "Queued ${picked.size} ${kind.label} downloads ($site), about $minutes min with safety pauses",
            Toast.LENGTH_LONG,
        ).show()
        onClose()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(iconsR.drawable.mozac_ic_back_24), "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Download from playlist", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entries.size} videos · $count selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!loadedAll) {
                    // The page only lists what you have scrolled to; this asks YouTube for the whole playlist once.
                    TextButton(
                        enabled = !loadingAll,
                        onClick = {
                            // Preferred: the page scrolls itself and the picker reopens with the full list.
                            if (PlaylistBridge.requestAll()) {
                                Toast.makeText(context, "Scrolling the page to load every video…", Toast.LENGTH_SHORT).show()
                                onClose()
                                return@TextButton
                            }
                            loadingAll = true
                            MediaDownloads.get(context).loadPlaylist(
                                data.pageUrl,
                                onDone = { list -> entries = list; loadedAll = true; loadingAll = false; note = "Loaded all ${list.size} videos" },
                                onFail = {
                                    loadingAll = false
                                    note = "Could not load the full list: $it"
                                    android.util.Log.w("PlaylistPicker", "load all failed: $it")
                                },
                            )
                        },
                    ) { Text(if (loadingAll) "Loading…" else "Load all") }
                }
                TextButton(onClick = { val all = count < entries.size; for (e in entries) checked[e.url] = all }) {
                    Text(if (count == entries.size) "Clear" else "Select all")
                }
            }
            if (note.isNotEmpty()) {
                Text(note, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.url }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { checked[e.url] = checked[e.url] != true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked[e.url] == true, onCheckedChange = { checked[e.url] = it })
                        Thumbnail(e.url)
                        Text(
                            e.title,
                            Modifier.weight(1f).padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { queue(Kind.AUDIO) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                        Text("Audio, best")
                    }
                    Button(onClick = { queue(Kind.VOICE) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                        Text("Voice, small")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { queue(Kind.MP3) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                        Text("MP3")
                    }
                    OutlinedButton(onClick = { queue(Kind.MP4) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                        Text("Video 1080p")
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(videoUrl: String) {
    val id = Uri.parse(videoUrl).getQueryParameter("v")
    val bitmap by produceState<ImageBitmap?>(null, id) {
        // SoundCloud links have no thumbnail here, so those rows stay as a plain tile.
        value = id?.let { Thumbs.load("https://i.ytimg.com/vi/$it/mqdefault.jpg") }
    }
    Box(
        Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
