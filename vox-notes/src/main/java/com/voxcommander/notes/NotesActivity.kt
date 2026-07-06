package com.voxcommander.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.voxcommander.notes.data.Note
import com.voxcommander.notes.data.NoteDao
import com.voxcommander.notes.data.NotesDatabase
import kotlinx.coroutines.launch

class NotesActivity : ComponentActivity() {

    private val dao: NoteDao by lazy { NotesDatabase.get(this).noteDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoxIntent(intent)
        setContent { NotesApp(dao) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoxIntent(intent)
    }

    /** If launched by VoxCommander's native intent, save the query as a note. Plain parsing. */
    private fun handleVoxIntent(intent: Intent?) {
        if (intent?.action != VoxContract.ACTION_HANDLE) return
        val text = intent.getStringExtra(VoxContract.EXTRA_QUERY)?.trim().orEmpty()
        if (text.isEmpty()) return
        lifecycleScope.launch { dao.insert(Note(text = text, createdAt = System.currentTimeMillis())) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun NotesApp(dao: NoteDao) {
    MaterialTheme {
        val notes by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        var draft by remember { mutableStateOf("") }

        Scaffold(topBar = { TopAppBar(title = { Text("Vox Notes") }) }) { pad ->
            Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.width(0.dp).fillMaxWidth().padding(end = 8.dp),
                        label = { Text("New note") }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val t = draft.trim()
                        if (t.isNotEmpty()) {
                            scope.launch { dao.insert(Note(text = t, createdAt = System.currentTimeMillis())) }
                            draft = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) { Text("Add") }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { note ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(note.text, modifier = Modifier.width(0.dp).fillMaxWidth(0.8f))
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { scope.launch { dao.delete(note) } }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
