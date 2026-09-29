package com.newoether.agora.newgpt

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Obsidian = Color(0xFF030304)
private val Surface = Color(0xFF0B0B0D)
private val Raised = Color(0xFF151518)
private val Gold = Color(0xFFE1B84A)
private val GoldBright = Color(0xFFFFE7A0)
private val GoldDeep = Color(0xFF8D6412)
private val Ivory = Color(0xFFF4EEDF)

@Serializable
private data class StudioAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val systemPrompt: String,
    val tools: List<String> = emptyList(),
    val handoffs: List<String> = emptyList(),
)

@Serializable
private data class AppSpec(
    val name: String,
    val packageName: String,
    val platform: String,
    val features: List<String>,
    val screens: List<String>,
)

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
private const val PREFS = "newgpt_studio"

private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun loadAgents(context: Context): List<StudioAgent> {
    val raw = prefs(context).getString("agents", null)
        ?: return listOf(
            StudioAgent(
                name = "Koordynator",
                description = "Główny agent NewGPT.",
                systemPrompt = "Rozbijaj złożone zadania na kroki, dobieraj narzędzia i wymagaj weryfikacji.",
                tools = listOf("research", "repository", "verification"),
            )
        )
    return runCatching {
        json.decodeFromString(ListSerializer(StudioAgent.serializer()), raw)
    }.getOrDefault(emptyList())
}

private fun saveAgents(context: Context, agents: List<StudioAgent>) {
    prefs(context).edit()
        .putString("agents", json.encodeToString(ListSerializer(StudioAgent.serializer()), agents))
        .apply()
}

private fun copyUriToApp(context: Context, uri: Uri, name: String): File {
    val directory = File(context.filesDir, "newgpt/gguf").apply { mkdirs() }
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(directory, safeName)
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Nie można otworzyć pliku." }
        target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
    }
    return target
}

@Composable
fun NewGptStudioScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val dualRuntime = remember { DualGgufRuntime() }
    val github = remember { GitHubConnector(context) }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    var agents by remember { mutableStateOf(loadAgents(context)) }
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var agentName by rememberSaveable { mutableStateOf("") }
    var agentDescription by rememberSaveable { mutableStateOf("") }
    var agentPrompt by rememberSaveable { mutableStateOf("") }
    var agentTools by rememberSaveable { mutableStateOf("") }
    var agentHandoffs by rememberSaveable { mutableStateOf("") }

    var appName by rememberSaveable { mutableStateOf("NewGPT App") }
    var packageName by rememberSaveable { mutableStateOf("com.mojealterego.newgptapp") }
    var platform by rememberSaveable { mutableStateOf("Android") }
    var features by rememberSaveable { mutableStateOf("") }
    var screens by rememberSaveable { mutableStateOf("") }
    var appPlan by rememberSaveable { mutableStateOf("") }

    var modelPaths by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var modelStatus by rememberSaveable { mutableStateOf("Wybierz dokładnie dwa pliki GGUF.") }

    var githubToken by remember { mutableStateOf(github.token) }
    var githubRepo by rememberSaveable { mutableStateOf("") }
    var githubPath by rememberSaveable { mutableStateOf("newgpt/BUILD_PLAN.md") }
    var githubContent by rememberSaveable { mutableStateOf("") }
    var githubRepos by remember { mutableStateOf(emptyList<String>()) }
    var githubStatus by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { dualRuntime.close() }
    }

    val ggufPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.size != 2) {
            modelStatus = "Wybierz dokładnie dwa pliki GGUF."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            modelStatus = "Kopiowanie i ładowanie dwóch modeli…"
            runCatching {
                val copied = uris.mapIndexed { index, uri ->
                    copyUriToApp(context, uri, "model_" + (index + 1) + "_" + (uri.lastPathSegment ?: "model.gguf"))
                }
                modelPaths = copied.map(File::getAbsolutePath)
                val loaded = withContext(Dispatchers.IO) {
                    dualRuntime.loadBoth(modelPaths[0], modelPaths[1])
                }
                modelStatus = if (loaded) {
                    "Dwa modele GGUF są jednocześnie załadowane."
                } else {
                    "Nie udało się załadować obu modeli."
                }
            }.onFailure {
                modelStatus = "Błąd GGUF: " + (it.message ?: "nieznany błąd")
            }
        }
    }

    fun resetAgentDraft() {
        selectedAgent = null
        agentName = ""
        agentDescription = ""
        agentPrompt = ""
        agentTools = ""
        agentHandoffs = ""
    }

    fun editAgent(agent: StudioAgent) {
        selectedAgent = agent.id
        agentName = agent.name
        agentDescription = agent.description
        agentPrompt = agent.systemPrompt
        agentTools = agent.tools.joinToString(", ")
        agentHandoffs = agent.handoffs.joinToString(", ")
    }

    fun saveAgent() {
        if (agentName.isBlank() || agentPrompt.isBlank()) return
        val id = selectedAgent ?: UUID.randomUUID().toString()
        val agent = StudioAgent(
            id = id,
            name = agentName.trim(),
            description = agentDescription.trim(),
            systemPrompt = agentPrompt.trim(),
            tools = agentTools.split(",").map(String::trim).filter(String::isNotBlank).distinct(),
            handoffs = agentHandoffs.split(",").map(String::trim).filter(String::isNotBlank).distinct(),
        )
        agents = (agents.filterNot { it.id == id } + agent).sortedBy { it.name.lowercase() }
        saveAgents(context, agents)
        selectedAgent = id
    }

    Scaffold(
        containerColor = Obsidian,
        topBar = {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xF0030304))
                    .border(1.dp, GoldDeep)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, "Wstecz", tint = GoldBright)
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_newgpt),
                        contentDescription = "NewGPT",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(50.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NewGPT", color = Ivory, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("STUDIO · MOJEALTEREGO", color = Gold, fontSize = 10.sp, letterSpacing = 2.sp)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, GoldBright, GoldDeep, Color.Transparent)))
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab, containerColor = Surface, contentColor = Gold) {
                listOf("AGENTS", "APP BUILDER", "GGUF ×2", "GITHUB").forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
            when (tab) {
                0 -> AgentPanel(
                    agents, selectedAgent, agentName, agentDescription, agentPrompt, agentTools, agentHandoffs,
                    ::resetAgentDraft, ::editAgent,
                    { id ->
                        agents = agents.filterNot { it.id == id }
                        saveAgents(context, agents)
                        if (selectedAgent == id) resetAgentDraft()
                    },
                    { agentName = it }, { agentDescription = it }, { agentPrompt = it },
                    { agentTools = it }, { agentHandoffs = it }, ::saveAgent
                )
                1 -> AppPanel(
                    appName, packageName, platform, features, screens, appPlan,
                    { appName = it }, { packageName = it }, { platform = it },
                    { features = it }, { screens = it },
                    {
                        val spec = AppSpec(
                            appName.trim(), packageName.trim(), platform.trim(),
                            features.split(",").map(String::trim).filter(String::isNotBlank),
                            screens.split(",").map(String::trim).filter(String::isNotBlank)
                        )
                        appPlan = buildString {
                            appendLine("# " + spec.name)
                            appendLine()
                            appendLine("Platform: " + spec.platform)
                            appendLine("Package: " + spec.packageName)
                            appendLine()
                            appendLine("## Screens")
                            spec.screens.forEach { appendLine("- " + it) }
                            appendLine()
                            appendLine("## Features")
                            spec.features.forEach { appendLine("- " + it) }
                            appendLine()
                            appendLine("## Quality gates")
                            appendLine("- Gradle compile")
                            appendLine("- unit tests")
                            appendLine("- APK/AAB")
                            appendLine("- installation verification")
                        }
                    },
                    { clipboard.setText(AnnotatedString(appPlan)) }
                )
                2 -> GgufPanel(
                    modelPaths, modelStatus,
                    dualRuntime.isLoaded(0) && dualRuntime.isLoaded(1),
                    { ggufPicker.launch(arrayOf("*/*")) },
                    {
                        dualRuntime.close()
                        modelStatus = "Modele zwolnione z pamięci."
                    }
                )
                3 -> GithubPanel(
                    githubToken, githubRepo, githubPath, githubContent, githubRepos, githubStatus,
                    {
                        githubToken = it
                        github.setToken(it)
                    },
                    { githubRepo = it }, { githubPath = it }, { githubContent = it },
                    {
                        scope.launch {
                            githubStatus = "Pobieranie repozytoriów…"
                            github.listRepositories()
                                .onSuccess {
                                    githubRepos = it
                                    githubStatus = "Znaleziono " + it.size + " repozytoriów."
                                }
                                .onFailure { githubStatus = "Błąd: " + (it.message ?: "GitHub") }
                        }
                    },
                    {
                        scope.launch {
                            githubStatus = "Zapisywanie pliku…"
                            github.createOrUpdateFile(
                                githubRepo, githubPath, githubContent,
                                "NewGPT Studio: update " + githubPath
                            )
                                .onSuccess { githubStatus = "GitHub: zapisano " + githubPath }
                                .onFailure { githubStatus = "Błąd GitHub: " + (it.message ?: "nieznany błąd") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StudioCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC0B0B0D)),
        border = BorderStroke(1.dp, Color(0xCC8D6412)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = GoldBright, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, color = Ivory, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color(0x555F4A18))
            content()
        }
    }
}

@Composable
private fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    singleLine: Boolean = minLines == 1,
) {
    OutlinedTextField(
        value, onValueChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = minLines,
        singleLine = singleLine,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = GoldDeep,
            focusedLabelColor = GoldBright,
            unfocusedLabelColor = Color(0xFFD0C8B8),
            cursorColor = GoldBright,
            focusedTextColor = Ivory,
            unfocusedTextColor = Ivory,
        ),
    )
}

@Composable
private fun AgentPanel(
    agents: List<StudioAgent>,
    selectedId: String?,
    name: String,
    description: String,
    prompt: String,
    tools: String,
    handoffs: String,
    onNew: () -> Unit,
    onSelect: (StudioAgent) -> Unit,
    onDelete: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onPrompt: (String) -> Unit,
    onTools: (String) -> Unit,
    onHandoffs: (String) -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            StudioCard("AGENTS BUILDER · NO CODE", Icons.Default.SmartToy) {
                Text("Twórz agentów przez formularz: prompt, narzędzia i przekazywanie zadań.", color = Color(0xFFD8D2C5))
                Button(
                    onClick = onNew,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Obsidian),
                ) { Text("＋ NOWY AGENT", fontWeight = FontWeight.Bold) }
                StudioTextField(name, onName, "Nazwa")
                StudioTextField(description, onDescription, "Opis")
                StudioTextField(prompt, onPrompt, "System prompt", 6, false)
                StudioTextField(tools, onTools, "Tools — po przecinku")
                StudioTextField(handoffs, onHandoffs, "Handoffs — ID agentów")
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && prompt.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Obsidian),
                ) { Text("ZAPISZ AGENTA", fontWeight = FontWeight.Bold) }
            }
        }
        items(agents, key = { it.id }) { agent ->
            StudioCard(agent.name, Icons.Default.Psychology) {
                Text(agent.description, color = Color(0xFFD8D2C5))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelect(agent) }, modifier = Modifier.weight(1f)) {
                        Text(if (agent.id == selectedId) "EDYTUJESZ" else "EDYTUJ", color = GoldBright)
                    }
                    OutlinedButton(onClick = { onDelete(agent.id) }, modifier = Modifier.weight(1f)) {
                        Text("USUŃ", color = GoldBright)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPanel(
    appName: String, packageName: String, platform: String, features: String, screens: String, plan: String,
    onName: (String) -> Unit, onPackage: (String) -> Unit, onPlatform: (String) -> Unit,
    onFeatures: (String) -> Unit, onScreens: (String) -> Unit, onGenerate: () -> Unit, onCopy: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            StudioCard("APP BUILDER · NO CODE", Icons.Default.PhoneAndroid) {
                StudioTextField(appName, onName, "Nazwa aplikacji")
                StudioTextField(packageName, onPackage, "Package name")
                StudioTextField(platform, onPlatform, "Platforma / stack")
                StudioTextField(features, onFeatures, "Funkcje — po przecinku")
                StudioTextField(screens, onScreens, "Ekrany — po przecinku")
                Button(
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Obsidian),
                ) { Text("GENERUJ SPECYFIKACJĘ", fontWeight = FontWeight.Bold) }
            }
        }
        item {
            StudioCard("SPECYFIKACJA PROJEKTU", Icons.Default.AutoAwesome) {
                SelectionContainer {
                    Text(
                        if (plan.isBlank()) "Wprowadź dane i wygeneruj specyfikację." else plan,
                        color = Ivory,
                    )
                }
                if (plan.isNotBlank()) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                        Text("KOPIUJ SPECYFIKACJĘ", color = GoldBright)
                    }
                }
            }
        }
    }
}

@Composable
private fun GgufPanel(
    paths: List<String>,
    status: String,
    loaded: Boolean,
    onPick: () -> Unit,
    onUnload: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            StudioCard("GGUF ×2 · DWA MODELE JEDNOCZEŚNIE", Icons.Default.Memory) {
                Text(
                    "Wybierz dwa pliki GGUF. NewGPT utrzymuje dwa niezależne silniki llama.cpp w tej przestrzeni.",
                    color = Color(0xFFD8D2C5)
                )
                paths.forEachIndexed { index, path ->
                    Text(
                        "MODEL " + (index + 1) + ": " + File(path).name,
                        color = if (loaded) GoldBright else Ivory
                    )
                }
                Text(status, color = if (loaded) Gold else Ivory)
                Button(
                    onClick = onPick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Obsidian),
                ) { Text("WYBIERZ DWA GGUF", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onUnload, modifier = Modifier.fillMaxWidth()) {
                    Text("ZWOLNIJ MODELE", color = GoldBright)
                }
            }
        }
    }
}

@Composable
private fun GithubPanel(
    token: String,
    repo: String,
    path: String,
    content: String,
    repos: List<String>,
    status: String,
    onToken: (String) -> Unit,
    onRepo: (String) -> Unit,
    onPath: (String) -> Unit,
    onContent: (String) -> Unit,
    onList: () -> Unit,
    onPush: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            StudioCard("GITHUB · POŁĄCZENIE", Icons.Default.Code) {
                StudioTextField(token, onToken, "GitHub Personal Access Token", singleLine = true)
                StudioTextField(repo, onRepo, "Repozytorium owner/name")
                StudioTextField(path, onPath, "Ścieżka pliku")
                StudioTextField(content, onContent, "Treść pliku", 8, false)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onList,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Obsidian),
                    ) { Text("REPOZYTORIA") }
                    OutlinedButton(onClick = onPush, modifier = Modifier.weight(1f)) {
                        Text("ZAPISZ", color = GoldBright)
                    }
                }
                if (status.isNotBlank()) Text(status, color = GoldBright)
            }
        }
        if (repos.isNotEmpty()) {
            item { Text("DOSTĘPNE REPOZYTORIA", color = Gold, fontWeight = FontWeight.Bold) }
            items(repos) { repository ->
                AssistChip(
                    onClick = { onRepo(repository) },
                    label = { Text(repository) },
                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                )
            }
        }
    }
}
