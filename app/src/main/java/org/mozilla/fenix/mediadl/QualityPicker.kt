/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.theme.FirefoxTheme
import mozilla.components.ui.icons.R as iconsR

/** What the quality sheet shows: set by whoever opens it. */
object QualitySession {
    class Data(val pageUrl: String, val info: MediaInfo)

    @Volatile var current: Data? = null
}

/** Reads the qualities of the page's video (one request), then opens the sheet. */
fun pickQuality(activity: Activity, pageUrl: String) {
    MediaDownloads.get(activity).loadFormats(
        pageUrl,
        onDone = { info ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                QualitySession.current = QualitySession.Data(pageUrl, info)
                activity.startActivity(Intent(activity, QualityActivity::class.java))
            }
        },
        onFail = {
            Log.w("QualityPicker", "format read failed: $it")
            Toast.makeText(activity, "Could not read the qualities: $it", Toast.LENGTH_LONG).show()
        },
    )
}

/** Full-screen sheet listing the video heights and audio presets of one video with estimated sizes. */
class QualityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = QualitySession.current ?: run { finish(); return }
        setContent { FirefoxTheme { QualityScreen(data) { finish() } } }
    }
}

private fun sizeText(bytes: Long) = when {
    bytes <= 0 -> "size unknown"
    bytes >= 1_000_000_000 -> "about %.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "about %.0f MB".format(bytes / 1e6)
    else -> "about %.0f KB".format(bytes / 1e3)
}

@Composable
private fun QualityScreen(data: QualitySession.Data, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dl = MediaDownloads.get(context)
    val youtube = siteOf(data.pageUrl).startsWith("YouTube")
    var info by remember { mutableStateOf(data.info) }
    var login by remember { mutableStateOf(dl.useLogin) }
    var loading by remember { mutableStateOf(false) }

    // Switching the login re-reads the list once, so the rows match what the download will get.
    fun setLogin(on: Boolean) {
        login = on; dl.useLogin = on; loading = true
        dl.loadFormats(
            data.pageUrl,
            onDone = { info = it; loading = false },
            onFail = { loading = false; Toast.makeText(context, "Could not read the qualities: $it", Toast.LENGTH_LONG).show() },
        )
    }

    fun pick(o: QualityOption) {
        MediaDownloads.get(context).enqueue(data.pageUrl, o.kind, format = o.format, tag = o.label.substringBefore(" ("), attempt = info.attempt)
        onClose()
    }

    @Composable
    fun OptionRow(o: QualityOption) {
        Column(
            Modifier.fillMaxWidth().clickable { pick(o) }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(o.label, style = MaterialTheme.typography.bodyLarge)
            Text(sizeText(o.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(painterResource(iconsR.drawable.mozac_ic_back_24), "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Choose quality", style = MaterialTheme.typography.titleMedium)
                    Text(info.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            if (youtube) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use my YouTube login for HD video", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (login) "On: HD video. YouTube sees your account on each download."
                            else if (info.maxHeight in 1..360) "Off: no login, so video is limited to 360p."
                            else "Off: no login. Turn on for higher video qualities.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = login, enabled = !loading, onCheckedChange = { setLogin(it) })
                }
                HorizontalDivider()
            }
            if (loading) Text("Reading the qualities…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.Top) {
                if (info.video.isNotEmpty()) {
                    item { Text("Video", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    info.video.forEach { o -> item { OptionRow(o) } }
                }
                item { Text("Audio only", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                info.audio.forEach { o -> item { OptionRow(o) } }
            }
        }
    }
}
