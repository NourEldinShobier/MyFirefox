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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateListOf
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
    val entries = data.entries
    val site = siteOf(data.pageUrl)
    val checked = remember { mutableStateListOf<Boolean>().apply { repeat(entries.size) { add(false) } } }
    val count = checked.count { it }

    fun queue(kind: Kind) {
        val dl = MediaDownloads.get(context)
        val picked = entries.filterIndexed { i, _ -> checked[i] }
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
                TextButton(onClick = { val all = count < entries.size; for (i in entries.indices) checked[i] = all }) {
                    Text(if (count == entries.size) "Clear" else "Select all")
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(entries) { i, e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
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
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { queue(Kind.MP3) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                    Text("Audio (MP3)")
                }
                OutlinedButton(onClick = { queue(Kind.MP4) }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                    Text("Video (MP4)")
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
