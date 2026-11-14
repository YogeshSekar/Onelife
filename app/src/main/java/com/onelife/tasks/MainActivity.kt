package com.onelife.tasks

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.taskStore by preferencesDataStore(name = "tasks")
private val tasksKey = stringPreferencesKey("tasks_json")

data class Task(val id: String, val title: String, val notes: String, val done: Boolean)

class TaskRepository(private val context: Context) {
    val tasks: Flow<List<Task>> = context.taskStore.data.map { prefs ->
        runCatching {
            val array = JSONArray(prefs[tasksKey] ?: "[]")
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Task(item.getString("id"), item.getString("title"), item.optString("notes"), item.optBoolean("done"))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(task: Task) = mutate { current ->
        val index = current.indexOfFirst { it.id == task.id }
        if (index < 0) current.add(0, task) else current[index] = task
    }

    suspend fun delete(id: String) = mutate { it.removeAll { task -> task.id == id } }

    suspend fun setDone(id: String, done: Boolean) = mutate { current ->
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) current[index] = current[index].copy(done = done)
    }

    private suspend fun mutate(change: (MutableList<Task>) -> Unit) {
        context.taskStore.edit { prefs ->
            val current = runCatching {
                val array = JSONArray(prefs[tasksKey] ?: "[]")
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    Task(item.getString("id"), item.getString("title"), item.optString("notes"), item.optBoolean("done"))
                }.toMutableList()
            }.getOrDefault(mutableListOf())
            change(current)
            prefs[tasksKey] = JSONArray().apply {
                current.forEach { task ->
                    put(JSONObject().put("id", task.id).put("title", task.title)
                        .put("notes", task.notes).put("done", task.done))
                }
            }.toString()
        }
    }
}

class TasksViewModel(private val repository: TaskRepository) : ViewModel() {
    val tasks = repository.tasks
    suspend fun save(task: Task) = repository.save(task)
    suspend fun delete(id: String) = repository.delete(id)
    suspend fun setDone(id: String, done: Boolean) = repository.setDone(id, done)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val context = LocalContext.current
                val model: TasksViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        TasksViewModel(TaskRepository(context.applicationContext)) as T
                })
                TaskScreen(model)
            }
        }
    }
}

@Composable
private fun TaskScreen(model: TasksViewModel) {
    val tasks by model.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Task?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    val remaining = tasks.count { !it.done }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = null; showEditor = true }) { Text("Add task") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(28.dp))
            Text("one life", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Make room for what matters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Text("$remaining remaining · ${tasks.count { it.done }} completed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("A fresh start. Add your first task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks.sortedBy { it.done }, key = { it.id }) { task ->
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.done, onCheckedChange = { checked -> scope.launch { model.setDone(task.id, checked) } })
                                Column(Modifier.weight(1f).clickable { editing = task; showEditor = true }.padding(start = 8.dp)) {
                                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (task.notes.isNotBlank()) Text(task.notes, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { editing = task; showEditor = true }) { Text("Edit") }
                                TextButton(onClick = { deleting = task }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val original = editing
        var title by remember(original, showEditor) { mutableStateOf(original?.title ?: "") }
        var notes by remember(original, showEditor) { mutableStateOf(original?.notes ?: "") }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (original == null) "New task" else "Edit task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Task") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch { model.save(Task(original?.id ?: UUID.randomUUID().toString(), title.trim(), notes.trim(), original?.done ?: false)) }
                    showEditor = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }
    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete task?") },
            text = { Text("${task.title} will be removed.") },
            confirmButton = { TextButton(onClick = { scope.launch { model.delete(task.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}
