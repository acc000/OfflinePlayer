package com.example.offlineplayer

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { App() } }
    }
}

// Material You: los colores salen del fondo de pantalla del movil (Android 12+).
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

private val tabs = listOf(
    "Biblioteca" to Icons.Default.LibraryMusic,
    "Listas" to Icons.Default.QueueMusic,
    "Para ti" to Icons.Default.AutoAwesome,
    "Ajustes" to Icons.Default.Settings
)

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun App(vm: AppVM = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showPlayer by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Column {
                    if (vm.current != null) MiniPlayer(vm) { showPlayer = true }
                    NavigationBar {
                        tabs.forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> LibraryScreen(vm)
                    1 -> PlaylistsScreen(vm)
                    2 -> ForYouScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
        if (showPlayer) {
            BackHandler { showPlayer = false }
            PlayerScreen(vm) { showPlayer = false }
        }
    }
}

@Composable
fun LibraryScreen(vm: AppVM) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.addFolder(it) } }
    var query by remember { mutableStateOf("") }
    val list = remember(vm.songs, query) {
        if (query.isBlank()) vm.songs else vm.songs.filter { (it.title + it.artist + it.album).contains(query, true) }
    }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text("Biblioteca", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(null) }) {
                Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("A\u00f1adir carpeta")
            }
            FilledTonalButton(enabled = list.isNotEmpty(), onClick = { vm.playShuffled(list) }) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Aleatorio")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Buscar canci\u00f3n, artista, \u00e1lbum") },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        if (vm.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.songs.isEmpty() && !vm.scanning) {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text("A\u00f1ade una carpeta del m\u00f3vil para empezar.") }
        }
        LazyColumn {
            itemsIndexed(list, key = { _, s -> s.uri }) { i, s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1) },
                    supportingContent = { Text("${s.artist} \u00b7 ${s.album}", maxLines = 1) },
                    trailingContent = { IconButton({ menuSong = s }) { Icon(Icons.Default.PlaylistAdd, "A\u00f1adir a lista") } },
                    modifier = Modifier.clickable { vm.play(list, i) }
                )
            }
        }
    }
    menuSong?.let { s -> AddToPlaylistDialog(vm, s) { menuSong = null } }
}

@Composable
fun AddToPlaylistDialog(vm: AppVM, song: Song, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A\u00f1adir a lista") },
        text = {
            Column {
                vm.playlists.forEach { p -> TextButton(onClick = { vm.addToPlaylist(p.name, song.uri); onDismiss() }) { Text(p.name) } }
                OutlinedTextField(name, { name = it }, label = { Text("Nueva lista") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { vm.createPlaylist(name.trim(), listOf(song.uri)); onDismiss() }) { Text("Crear y a\u00f1adir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun PlaylistsScreen(vm: AppVM) {
    var open by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val p = vm.playlists.firstOrNull { it.name == open }

    if (p != null) {
        PlaylistDetail(vm, p) { open = null }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Listas", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nueva lista") }
            }
            if (vm.playlists.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text("A\u00fan no tienes listas.") }
            LazyColumn {
                items(vm.playlists, key = { it.name }) { pl ->
                    ListItem(
                        headlineContent = { Text(pl.name) },
                        supportingContent = { Text("${pl.uris.size} canciones") },
                        leadingContent = { Icon(Icons.Default.QueueMusic, null) },
                        modifier = Modifier.clickable { open = pl.name }
                    )
                }
            }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Nueva lista") },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { vm.createPlaylist(newName.trim()); newName = ""; creating = false }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } }
    )
}

@Composable
fun PlaylistDetail(vm: AppVM, p: Playlist, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val map = vm.songs.associateBy { it.uri }
    val songs = p.uris.mapNotNull { map[it] }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Volver") }
            Text(p.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton({ vm.deletePlaylist(p.name); onBack() }) { Icon(Icons.Default.Delete, "Eliminar lista") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = songs.isNotEmpty(), onClick = { vm.play(songs, 0) }) { Icon(Icons.Default.PlayArrow, null); Text("Reproducir") }
            FilledTonalButton(enabled = songs.isNotEmpty(), onClick = { vm.playShuffled(songs) }) { Icon(Icons.Default.Shuffle, null); Text("Aleatorio") }
        }
        LazyColumn {
            itemsIndexed(songs, key = { _, s -> s.uri }) { i, s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1) },
                    supportingContent = { Text(s.artist, maxLines = 1) },
                    trailingContent = { IconButton({ vm.removeFromPlaylist(p.name, s.uri) }) { Icon(Icons.Default.Close, "Quitar") } },
                    modifier = Modifier.clickable { vm.play(songs, i) }
                )
            }
        }
    }
}

@Composable
fun ForYouScreen(vm: AppVM) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Para ti", style = MaterialTheme.typography.headlineLarge)
        Text("La IA crea listas a partir de los t\u00edtulos, artistas y g\u00e9neros de tu biblioteca. Tus archivos nunca se env\u00edan.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(enabled = !vm.aiLoading, onClick = { vm.recommend() }) {
            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Generar recomendaciones")
        }
        if (vm.aiLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.aiResult, key = { it.name }) { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(r.reason, style = MaterialTheme.typography.bodyMedium)
                        Text("${r.uris.size} canciones", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.playUris(r.uris) }) { Text("Reproducir") }
                            OutlinedButton(onClick = { vm.createPlaylist(r.name, r.uris); vm.message = "Lista guardada" }) { Text("Guardar lista") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVM) {
    var key by remember { mutableStateOf(vm.apiKey) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Carpetas de m\u00fasica", style = MaterialTheme.typography.titleMedium) }
        items(vm.folders.toList()) { f ->
            ListItem(
                headlineContent = { Text(Uri.decode(f).substringAfterLast(':'), maxLines = 1) },
                trailingContent = { IconButton({ vm.removeFolder(f) }) { Icon(Icons.Default.Delete, "Quitar carpeta") } }
            )
        }
        item { OutlinedButton(onClick = { vm.rescan() }, enabled = !vm.scanning) { Text("Volver a escanear") } }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Clave de API de Anthropic (recomendaciones IA)", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), label = { Text("sk-ant-...") })
        }
        item { Button(onClick = { vm.saveKey(key) }) { Text("Guardar clave") } }
    }
}

@Composable
fun MiniPlayer(vm: AppVM, onOpen: () -> Unit) {
    val md = vm.current?.mediaMetadata
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(md?.title?.toString() ?: "", maxLines = 1, fontWeight = FontWeight.Bold)
                Text(md?.artist?.toString() ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            IconButton({ vm.togglePlay() }) { Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir/Pausar") }
            IconButton({ vm.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
        }
    }
}

@Composable
fun PlayerScreen(vm: AppVM, onClose: () -> Unit) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var art by remember { mutableStateOf<ImageBitmap?>(null) }
    val ctx = LocalContext.current
    val md = vm.current?.mediaMetadata

    LaunchedEffect(vm.controller) {
        while (true) {
            vm.controller?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0L) }
            delay(500)
        }
    }
    LaunchedEffect(vm.current?.mediaId) {
        val id = vm.current?.mediaId ?: return@LaunchedEffect
        art = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(id))
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                } finally { r.release() }
            }.getOrNull()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") } }
            Card(
                Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                val a = art
                if (a != null) Image(a, null, Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(md?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(md?.artist?.toString() ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Slider(
                value = (drag ?: (if (dur > 0) pos.toFloat() / dur else 0f)),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { vm.seekTo((it * dur).toLong()) }; drag = null }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(fmt(pos)); Text(fmt(dur)) }
            Spacer(Modifier.height(16.dp))
            val on = MaterialTheme.colorScheme.primary
            val off = MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.toggleShuffle() }) { Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (vm.shuffle) on else off) }
                IconButton({ vm.prev() }) { Icon(Icons.Default.SkipPrevious, "Anterior", Modifier.size(40.dp)) }
                FilledIconButton({ vm.togglePlay() }, Modifier.size(72.dp)) {
                    Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir/Pausar", Modifier.size(40.dp))
                }
                IconButton({ vm.next() }) { Icon(Icons.Default.SkipNext, "Siguiente", Modifier.size(40.dp)) }
                IconButton({ vm.cycleRepeat() }) {
                    Icon(
                        if (vm.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetir",
                        tint = if (vm.repeat != Player.REPEAT_MODE_OFF) on else off
                    )
                }
            }
        }
    }
}
