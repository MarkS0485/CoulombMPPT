package app.coulombmppt.ui.logs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.coulombmppt.ui.components.BrandTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    vm: LogsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Logs",
                subtitle = buildString {
                    append(if (state.showingAllSessions) "All sessions" else "This launch")
                    append(" · ")
                    append(if (state.tailing) "live (1.5 s)" else "paused")
                },
                onBack   = onBack,
                actions = {
                    IconButton(onClick = { vm.toggleAllSessions() }) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = if (state.showingAllSessions) "Show current session only"
                                                 else "Show all retained sessions",
                            tint = if (state.showingAllSessions) Color.White else Color.White.copy(alpha = 0.55f),
                        )
                    }
                    IconButton(onClick = {
                        if (state.tailing) vm.pauseTail() else vm.startTail()
                    }) {
                        Icon(
                            imageVector = if (state.tailing) Icons.Filled.PauseCircleOutline
                                          else                Icons.Filled.PlayCircleOutline,
                            contentDescription = if (state.tailing) "Pause tailing" else "Resume tailing",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs", tint = Color.White)
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "CoulombMPPT logs")
                            putExtra(Intent.EXTRA_TEXT, state.text)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share logs")
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text  = if (state.text.isBlank()) "(no log entries yet)"
                        else                       state.text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear logs?") },
            text = {
                Text(
                    if (state.showingAllSessions)
                        "Delete every retained log file (this launch + the last 7 days). Cannot be undone."
                    else
                        "Clear the current launch's log. Previous retained sessions are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (state.showingAllSessions) vm.clearAll() else vm.clearCurrent()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}
