package com.youtubedownloader.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Type(val label: String) { VIDEO("Video"), AUDIO("Audio") }

    enum class Kind { INFO, DIM, WARN, ERROR, OK, PROGRESS }

    data class LogLine(val text: String, val kind: Kind)

    data class Msg(val title: String, val text: String)

    companion object {
        val RESOLUTIONS = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")

        /** Matches yt-dlp progress lines such as "[download]  45.3% of ..." (same regex as the desktop app). */
        private val PROGRESS_RE = Regex("""\[download]\s+(\d+(?:\.\d+)?)%""")

        /** yt-dlp lines that start post-processing (progress becomes indeterminate). */
        private val POST_PREFIXES = listOf("[Merger]", "[ExtractAudio]", "[VideoConvertor]", "[Fixup")

        private val TEMP_EXTENSIONS = listOf("part", "ytdl", "temp")
        private val INTERMEDIATE = Regex(""".*\.f\d+\.[A-Za-z0-9]+$""")

        private const val PID = "ytdl"
    }

    private val ctx: Application = app

    // ---- UI state ----
    val url = MutableStateFlow("")
    val resolutionIndex = MutableStateFlow(0)
    val progress = MutableStateFlow(0f)
    val indeterminate = MutableStateFlow(false)
    val logLines = MutableStateFlow<List<LogLine>>(emptyList())
    val busy = MutableStateFlow(false)
    val downloading = MutableStateFlow(false)
    val message = MutableStateFlow<Msg?>(null)

    private var job: Job? = null

    @Volatile private var cancelled = false
    @Volatile private var notifActive = false
    private var lastNotifPercent = -1

    // ---- helpers ----
    private fun log(text: String, kind: Kind = Kind.INFO) {
        logLines.update { old ->
            // A new progress line replaces the previous one (like a terminal's carriage return).
            val base = if (kind == Kind.PROGRESS && old.lastOrNull()?.kind == Kind.PROGRESS) old.dropLast(1) else old
            val n = base + LogLine(text, kind)
            if (n.size > 600) n.takeLast(500) else n
        }
    }

    private fun show(title: String, text: String) { message.value = Msg(title, text) }
    fun dismissMessage() { message.value = null }

    fun setUrl(s: String) { url.value = s }
    fun setResolution(i: Int) { resolutionIndex.value = i }

    private fun notifyProgress(text: String, percent: Int?) {
        if (notifActive) Notifications.updateProgress(ctx, text, percent)
    }

    private fun launchJob(block: suspend () -> Unit) {
        busy.value = true
        job = viewModelScope.launch {
            try {
                if (!App.ready.isCompleted) log("Initialising yt-dlp, please wait...", Kind.DIM)
                val err = App.ready.await()
                if (err != null) { show("Error", err); return@launch }
                block()
            } finally {
                busy.value = false
                downloading.value = false
            }
        }
    }

    // ---- download / cancel ----
    fun startDownload(type: Type) {
        if (busy.value) return

        val raw = url.value.trim()
        if (raw.isEmpty()) { show("Warning", "Please enter a YouTube URL"); return }
        if (!Utils.isYoutubeUrl(raw)) { show("Warning", "Please enter a valid YouTube URL"); return }
        val target = Utils.findYoutubeUrl(raw) ?: raw
        val resolution = RESOLUTIONS[resolutionIndex.value].removeSuffix("p")

        cancelled = false
        downloading.value = true
        logLines.value = emptyList()
        progress.value = 0f
        indeterminate.value = true   // indeterminate until we see a percentage
        lastNotifPercent = -1
        launchJob { runDownload(type, target, resolution) }
    }

    fun cancel() {
        if (!downloading.value) return
        cancelled = true
        log("\n--- Cancelling... Please wait ---", Kind.WARN)
        try { YoutubeDL.getInstance().destroyProcessById(PID) } catch (_: Exception) {}
        job?.cancel()
    }

    private suspend fun runDownload(type: Type, target: String, resolution: String) {
        val work = File(ctx.cacheDir, "work_${UUID.randomUUID()}").apply { mkdirs() }
        notifActive = true
        DownloadService.start(ctx)
        val label = type.label
        var success = false
        try {
            log("Starting ${label.lowercase()} download...")

            val req = YoutubeDLRequest(target)
            req.addOption("--newline")      // one progress update per line
            req.addOption("--no-playlist")  // a video link with &list=... must not pull in a whole playlist

            when (type) {
                Type.VIDEO -> {
                    val format = "bestvideo[height<=$resolution]+bestaudio/best[height<=$resolution]"
                    req.addOption("-o", File(work, "%(title).100s_${resolution}p.%(ext)s").absolutePath)
                    req.addOption("--format", format)
                    req.addOption("--merge-output-format", "mp4")
                    log("Format: $format", Kind.DIM)
                }
                Type.AUDIO -> {
                    // MP3, 192 kbps, 44.1 kHz
                    req.addOption("-o", File(work, "%(title).100s.%(ext)s").absolutePath)
                    req.addOption("--extract-audio")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "192K")
                    req.addOption("--postprocessor-args", "ExtractAudio:-ar 44100")
                    log("Format: MP3, 192 kbps, 44.1 kHz", Kind.DIM)
                }
            }

            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(req, PID) { _, _, line ->
                    line.split('\r', '\n').forEach { processLine(it) }
                }
            }

            progress.value = 1f
            indeterminate.value = false
            val saved = exportOutputs(work)
            if (saved == 0) error("yt-dlp finished but produced no output file.")
            success = true

            val text = "$label download completed successfully! ($saved file(s) saved to Downloads/${MediaExporter.FOLDER})"
            log("$label download completed successfully!", Kind.OK)
            show("Success", text)
            Notifications.result(ctx, "Download complete", text)
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } catch (e: Exception) {
            if (cancelled) {
                onCancelled()
            } else {
                val detail = e.message ?: "unknown error"
                log("ERROR: $detail", Kind.ERROR)
                log("$label process failed", Kind.ERROR)
                var text = "$label download failed. See the log for details."
                if (looksLikeYoutubeBreakage(detail)) {
                    text += "\n\nYouTube changes often. Tap \"Update yt-dlp\" and try again."
                }
                show("Error", text)
                Notifications.result(ctx, "Download failed", "$label download failed.")
            }
        } finally {
            notifActive = false
            DownloadService.stop(ctx)
            work.deleteRecursively()
            indeterminate.value = false
            progress.value = if (success) 1f else 0f
        }
    }

    private fun looksLikeYoutubeBreakage(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf("403", "sign in", "javascript", "not available", "nsig", "unable to extract", "po token", "bot")
            .any { it in m }
    }

    private fun onCancelled() {
        log("\n--- Download cancelled ---", Kind.WARN)
        show("Cancelled", "The download was cancelled.")
    }

    /** Port of process_line() from the desktop app. */
    private fun processLine(raw: String) {
        val line = raw.trimEnd()
        if (line.isBlank()) return

        val match = PROGRESS_RE.find(line)
        if (match != null) {
            val pct = match.groupValues[1].toFloat()
            indeterminate.value = false
            progress.value = (pct / 100f).coerceIn(0f, 1f)
            if (pct.toInt() != lastNotifPercent) {
                lastNotifPercent = pct.toInt()
                notifyProgress("Downloading... ${pct.toInt()}%", pct.toInt())
            }
            log(line, Kind.PROGRESS)
            return
        }

        if (POST_PREFIXES.any { line.startsWith(it) }) {
            indeterminate.value = true   // post-processing: indeterminate
            lastNotifPercent = -1
            notifyProgress("Processing...", null)
        }

        when {
            line.startsWith("ERROR") -> log(line, Kind.ERROR)
            line.startsWith("WARNING") -> log(line, Kind.WARN)
            else -> log(line)
        }
    }

    /** Moves finished files from the work dir into Downloads/YouTubeDownloader. Never overwrites (Name, Name_1, ...). */
    private fun exportOutputs(work: File): Int {
        var n = 0
        work.listFiles()
            ?.filter { f ->
                f.isFile &&
                    TEMP_EXTENSIONS.none { f.name.endsWith(".$it") } &&
                    !INTERMEDIATE.matches(f.name)
            }
            ?.forEach { f ->
                val finalName = MediaExporter.uniqueName(ctx, f.nameWithoutExtension, f.extension)
                if (MediaExporter.export(ctx, f, finalName)) {
                    log("Saved: $finalName", Kind.OK)
                    n++
                } else {
                    log("ERROR: could not save $finalName", Kind.ERROR)
                }
                f.delete()
            }
        return n
    }

    // ---- yt-dlp update (YouTube extraction breaks often) ----
    fun updateYtDlp() {
        if (busy.value) return
        logLines.value = emptyList()
        log("--- Updating yt-dlp... ---")
        launchJob {
            try {
                val status = withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(ctx) }
                log("Update result: $status", Kind.OK)
            } catch (e: Exception) {
                log("ERROR: ${e.message}", Kind.ERROR)
            }
        }
    }
}
