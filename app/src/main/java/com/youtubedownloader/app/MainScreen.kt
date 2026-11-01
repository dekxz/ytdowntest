package com.youtubedownloader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youtubedownloader.app.MainViewModel.Kind

// Colors taken from the desktop app's stylesheet
private val WinDark = darkColorScheme(
    primary = Color(0xFF0078D4),
    onPrimary = Color.White,
    background = Color(0xFF202020),
    onBackground = Color.White,
    surface = Color(0xFF2D2D2D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2D2D2D),
    outline = Color(0xFF3A3A3A),
)
private val DownloadRed = Color(0xFFE74C3C)
private val Grey = Color(0xFFA6A6A6)

private fun Kind.color(): Color = when (this) {
    Kind.INFO, Kind.PROGRESS -> Color(0xFFE0E0E0)
    Kind.DIM -> Grey
    Kind.WARN -> Color(0xFFFFA500)
    Kind.ERROR -> Color(0xFFFF5555)
    Kind.OK -> Color(0xFF4CAF50)
}

@Composable
fun MainScreen(
    vm: MainViewModel,
    onPaste: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    MaterialTheme(colorScheme = WinDark) {
        val url by vm.url.collectAsState()
        val resIndex by vm.resolutionIndex.collectAsState()
        val progress by vm.progress.collectAsState()
        val indeterminate by vm.indeterminate.collectAsState()
        val logLines by vm.logLines.collectAsState()
        val busy by vm.busy.collectAsState()
        val downloading by vm.downloading.collectAsState()
        val message by vm.message.collectAsState()

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Paste the YouTube video URL and select the format to download.",
                    fontSize = 14.sp,
                    color = Grey,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                HorizontalDivider(color = Color(0xFF333333))

                // URL input
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = vm::setUrl,
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("YouTube URL") },
                        placeholder = { Text("https://www.youtube.com/watch?v=...") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onPaste, enabled = !busy) { Text("Paste") }
                }

                // Resolution selection (video only)
                Picker(
                    label = "Resolution (video only)",
                    items = MainViewModel.RESOLUTIONS,
                    selected = resIndex,
                    enabled = !busy,
                    onSelect = vm::setResolution,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Progress bar
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF2D2D2D),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF2D2D2D),
                    )
                }

                // Log output
                val listState = rememberLazyListState()
                LaunchedEffect(logLines) {
                    if (logLines.isNotEmpty()) listState.scrollToItem(logLines.lastIndex)
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    if (logLines.isEmpty()) {
                        Text("Output will appear here...", fontSize = 11.sp, color = Color(0xFF707070), fontFamily = FontFamily.Monospace)
                    } else {
                        SelectionContainer {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(logLines) { line ->
                                    Text(
                                        line.text,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = line.kind.color(),
                                    )
                                }
                            }
                        }
                    }
                }

                // Download buttons (replaced by Cancel while a download runs)
                if (downloading) {
                    Button(
                        onClick = vm::cancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("CANCEL", fontWeight = FontWeight.Bold) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.startDownload(MainViewModel.Type.VIDEO) },
                            enabled = !busy,
                            colors = redButton(),
                            modifier = Modifier.weight(1f),
                        ) { Text("DOWNLOAD VIDEO", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
                        Button(
                            onClick = { vm.startDownload(MainViewModel.Type.AUDIO) },
                            enabled = !busy,
                            colors = redButton(),
                            modifier = Modifier.weight(1f),
                        ) { Text("DOWNLOAD AUDIO", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenFolder, modifier = Modifier.weight(1f)) {
                        Text("OPEN DOWNLOADS FOLDER", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = vm::updateYtDlp, enabled = !busy) { Text("Update yt-dlp") }
                }
            }
        }

        message?.let { m ->
            AlertDialog(
                onDismissRequest = vm::dismissMessage,
                title = { Text(m.title) },
                text = { Text(m.text) },
                confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
                containerColor = Color(0xFF252525),
            )
        }
    }
}

@Composable
private fun redButton() = ButtonDefaults.buttonColors(
    containerColor = DownloadRed,
    contentColor = Color.White,
    disabledContainerColor = Color(0xFF454545),
    disabledContentColor = Color(0xFF808080),
)

@Composable
private fun Picker(
    label: String,
    items: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${items.getOrNull(selected) ?: ""}", maxLines = 1, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, item ->
                DropdownMenuItem(text = { Text(item, fontSize = 13.sp) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}
