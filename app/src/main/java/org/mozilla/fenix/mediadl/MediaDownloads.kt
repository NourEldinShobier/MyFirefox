/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.mediadl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import kotlinx.coroutines.Job
import java.io.File

enum class Kind(val label: String) { MP3("MP3"), MP4("MP4") }
enum class Status { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELED }

/** Which per-site folder a page belongs to. */
fun siteOf(url: String): String {
    val host = Uri.parse(url).host?.lowercase() ?: return "Other"
    return when {
        host == "music.youtube.com" -> "YouTube Music"
        host.endsWith("youtube.com") || host == "youtu.be" -> "YouTube"
        host.endsWith("soundcloud.com") -> "SoundCloud"
        host.endsWith("instagram.com") -> "Instagram"
        else -> "Other"
    }
}

/** True for pages that hold one video or track (not a feed or home page). */
fun isDownloadable(url: String): Boolean {
    val u = Uri.parse(url)
    val host = u.host?.lowercase() ?: return false
    val path = u.path ?: ""
    return when {
        host.endsWith("youtube.com") -> path.startsWith("/watch") || path.startsWith("/shorts/") || path.startsWith("/live/")
        host == "youtu.be" -> path.length > 1
        host.endsWith("soundcloud.com") -> path.trim('/').split('/').size >= 2
        host.endsWith("instagram.com") ->
            Regex("^/(reel|reels|p|tv)/").containsMatchIn(path) || Regex("^/[^/]+/(reel|p)/").containsMatchIn(path)
        else -> false
    }
}

/** True for playlist, album and set pages whose songs can be picked one by one. */
fun isPlaylist(url: String): Boolean {
    val u = Uri.parse(url)
    val host = u.host?.lowercase() ?: return false
    val path = u.path ?: ""
    val list = u.getQueryParameter("list").orEmpty()
    return when {
        host.endsWith("youtube.com") ->
            (path == "/playlist" && list.isNotEmpty()) ||
                (path.startsWith("/watch") && list.isNotEmpty() && !list.startsWith("RD") && !list.startsWith("UL"))
        host.endsWith("soundcloud.com") -> "/sets/" in path
        else -> false
    }
}

class PlaylistEntry(val url: String, val title: String)

class DownloadJob(val id: String, val url: String, val site: String, val kind: Kind) {
    var title = ""
    var status = Status.QUEUED
    var percent = -1f
    var error = ""
    var savedWhere = ""
    var processId: String? = null
    var job: Job? = null
    var attempt = 0
    var firstError = ""
    val active get() = status == Status.QUEUED || status == Status.RUNNING || status == Status.SAVING
}

/**
 * Queue of downloads run one at a time through yt-dlp. Files are named after the video or song title
 * and saved into the folder chosen for that site (default Download/MyFirefox/<site>).
 */
class MediaDownloads private constructor(private val app: Context) {
    private val prefs: SharedPreferences = app.getSharedPreferences("media_downloads", Context.MODE_PRIVATE)
    val jobs = mutableListOf<DownloadJob>()
    private val main = Handler(Looper.getMainLooper())
    private var initState = 0 // 0 not started, 1 starting, 2 ready, -1 failed
    private var initError = ""
    private var counter = 0

    /** Netscape cookie file for logged-in downloads (written by the cookie bridge when present). */
    val cookieFile get() = File(app.filesDir, "cookies.txt")

    private fun toast(message: String) = main.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }

    fun enqueue(url: String, kind: Kind, site: String = siteOf(url), quiet: Boolean = false) {
        if (jobs.any { it.url == url && it.kind == kind && it.active }) { if (!quiet) toast("Already downloading this"); return }
        val job = DownloadJob("d${System.currentTimeMillis()}_${counter++}", url, site, kind)
        jobs.add(0, job)
        if (!quiet) toast("Download started (${job.site}, ${kind.label})")
        pump()
    }

    /** Lists every song of a playlist page (yt-dlp pages through lazily loaded lists itself). */
    fun loadPlaylist(url: String, onDone: (List<PlaylistEntry>) -> Unit, onFail: (String) -> Unit) {
        toast("Reading the playlist…")
        withEngine({
            val r = YoutubeDLRequest(url)
                .addOption("--flat-playlist").addOption("--yes-playlist")
                .addOption("--print", "%(url)s\t%(title)s")
            if (cookieFile.exists() && cookieFile.length() > 0) r.addOption("--cookies", cookieFile.absolutePath)
            YoutubeDL.download(
                request = r, pId = null,
                progressCallBack = { _, _, _ -> },
                onStartProcess = { },
                onEndProcess = { resp ->
                    val list = resp.out.lines().mapNotNull { line ->
                        val (u, t) = line.split('\t', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                        if (u.startsWith("http")) PlaylistEntry(u, t.ifBlank { u }) else null
                    }
                    main.post {
                        if (list.isEmpty()) onFail(resp.err.lines().lastOrNull { it.isNotBlank() } ?: "No songs found") else onDone(list)
                    }
                },
                onError = { e -> main.post { onFail(e.message ?: "could not read the playlist") } },
            )
        }, { onFail(it) })
    }

    fun cancel(job: DownloadJob) {
        job.processId?.let { YoutubeDL.destroyProcessById(it) }
        job.job?.cancel()
        if (job.active) job.status = Status.CANCELED
        pump()
    }

    val running get() = jobs.firstOrNull { it.status == Status.RUNNING || it.status == Status.SAVING }
    val queued get() = jobs.count { it.status == Status.QUEUED }

    // ---- folders ------------------------------------------------------------------------

    fun folderOf(site: String): Uri? = prefs.getString("dlTree_$site", null)?.let(Uri::parse)

    fun setFolder(site: String, uri: Uri?) {
        prefs.edit().apply { if (uri == null) remove("dlTree_$site") else putString("dlTree_$site", uri.toString()) }.apply()
    }

    fun folderLabel(site: String): String {
        val tree = folderOf(site) ?: return "Download/MyFirefox/$site"
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault("")
        return Uri.decode(id).substringAfter(':', id).ifBlank { "Chosen folder" }
    }

    // ---- running ------------------------------------------------------------------------

    private fun pump() {
        sync()
        if (running != null) return
        val next = jobs.lastOrNull { it.status == Status.QUEUED } ?: return
        next.status = Status.RUNNING
        sync()
        ensureReady(next)
    }

    private fun ensureReady(job: DownloadJob) =
        withEngine({ start(job) }, { fail(job, "Download engine failed to start: $it") })

    /** Runs [ready] once yt-dlp is unpacked and initialised, or [failed] with the reason. */
    private fun withEngine(ready: () -> Unit, failed: (String) -> Unit) {
        when (initState) {
            2 -> ready()
            -1 -> failed(initError)
            1 -> main.postDelayed({ withEngine(ready, failed) }, 500)
            else -> {
                initState = 1
                YoutubeDL.init(
                    app, true, false,
                    { _ -> initState = 2; main.post { ready() } },
                    { e ->
                        initState = -1
                        initError = e.message ?: "unknown"
                        main.post { failed(initError) }
                    },
                )
            }
        }
    }

    /**
     * Writes the browser's own cookies for the supported sites to [cookieFile] so yt-dlp is treated as the
     * logged-in browser (YouTube otherwise answers "confirm you're not a bot").
     * shortcut: reads Gecko's cookies.sqlite on the main thread, upgrade to a background thread if it gets slow.
     */
    private fun exportCookies() {
        val profile = File(app.filesDir, "mozilla").listFiles()
            ?.firstOrNull { File(it, "cookies.sqlite").exists() } ?: return
        val tmp = File(app.cacheDir, "ckdb").apply { deleteRecursively(); mkdirs() }
        listOf("cookies.sqlite", "cookies.sqlite-wal", "cookies.sqlite-shm").forEach { n ->
            File(profile, n).takeIf { it.exists() }?.copyTo(File(tmp, n), overwrite = true)
        }
        val sites = listOf("youtube.com", "google.com", "soundcloud.com", "instagram.com")
        val where = sites.joinToString(" OR ") { "host LIKE '%$it'" }
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            File(tmp, "cookies.sqlite").absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        db.use {
            val out = StringBuilder("# Netscape HTTP Cookie File\n")
            it.rawQuery("SELECT host, path, isSecure, expiry, name, value FROM moz_cookies WHERE $where", null).use { c ->
                while (c.moveToNext()) {
                    val host = c.getString(0)
                    out.append(host).append('\t').append(if (host.startsWith(".")) "TRUE" else "FALSE").append('\t')
                        .append(c.getString(1)).append('\t').append(if (c.getInt(2) != 0) "TRUE" else "FALSE").append('\t')
                        .append(c.getLong(3)).append('\t').append(c.getString(4)).append('\t').append(c.getString(5)).append('\n')
                }
            }
            cookieFile.writeText(out.toString())
        }
        tmp.deleteRecursively()
    }

    private fun start(job: DownloadJob) {
        runCatching { exportCookies() }
        val dir =File(app.cacheDir, "dl/${job.id}").apply { deleteRecursively(); mkdirs() }
        val r = YoutubeDLRequest(job.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--embed-metadata")
            .addOption("-o", File(dir, "%(title).150B.%(ext)s").absolutePath)
        when (job.kind) {
            Kind.MP3 -> r.addOption("-f", "bestaudio/best").addOption("-x").addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0").addOption("--embed-thumbnail").addOption("--convert-thumbnails", "jpg")
            Kind.MP4 -> r.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b").addOption("--merge-output-format", "mp4")
        }
        // YouTube: first try clients that need neither cookies nor a JavaScript challenge (the engine has no JS runtime),
        // then the logged-in cookies with the default clients.
        val youtube = job.site.startsWith("YouTube")
        if (youtube) {
            // QuickJS (built in CI) solves YouTube's "n" challenge; the solver script is fetched once from GitHub.
            val qjs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")
            if (qjs.exists()) r.addOption("--js-runtimes", "quickjs:${qjs.absolutePath}")
            r.addOption("--remote-components", "ejs:github")
        }
        if (youtube && job.attempt == 0) r.addOption("--extractor-args", "youtube:player_client=android_vr,android")
        else if (cookieFile.exists() && cookieFile.length() > 0) r.addOption("--cookies", cookieFile.absolutePath)
        // Second try for YouTube uses clients that do not need a JavaScript challenge solved.

        job.job = YoutubeDL.download(
            request = r,
            pId = null,
            progressCallBack = { pct, _, line ->
                main.post {
                    if (pct >= 0f) job.percent = pct
                    titleFrom(line)?.let { if (job.title.isBlank() || job.kind == Kind.MP3) job.title = it }
                }
            },
            onStartProcess = { id -> job.processId = id },
            onEndProcess = { resp ->
                main.post {
                    if (job.status == Status.CANCELED) { dir.deleteRecursively(); pump(); return@post }
                    if (resp.exitCode == 0) {
                        finish(job, dir)
                    } else {
                        retryOrFail(job, dir, resp.err.ifBlank { resp.out }.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp exited with ${resp.exitCode}")
                    }
                }
            },
            onError = { e ->
                main.post {
                    if (job.status == Status.CANCELED || e is YoutubeDL.CanceledException) {
                        job.status = Status.CANCELED; dir.deleteRecursively(); pump()
                    } else {
                        retryOrFail(job, dir, e.message ?: "download failed")
                    }
                }
            },
        )
    }

    private fun retryOrFail(job: DownloadJob, dir: File, message: String) {
        if (job.site.startsWith("YouTube") && job.attempt == 0) {
            job.attempt = 1
            job.firstError = message
            start(job)
        } else {
            dir.deleteRecursively()
            fail(job, message)
        }
    }

    private fun titleFrom(line: String): String? {
        val m = Regex("""Destination: (.+)$""").find(line) ?: return null
        val name = File(m.groupValues[1].trim()).name
        return name.substringBeforeLast('.').replace(Regex("""\.f\d+$"""), "")
    }

    private fun finish(job: DownloadJob, dir: File) {
        job.status = Status.SAVING
        sync()
        val media = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }?.maxByOrNull { it.length() }
        if (media == null) { dir.deleteRecursively(); fail(job, "No file was produced"); return }
        Thread {
            val saved = runCatching { saveTo(job.site, media) }
            main.post {
                dir.deleteRecursively()
                saved.onSuccess { where ->
                    job.savedWhere = where
                    job.title = media.nameWithoutExtension
                    job.status = Status.DONE; job.percent = 100f
                    toast("Saved ${media.nameWithoutExtension} to $where")
                    notifyDone(job)
                }.onFailure { fail(job, "Could not save the file: ${it.message}") }
                pump()
            }
        }.start()
    }

    private fun fail(job: DownloadJob, message: String) {
        job.status = Status.FAILED
        job.error = if (job.firstError.isBlank()) message else "1st try: ${job.firstError.take(160)} | 2nd try: ${message.take(160)}"
        toast("Download failed: $message")
        notifyDone(job)
        pump()
    }

    // ---- saving -------------------------------------------------------------------------

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "mp4" -> "video/mp4"
        "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; else -> "application/octet-stream"
    }

    /** Copies [file] into the site's chosen folder, or Download/MyFirefox/<site> when none was chosen. */
    private fun saveTo(site: String, file: File): String {
        val resolver = app.contentResolver
        val tree = folderOf(site)
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(file), file.name)
                ?: error("the chosen folder is not writable")
            resolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            return folderLabel(site)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MyFirefox/$site")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the file")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return "Download/MyFirefox/$site"
    }

    // ---- foreground service + completion notifications ----------------------------------

    private fun sync() {
        val i = Intent(app, MediaDownloadService::class.java)
        if (running != null || queued > 0) {
            try { app.startForegroundService(i) } catch (_: Exception) { /* not allowed from the background */ }
        } else {
            app.stopService(i)
        }
    }

    private fun launchApp() =
        PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)

    private fun notifyDone(job: DownloadJob) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        val ok = job.status == Status.DONE
        val n = Notification.Builder(app, DONE_CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "Saved ${job.title}" else "Download failed")
            .setContentText(if (ok) job.savedWhere else job.error)
            .setContentIntent(launchApp()).setAutoCancel(true).build()
        nm.notify(job.id.hashCode(), n)
    }

    fun progressNotification(service: Context): Notification {
        val j = running
        val more = queued
        val b = Notification.Builder(service, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(j?.title?.ifBlank { null } ?: "Downloading…")
            .setContentText(
                buildString {
                    append(j?.let { "${it.site} · ${it.kind.label}" } ?: "Starting")
                    if (more > 0) append(" · $more waiting")
                },
            )
            .setContentIntent(launchApp()).setOngoing(true).setOnlyAlertOnce(true)
        val p = j?.percent ?: -1f
        if (j?.status == Status.RUNNING && p >= 0f) b.setProgress(100, p.toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private val MEDIA_EXT = setOf("mp3", "m4a", "opus", "ogg", "mp4", "webm", "mkv", "aac", "flac", "wav")
        const val DONE_CHANNEL = "media_downloads_done"
        const val PROGRESS_CHANNEL = "media_downloads_progress"

        @Volatile private var instance: MediaDownloads? = null

        fun get(context: Context): MediaDownloads =
            instance ?: synchronized(this) { instance ?: MediaDownloads(context.applicationContext).also { instance = it } }
    }
}

/** Keeps the process alive and shows overall progress while downloads run. */
class MediaDownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val dl get() = MediaDownloads.get(this)
    private val tick = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, dl.progressNotification(this@MediaDownloadService))
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MediaDownloads.PROGRESS_CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(ID, dl.progressNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object { private const val ID = 4242 }
}
