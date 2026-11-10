package com.superiorx.editor

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var editorPanel: View
    private lateinit var emptyState: TextView
    private lateinit var clipName: TextView
    private lateinit var timeLabel: TextView
    private lateinit var trimSummary: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var setStartButton: Button
    private lateinit var setEndButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var muteSwitch: Switch
    private lateinit var resolutionSpinner: Spinner
    private lateinit var recentProject: TextView

    private var selectedUri: Uri? = null
    private var durationMs = 0L
    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private var transformer: Transformer? = null
    private var isSeeking = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateTimeline = object : Runnable {
        override fun run() {
            if (player.currentMediaItem != null && !isSeeking) updatePlaybackPosition()
            uiHandler.postDelayed(this, 250)
        }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            loadVideo(uri, displayName(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    val actualDuration = player.duration
                    if (actualDuration > 0) {
                        durationMs = actualDuration
                        trimEndMs = actualDuration
                        seekBar.max = actualDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        updateTrimSummary()
                        updatePlaybackPosition()
                    }
                }
            }
        })
        findViewById<Button>(R.id.importButton).setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        setStartButton.setOnClickListener { setTrimStart() }
        setEndButton.setOnClickListener { setTrimEnd() }
        exportButton.setOnClickListener { exportVideo() }
        findViewById<TextView>(R.id.settingsButton).setOnClickListener {
            Toast.makeText(this, "Exports use H.264 and save to your gallery. Choose 720p, 1080p or 4K below.", Toast.LENGTH_LONG).show()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeLabel.text = "${formatTime(progress.toLong())} / ${formatTime(durationMs)}"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                player.seekTo((bar?.progress ?: 0).toLong())
                isSeeking = false
                updatePlaybackPosition()
            }
        })
        recentProject.setOnClickListener {
            val uri = getSharedPreferences("projects", MODE_PRIVATE).getString("last_uri", null)?.let(Uri::parse)
            if (uri != null) loadVideo(uri, getSharedPreferences("projects", MODE_PRIVATE).getString("last_name", "Recent project") ?: "Recent project")
            else Toast.makeText(this, "Import a video to start your first project.", Toast.LENGTH_SHORT).show()
        }
        restoreRecentProject()
        uiHandler.post(updateTimeline)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        editorPanel = findViewById(R.id.editorPanel)
        emptyState = findViewById(R.id.emptyState)
        clipName = findViewById(R.id.clipName)
        timeLabel = findViewById(R.id.timeLabel)
        trimSummary = findViewById(R.id.trimSummary)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.exportProgress)
        exportButton = findViewById(R.id.exportButton)
        setStartButton = findViewById(R.id.setStartButton)
        setEndButton = findViewById(R.id.setEndButton)
        seekBar = findViewById(R.id.timelineSeekBar)
        muteSwitch = findViewById(R.id.muteSwitch)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        recentProject = findViewById(R.id.recentProject)
    }

    private fun loadVideo(uri: Uri, name: String) {
        selectedUri = uri
        trimStartMs = 0L
        durationMs = 0L
        trimEndMs = 0L
        clipName.text = name
        emptyState.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        editorPanel.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        getSharedPreferences("projects", MODE_PRIVATE).edit()
            .putString("last_uri", uri.toString()).putString("last_name", name).apply()
        recentProject.text = "▶   $name\nTap to reopen this project"
        updateTrimSummary()
    }

    private fun setTrimStart() {
        val position = player.currentPosition.coerceAtLeast(0L)
        if (durationMs <= 0L || position >= trimEndMs) {
            toast("Move the playhead before the current trim out point.")
            return
        }
        trimStartMs = position
        updateTrimSummary()
    }

    private fun setTrimEnd() {
        val position = player.currentPosition.coerceAtLeast(0L)
        if (durationMs <= 0L || position <= trimStartMs) {
            toast("Move the playhead after the current trim in point.")
            return
        }
        trimEndMs = position
        updateTrimSummary()
    }

    private fun updateTrimSummary() {
        if (durationMs <= 0) {
            setStartButton.text = "Set in · 00:00"
            setEndButton.text = "Set out · 00:00"
            trimSummary.text = "Preparing video…"
            return
        }
        setStartButton.text = "Set in · ${formatTime(trimStartMs)}"
        setEndButton.text = "Set out · ${formatTime(trimEndMs)}"
        trimSummary.text = "Selected ${formatTime(trimStartMs)} – ${formatTime(trimEndMs)}  ·  ${formatTime((trimEndMs - trimStartMs).coerceAtLeast(0))}"
    }

    private fun updatePlaybackPosition() {
        if (durationMs <= 0L) return
        val position = player.currentPosition.coerceIn(0L, durationMs)
        seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timeLabel.text = "${formatTime(position)} / ${formatTime(durationMs)}"
    }

    private fun exportVideo() {
        val uri = selectedUri ?: run { toast("Choose a video first."); return }
        if (durationMs <= 0L || trimEndMs <= trimStartMs) { toast("The selected trim range is not valid."); return }
        val height = when (resolutionSpinner.selectedItemPosition) { 0 -> 720; 1 -> 1080; else -> 2160 }
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs)
                .setEndPositionMs(trimEndMs)
                .build()
        ).build()
        val effects = Effects(emptyList(), listOf(Presentation.createForHeight(height)))
        val editedItem = EditedMediaItem.Builder(item)
            .setRemoveAudio(muteSwitch.isChecked)
            .setEffects(effects)
            .build()
        val output = File(cacheDir, "superiorx_${System.currentTimeMillis()}.mp4")
        setExporting(true, "Preparing export…")
        try {
            transformer = Transformer.Builder(this)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        try {
                            val saved = saveToGallery(output)
                            output.delete()
                            setExporting(false, "Saved to gallery: ${saved.second}")
                            toast("Export complete — saved to ${saved.second}")
                        } catch (error: Exception) {
                            output.delete()
                            setExporting(false, "Could not save export: ${error.message ?: "unknown error"}")
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        output.delete()
                        setExporting(false, "Export failed: ${exportException.message ?: "unsupported video format"}")
                    }
                })
                .build()
            transformer?.start(editedItem, output.absolutePath)
        } catch (error: Exception) {
            output.delete()
            setExporting(false, "Could not start export: ${error.message ?: "unknown error"}")
        }
    }

    private fun saveToGallery(source: File): Pair<Uri, String> {
        val resolver = contentResolver
        val name = "SuperiorX_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Superior X Editor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Gallery did not accept the exported video")
        try {
            resolver.openOutputStream(uri)?.use { output -> FileInputStream(source).use { input -> input.copyTo(output) } }
                ?: throw IOException("Unable to open gallery output")
            val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return uri to "Movies/Superior X Editor/$name"
    }

    private fun setExporting(exporting: Boolean, message: String) {
        runOnUiThread {
            exportButton.isEnabled = !exporting
            progressBar.visibility = if (exporting) View.VISIBLE else View.GONE
            statusText.visibility = View.VISIBLE
            statusText.text = message
            if (!exporting) transformer = null
        }
    }

    private fun displayName(uri: Uri): String {
        val cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) return it.getString(0) ?: "Selected video" }
        return "Selected video"
    }

    private fun restoreRecentProject() {
        val prefs = getSharedPreferences("projects", MODE_PRIVATE)
        val name = prefs.getString("last_name", null)
        if (name != null) recentProject.text = "▶   $name\nTap to reopen this project"
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        uiHandler.removeCallbacks(updateTimeline)
        transformer?.cancel()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
