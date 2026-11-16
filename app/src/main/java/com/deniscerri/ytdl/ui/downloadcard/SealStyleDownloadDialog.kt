package com.deniscerri.ytdl.ui.downloadcard

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.util.Extensions.loadThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Seal-style share popup: a full screen "Format selection" page with the video info on top,
 * one suggested format, then the list of video formats and audio formats to pick from.
 *
 * For anything it can't handle (playlists, command mode, nothing fetched) it hands over to the
 * classic download card, which is still available through the "More options" button.
 */
class SealStyleDownloadDialog : DialogFragment() {
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel

    private var result by mutableStateOf<ResultItem?>(null)
    private var loading by mutableStateOf(true)
    private var fetched by mutableStateOf(false)
    private var enqueuing by mutableStateOf(false)
    private var sawLoading = false
    private var switchingToClassic = false

    /** What the user picked: a video (+ optional audio to merge) or an audio-only format. */
    private data class Choice(val video: Format?, val audio: Format?, val audioOnly: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_NoActionBar)
        isCancelable = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Content() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]

        val base = downloadCardViewModel.resultItem
        if (base == null || base.url.isBlank()) {
            dismissAllowingStateLoss()
            return
        }
        result = base

        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest { updating ->
                loading = updating
                if (updating) {
                    sawLoading = true
                } else if (sawLoading) {
                    fetched = true
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { list ->
                if (list == null) return@collectLatest
                if (list.size == 1 && list[0] != null) {
                    val res = list[0]!!
                    result = res
                    downloadCardViewModel.setResultItem(res)
                    fetched = true
                    resultViewModel.updateResultData.emit(null)
                } else if (list.size > 1) {
                    // playlist: the classic card knows how to handle it
                    resultViewModel.updateResultData.emit(null)
                    openClassic()
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (!resultViewModel.updatingData.value) {
                resultViewModel.updateItemData(base)
            }
        }
    }

    private fun openClassic() {
        if (switchingToClassic) return
        switchingToClassic = true
        val act = activity as? ShareActivity
        dismissAllowingStateLoss()
        act?.openClassicDialog()
    }

    private fun startDownload(choice: Choice?) {
        val res = result ?: return
        if (enqueuing) return
        enqueuing = true
        val appContext = requireContext().applicationContext
        // own scope: the popup is closed (and its scope cancelled) while the download is still being queued
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val queued = runCatching {
                val type = if (choice?.audioOnly == true) DownloadType.audio else DownloadType.video
                val item = downloadViewModel.createDownloadItemFromResult(result = res, givenType = type)
                if (choice != null) {
                    if (choice.audioOnly) {
                        choice.audio?.let { item.format = it }
                    } else {
                        choice.video?.let { item.format = it }
                        item.videoPreferences.audioFormatIDs.clear()
                        choice.audio?.let { item.videoPreferences.audioFormatIDs.add(it.format_id) }
                    }
                }
                downloadViewModel.queueDownloads(listOf(item))
            }.getOrNull()

            withContext(Dispatchers.Main) {
                val text = when {
                    queued == null -> "Could not start the download"
                    queued.duplicateDownloadIDs.isNotEmpty() -> "Already downloaded or in the queue"
                    else -> "Download started"
                }
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
                runCatching { dismissAllowingStateLoss() }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!switchingToClassic) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                resultViewModel.cancelUpdateItemData()
            }
            activity?.finish()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------------

    @Composable
    private fun Content() {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }
        MaterialTheme(colorScheme = scheme) {
            Screen()
        }
    }

    @Composable
    private fun Screen() {
        val res = result
        val formats = remember(res?.formats) {
            val list = res?.formats ?: emptyList()
            if (list.any { it.format_note.contains("ytdlnisgeneric") }) emptyList() else list
        }
        val videoList = remember(formats) {
            formats.filter { it.hasVideo() }
                .sortedWith(compareByDescending<Format> { it.height ?: 0 }.thenByDescending { it.tbr?.toDoubleOrNull() ?: 0.0 })
        }
        val audioList = remember(formats) {
            formats.filter { it.hasAudio() && !it.hasVideo() }
                .sortedWith(compareByDescending<Format> { it.tbr?.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.filesize })
        }
        val bestAudio = audioList.firstOrNull()
        val suggested: Choice? = remember(videoList, audioList) {
            val v = videoList.firstOrNull()
            if (v == null) null else Choice(v, if (v.hasAudio()) null else audioList.firstOrNull(), false)
        }

        var selected by remember { mutableStateOf<Choice?>(null) }
        val showLoading = loading || !fetched
        val canDownload = !enqueuing && !showLoading

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { dismissAllowingStateLoss() }) { Text("✕", fontSize = 20.sp) }
                    Text(
                        "Format selection",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    TextButton(onClick = { openClassic() }) { Text("More options") }
                    TextButton(
                        enabled = canDownload,
                        onClick = { startDownload(selected ?: suggested) }
                    ) { Text("Download", fontWeight = FontWeight.Bold) }
                }

                if (showLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Header(res, showLoading) }

                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Suggested") }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val isSelected = selected == null
                        if (suggested != null) {
                            FormatCard(
                                title = describeChoice(suggested),
                                line2 = sizeAndBitrate(suggested.video, suggested.audio),
                                line3 = codecLine(suggested.video, suggested.audio),
                                selected = isSelected,
                                onClick = { selected = null }
                            )
                        } else {
                            FormatCard(
                                title = if (showLoading) "Fetching formats…" else "Best quality (automatic)",
                                line2 = "",
                                line3 = if (showLoading) "" else "Let yt-dlp pick the best video and audio",
                                selected = isSelected,
                                onClick = { selected = null }
                            )
                        }
                    }

                    if (videoList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Video") }
                        items(videoList) { f ->
                            val choice = Choice(f, if (f.hasAudio()) null else bestAudio, false)
                            FormatCard(
                                title = describeVideo(f),
                                line2 = sizeAndBitrate(f, null),
                                line3 = codecLine(f, null),
                                selected = selected?.video === f,
                                onClick = { selected = choice }
                            )
                        }
                    }

                    if (audioList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Audio") }
                        items(audioList) { f ->
                            val choice = Choice(null, f, true)
                            FormatCard(
                                title = describeAudio(f),
                                line2 = sizeAndBitrate(null, f),
                                line3 = codecLine(null, f),
                                selected = selected?.audio === f && selected?.audioOnly == true,
                                onClick = { selected = choice }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(res: ResultItem?, showLoading: Boolean) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            val thumb = res?.thumb ?: ""
            AndroidView(
                factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { iv -> iv.loadThumbnail(false, thumb) },
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                val title = res?.title ?: ""
                Text(
                    text = if (title.isBlank() && showLoading) "Fetching data…" else title.ifBlank { res?.url ?: "" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val sub = listOfNotNull(
                    res?.author?.takeIf { it.isNotBlank() },
                    res?.duration?.takeIf { it.isNotBlank() }
                ).joinToString("  •  ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    @Composable
    private fun FormatCard(title: String, line2: String, line3: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        val container = if (selected) MaterialTheme.colorScheme.primaryContainer else ComposeColor.Transparent
        val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Surface(
            shape = shape,
            color = container,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (line2.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = line2, style = MaterialTheme.typography.bodySmall)
                }
                if (line3.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = line3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Format text helpers
    // ---------------------------------------------------------------------------------------------

    private fun Format.hasVideo() = vcodec.isNotBlank() && vcodec != "none"

    private fun Format.hasAudio() = acodec.isNotBlank() && acodec != "none"

    private fun describeVideo(f: Format): String {
        val h = f.height
        val w = f.width
        val res = when {
            h != null && w != null -> "${w}x${h} (${h}p)"
            h != null -> "${h}p"
            else -> f.format_note
        }
        return "${f.format_id} - $res"
    }

    private fun describeAudio(f: Format): String {
        val note = f.format_note.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "${f.format_id} - audio only$note"
    }

    private fun describeChoice(c: Choice): String {
        val v = c.video ?: return c.audio?.let { describeAudio(it) } ?: ""
        val a = c.audio
        return if (a != null) "${describeVideo(v)}+${a.format_id} - audio only" else describeVideo(v)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f G", mb / 1024) else String.format(Locale.US, "%.2f M", mb)
    }

    private fun sizeAndBitrate(video: Format?, audio: Format?): String {
        val size = formatSize((video?.filesize ?: 0L) + (audio?.filesize ?: 0L))
        val tbr = ((video?.tbr?.toDoubleOrNull() ?: 0.0) + (audio?.tbr?.toDoubleOrNull() ?: 0.0))
        val bitrate = if (tbr > 0) String.format(Locale.US, "%.1f Kbps", tbr) else ""
        return listOf(size, bitrate).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun codecLine(video: Format?, audio: Format?): String {
        val main = video ?: audio ?: return ""
        val codecs = listOfNotNull(
            video?.vcodec?.takeIf { it.isNotBlank() && it != "none" },
            (audio ?: video)?.acodec?.takeIf { it.isNotBlank() && it != "none" }
        ).joinToString(" ") { it.substringBefore('.').uppercase(Locale.US) }
        val container = main.container.uppercase(Locale.US)
        return if (codecs.isNotBlank()) "$container ($codecs)" else container
    }
}
