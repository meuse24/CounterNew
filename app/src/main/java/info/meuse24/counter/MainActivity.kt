@file:OptIn(ExperimentalMaterial3Api::class)
package info.meuse24.counter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.meuse24.counter.ui.theme.CounterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ----------------------------------------------------
// 1) Fehlerzustände definieren (Sealed Class)
// ----------------------------------------------------
/**
 * Eine Versiegelte Klasse (sealed class), die verschiedene Fehlerzustände beschreibt.
 * Anhand dieser Fehlerzustände kann die UI (z. B. ein Snackbar) informieren,
 * was genau beim Datenmanagement (Laden, Speichern, Exportieren etc.) schiefgegangen ist.
 */
sealed class EventError {
    /**
     * Fehler beim Laden der Daten.
     * @param message Fehlernachricht für den Nutzer.
     */
    data class LoadError(val message: String) : EventError()

    /**
     * Fehler beim Speichern der Daten.
     * @param message Fehlernachricht für den Nutzer.
     */
    data class SaveError(val message: String) : EventError()

    /**
     * Fehler beim Exportieren der Daten (z.B. CSV-Export).
     * @param message Fehlernachricht für den Nutzer.
     */
    data class ExportError(val message: String) : EventError()

    /**
     * Fehler beim Löschen von Ereignissen.
     * Hier wird kein spezifisches Nachricht-String mitgegeben,
     * da z.B. nur ein generischer Fehler ausgegeben werden kann.
     */
    data object DeleteError : EventError()
}

// ----------------------------------------------------
// 2) UI State definieren
// ----------------------------------------------------
/**
 * Diese Datenklasse hält den Zustand der App in Bezug auf die Ereignisliste (events),
 * einen möglichen Fehlerzustand (error) und einen Ladeindikator (isLoading).
 * Sie dient als "Single source of truth" für die Darstellung in der UI.
 */
data class EventsUiState(
    val events: List<Event> = emptyList(),  // Aktuell gespeicherte Ereignisse
    val error: EventError? = null,          // Aktueller Fehlerzustand
    val isLoading: Boolean = false          // Zeigt an, ob ein Ladevorgang läuft
)

// ----------------------------------------------------
// 3) Event-Datenklasse
// ----------------------------------------------------
/**
 * Repräsentiert ein einzelnes Ereignis, bestehend aus einem Typ (String) und einem Zeitstempel (String).
 * - @Serializable: Ermöglicht das Serialisieren und Deserialisieren via Kotlinx-Serialization.
 * - @Parcelize: Macht die Klasse zu einem Parcelable, sodass sie zwischen Komponenten (z.B. Intents)
 *   übertragen werden kann, ohne Boilerplate-Code schreiben zu müssen.
 */
@Serializable
@Parcelize
data class Event(
    val type: String,          // Art bzw. Bezeichnung des Ereignisses
    val timestamp: String      // Zeitstempel im ISO-Format
) : Parcelable

// ----------------------------------------------------
// 4) ViewModel (CounterViewModel)
// ----------------------------------------------------
/**
 * ViewModel, das die Business-Logik der App kapselt.
 * Es verwaltet den Zustand der UI (EventsUiState) sowie
 * die beiden Button-Texte (event1Text und event2Text).
 */
class CounterViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Konstanten, die als Schlüssel für das Speichern und Laden in savedStateHandle verwendet werden
    companion object {
        private const val EVENTS_KEY = "events_key"
        private const val EVENT1_TEXT_KEY = "event1_text_key"
        private const val EVENT2_TEXT_KEY = "event2_text_key"
    }

    // ------------------------------------------------
    // StateFlow für den Haupt-UI-State (EventsUiState)
    // ------------------------------------------------
    /**
     * MutableStateFlow für den Ereigniszustand.
     * Dieser wird weiter als "immutables" StateFlow nach außen (UI) gegeben.
     */
    private val _uiState = MutableStateFlow(EventsUiState())
    val uiState = _uiState.asStateFlow()

    // ------------------------------------------------
    // StateFlows für die beiden Button-Texte
    // ------------------------------------------------
    /**
     * Speichert den Text für "Ereignis 1", kann dynamisch geändert werden.
     * Falls kein Wert im savedStateHandle gespeichert war, wird ein Standardwert gesetzt.
     */
    private val _event1Text = MutableStateFlow(
        savedStateHandle.get<String>(EVENT1_TEXT_KEY) ?: "Ereignis 1"
    )
    val event1Text = _event1Text.asStateFlow()

    /**
     * Speichert den Text für "Ereignis 2".
     * Falls kein Wert im savedStateHandle gespeichert war, wird ein Standardwert gesetzt.
     */
    private val _event2Text = MutableStateFlow(
        savedStateHandle.get<String>(EVENT2_TEXT_KEY) ?: "Ereignis 2"
    )
    val event2Text = _event2Text.asStateFlow()

    // JSON-Konfiguration mit Kotlinx.Serialization
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ------------------------------------------------
    // Funktion: Events aus Datei laden
    // ------------------------------------------------
    /**
     * Lädt eine Liste von Ereignissen aus einer lokalen JSON-Datei (events.json).
     * - Setzt isLoading auf true, um in der UI einen Ladeindikator zu zeigen.
     * - Liest die Datei und deserialisiert den Inhalt in eine Liste von Event-Objekten.
     * - Falls die Datei nicht existiert, werden leere Events gesetzt.
     * - Bei Fehlern wird ein LoadError im UI-State hinterlegt, damit die UI reagieren kann.
     */
    fun loadEvents(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // Setzt den Ladezustand auf true
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val directory = context.filesDir
                android.util.Log.d("CounterViewModel", "Directory exists: ${directory.exists()}")
                android.util.Log.d("CounterViewModel", "Directory path: ${directory.absolutePath}")

                val file = File(directory, "events.json")
                android.util.Log.d("CounterViewModel", "Checking file: ${file.absolutePath}")
                android.util.Log.d("CounterViewModel", "File exists: ${file.exists()}")

                // Wenn die Datei existiert, lese ihren Inhalt
                if (file.exists()) {
                    val jsonString = file.readText()
                    android.util.Log.d("CounterViewModel", "Read file content: $jsonString")
                    // Deserialisieren in eine Liste von Event
                    val loadedEvents = json.decodeFromString<List<Event>>(jsonString)
                    withContext(Dispatchers.Main) {
                        // Speichere die geladenen Events in savedStateHandle und im UI-State
                        savedStateHandle[EVENTS_KEY] = loadedEvents
                        _uiState.value = _uiState.value.copy(
                            events = loadedEvents,
                            isLoading = false
                        )
                    }
                } else {
                    // Wenn die Datei noch nicht existiert, leere Liste annehmen
                    withContext(Dispatchers.Main) {
                        savedStateHandle[EVENTS_KEY] = emptyList<Event>()
                        _uiState.value = _uiState.value.copy(
                            events = emptyList(),
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                // Fehler abfangen und als LoadError setzen
                android.util.Log.e("CounterViewModel", "Error loading events", e)
                val errorMessage = when (e) {
                    is IOException -> "Dateizugriff fehlgeschlagen"
                    is kotlinx.serialization.SerializationException ->
                        "Daten konnten nicht gelesen werden"
                    else -> "Unerwarteter Fehler beim Laden"
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        error = EventError.LoadError(errorMessage),
                        isLoading = false
                    )
                }
            }
        }
    }

    // ------------------------------------------------
    // Funktion: Neues Event hinzufügen
    // ------------------------------------------------
    /**
     * Erstellt ein neues Event mit dem übergebenen Typen und dem aktuellen Zeitpunkt,
     * fügt es sofort der Liste im UI-State hinzu und speichert asynchron in die Datei.
     * @param typeString Bezeichnung des Ereignisses
     */
    fun addEvent(typeString: String, context: Context) {
        // 1) Neues Ereignis erstellen
        val newEvent = Event(
            type = typeString,
            // Timestamp wird mit dem aktuellen Datum/Uhrzeit gefüllt
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )

        // 2) Liste aktualisieren
        val updatedEvents = _uiState.value.events + newEvent
        savedStateHandle[EVENTS_KEY] = updatedEvents
        _uiState.value = _uiState.value.copy(events = updatedEvents)

        // 3) Speicherung im Hintergrund
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = json.encodeToString(updatedEvents)
                val file = File(context.filesDir, "events.json")
                file.writeText(jsonString)
                android.util.Log.d("CounterViewModel", "Events async saved successfully")
            } catch (e: Exception) {
                // Bei Fehlern einen SaveError setzen
                android.util.Log.e("CounterViewModel", "Error saving events", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        error = EventError.SaveError("Fehler beim Speichern: ${e.message}")
                    )
                }
            }
        }
    }

    // ------------------------------------------------
    // Funktion: Einzelnes Event löschen
    // ------------------------------------------------
    /**
     * Löscht das angegebene Event aus der Liste und speichert die geänderte Liste wieder.
     * Falls die Liste nach dem Löschen leer ist, wird die Datei entfernt.
     */
    fun deleteEvent(event: Event, context: Context) {
        // 1) Sofortiges Entfernen aus der Liste im UI-State
        val updatedEvents = _uiState.value.events.filter { it != event }
        savedStateHandle[EVENTS_KEY] = updatedEvents
        _uiState.value = _uiState.value.copy(events = updatedEvents)

        // 2) Speicherung bzw. Löschen der Datei im Hintergrund
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (updatedEvents.isEmpty()) {
                    val file = File(context.filesDir, "events.json")
                    if (file.exists()) {
                        file.delete()
                        android.util.Log.d("CounterViewModel", "Deleted empty events file")
                    }
                } else {
                    val jsonString = json.encodeToString(updatedEvents)
                    val file = File(context.filesDir, "events.json")
                    file.writeText(jsonString)
                    android.util.Log.d("CounterViewModel", "Events saved after delete")
                }
            } catch (e: Exception) {
                // Bei Fehlern DeleteError setzen
                android.util.Log.e("CounterViewModel", "Error saving after delete", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        error = EventError.DeleteError
                    )
                }
            }
        }
    }

    // ------------------------------------------------
    // Funktion: Fehlerzustand zurücksetzen
    // ------------------------------------------------
    /**
     * Setzt einen eventuell vorhandenen Fehlerzustand im UI-State auf null,
     * um z.B. eine angezeigte Snackbar wieder zu entfernen.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ------------------------------------------------
    // Funktion: Button-Texte aus SharedPreferences laden
    // ------------------------------------------------
    /**
     * Lädt die Button-Texte (event1Text, event2Text) aus den SharedPreferences.
     * Falls nicht vorhanden, bleiben die Standardwerte erhalten.
     */
    fun loadButtonTexts(context: Context) {
        val sp = context.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
        val text1 = sp.getString(EVENT1_TEXT_KEY, _event1Text.value) ?: _event1Text.value
        val text2 = sp.getString(EVENT2_TEXT_KEY, _event2Text.value) ?: _event2Text.value

        // StateFlows aktualisieren
        _event1Text.value = text1
        _event2Text.value = text2

        // savedStateHandle aktualisieren
        savedStateHandle[EVENT1_TEXT_KEY] = text1
        savedStateHandle[EVENT2_TEXT_KEY] = text2
    }

    // ------------------------------------------------
    // Funktion: Button-Texte speichern
    // ------------------------------------------------
    /**
     * Speichert die vom Nutzer angepassten Button-Texte in den SharedPreferences
     * und aktualisiert die StateFlows sowie das savedStateHandle.
     */
    fun saveButtonTexts(context: Context, text1: String, text2: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sp = context.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
            // Daten in SharedPreferences speichern
            sp.edit()
                .putString(EVENT1_TEXT_KEY, text1)
                .putString(EVENT2_TEXT_KEY, text2)
                .apply()

            // Nach dem Speichern die StateFlows updaten (im Main-Thread)
            withContext(Dispatchers.Main) {
                _event1Text.value = text1
                _event2Text.value = text2
                savedStateHandle[EVENT1_TEXT_KEY] = text1
                savedStateHandle[EVENT2_TEXT_KEY] = text2
            }
        }
    }

    // ------------------------------------------------
    // Funktion: Events als CSV teilen
    // ------------------------------------------------
    /**
     * Erstellt eine temporäre CSV-Datei mit allen Ereignissen
     * und startet einen Intent, um diese zu teilen (z.B. per E-Mail).
     * Falls ein Fehler auftritt, wird ein ExportError im UI-State gesetzt.
     */
    fun shareEvents(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Dateiname an Hand eines Zeitstempels
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmm"))
                val fileName = "events_$timestamp.csv"

                // Neue CSV-Datei im internen Speicher anlegen
                val file = File(context.filesDir, fileName)
                file.printWriter().use { out ->
                    // Header-Zeile
                    out.println("Type,Timestamp")
                    // Für jedes Event eine Zeile (Type, Timestamp)
                    _uiState.value.events.forEach { event ->
                        out.println("${event.type},${event.timestamp}")
                    }
                }

                // Uri für den FileProvider
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                // Intent zum Teilen der CSV-Datei
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Aufruf einer Activity zum Auswählen der Share-App
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(shareIntent, "Ereignisse teilen"))
                }
            } catch (e: Exception) {
                // Bei Fehlern den ExportError setzen
                val errorMessage = when (e) {
                    is IOException ->
                        "Export fehlgeschlagen: Dateizugriff nicht möglich"
                    is IllegalArgumentException ->
                        "Export fehlgeschlagen: Ungültige Datei"
                    else -> "Unerwarteter Fehler beim Export"
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        error = EventError.ExportError(errorMessage)
                    )
                }
            }
        }
    }

    // ------------------------------------------------
    // Funktion: Alle Events löschen (Komplett-Reset)
    // ------------------------------------------------
    /**
     * Löscht alle Events aus der Liste, setzt sie im UI-State auf eine leere Liste
     * und entfernt die JSON-Datei im Hintergrund.
     */
    fun clearEvents(context: Context) {
        // 1) UI-State sofort leeren
        savedStateHandle[EVENTS_KEY] = emptyList<Event>()
        _uiState.value = _uiState.value.copy(events = emptyList())

        // 2) Datei löschen, wenn vorhanden
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "events.json")
                if (file.exists()) {
                    file.delete()
                    android.util.Log.d("CounterViewModel", "Events file deleted")
                }
            } catch (e: Exception) {
                // Bei Fehlern einen DeleteError setzen
                android.util.Log.e("CounterViewModel", "Error deleting events file", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        error = EventError.DeleteError
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------
// 5) MainActivity (Startpunkt der App)
// ----------------------------------------------------
/**
 * Die Haupt-Activity, in der das Compose-UI gerendert wird.
 * - enableEdgeToEdge() ermöglicht, dass die App die komplette Bildschirmfläche nutzt.
 * - setContent() lädt das Haupt-Composable (MainScreen) in einem Compose-Umfeld.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aktiviert Edge-to-Edge-Display (Statusleiste, Navigation Bar etc. fließend)
        enableEdgeToEdge()

        // Legt das UI mit Jetpack Compose fest
        setContent {
            // Unser eigenes Material-Theme
            CounterTheme {
                // Instanziiert das ViewModel mithilfe von SavedStateViewModelFactory,
                // um z.B. bei Konfigurationsänderungen (Rotation, Sprache etc.) den Zustand zu erhalten.
                val viewModel: CounterViewModel = viewModel(
                    factory = SavedStateViewModelFactory(application, this)
                )

                val context = LocalContext.current

                // Beim ersten Start (LaunchedEffect(Unit)) werden die Events aus der Datei geladen,
                // sofern die Liste noch leer ist, und die Button-Texte aus den SharedPreferences.
                LaunchedEffect(Unit) {
                    if (viewModel.uiState.value.events.isEmpty()) {
                        viewModel.loadEvents(context)
                    }
                    viewModel.loadButtonTexts(context)
                }

                // Ruft unser Hauptscreen-Composable auf und übergibt das ViewModel
                MainScreen(viewModel)
            }
        }
    }
}

// ----------------------------------------------------
// 6) Composable: MainScreen
// ----------------------------------------------------
/**
 * Hauptbildschirm der App mit folgendem Aufbau:
 * - AppBar (TopAppBar) mit Menü
 * - Zwei Buttons zum Hinzufügen von Ereignissen
 * - Liste der bisherigen Ereignisse
 * - Verschiedene Dialoge (Info-Dialog, Reset-Dialog, Löschen-Dialog, Fehler-Snackbar, etc.)
 */
@Composable
fun MainScreen(viewModel: CounterViewModel) {
    // Lokale State-Variablen für Steuerung von UI-Dialogen und Menüs
    var showMenu by remember { mutableStateOf(false) }          // Zeigt/versteckt das Dropdown-Menü
    var showInfoDialog by remember { mutableStateOf(false) }    // Zeigt/versteckt den Info-Dialog
    var showResetDialog by remember { mutableStateOf(false) }   // Zeigt/versteckt den Reset-Dialog
    var eventToDelete by remember { mutableStateOf<Event?>(null) }  // Temporär für Einzel-Event-Löschdialog
    var showSettingsScreen by remember { mutableStateOf(false) } // Steuert, ob das SettingsScreen angezeigt wird

    // UI-State (Liste von Ereignissen, Fehlermeldung, Ladezustand)
    val uiState by viewModel.uiState.collectAsState()

    // Sortieren der Ereignisse absteigend nach Zeitstempel
    // (Neuestes Ereignis oben in der Liste)
    val sortedEvents = uiState.events.sortedByDescending { it.timestamp }

    // State-Werte für die dynamischen Button-Beschriftungen
    val event1Text by viewModel.event1Text.collectAsState()
    val event2Text by viewModel.event2Text.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // LazyListState, um ggf. nach dem Hinzufügen eines Events nach oben zu scrollen
    val listState = rememberLazyListState()

    // Funktion, die ein Event hinzufügt und automatisch an den Listen-Anfang scrollt
    fun addEventWithScroll(type: String) {
        viewModel.addEvent(type, context)
        scope.launch {
            listState.animateScrollToItem(0)
        }
    }

    // ------------------------------------------------
    // Zeigt eine Snackbar bei aufgetretenem Fehler
    // ------------------------------------------------
    uiState.error?.let { error ->
        val errorMessage = when (error) {
            is EventError.LoadError -> error.message
            is EventError.SaveError -> error.message
            is EventError.ExportError -> error.message
            is EventError.DeleteError -> stringResource(R.string.l_schen_fehlgeschlagen)
        }
        Snackbar(
            action = {
                // Button in der Snackbar, um den Fehler zu "bestätigen" und zu entfernen
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        ) {
            Text(errorMessage)
        }
    }

    // ------------------------------------------------
    // Lade-Indikator (CircularProgressIndicator)
    // ------------------------------------------------
    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    // ------------------------------------------------
    // Info-Dialog mit WebView (app_info.html)
    // ------------------------------------------------
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            // Titel des Dialogs (mit Icon plus Text)
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App-Icon als Vorschaubild
                    Icon(
                        painter = painterResource(id = R.drawable.ic_app_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .padding(end = 8.dp),
                        tint = Color.Unspecified
                    )
                    Text(
                        text = stringResource(R.string.app_info),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            // Text-Inhalt: hier eine WebView, die eine lokale HTML-Seite lädt
            text = {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            // Lädt eine HTML-Datei aus dem res/raw-Verzeichnis
                            loadUrl("file:///android_res/raw/app_info.html")
                        }
                    },
                    modifier = Modifier.height(400.dp)
                )
            },
            // Button, um den Dialog zu schließen
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // ------------------------------------------------
    // Reset-Dialog: Bestätigung zum Löschen aller Events
    // ------------------------------------------------
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.zur_cksetzen_best_tigen)) },
            text = {
                Text(
                    stringResource(R.string.m_chten_sie_wirklich_alle_ereignisse_l_schen_diese_aktion_kann_nicht_r_ckg_ngig_gemacht_werden)
                )
            },
            // Bestätigungs-Button: führt clearEvents aus
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearEvents(context)
                        showResetDialog = false
                    }
                ) {
                    Text(stringResource(R.string.ja_alle_l_schen))
                }
            },
            // Abbrechen-Button
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.abbrechen))
                }
            }
        )
    }

    // ------------------------------------------------
    // Dialog zum Löschen eines einzelnen Events
    // ------------------------------------------------
    eventToDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text(stringResource(R.string.ereignis_l_schen)) },
            text = { Text(stringResource(R.string.m_chten_sie_dieses_ereignis_wirklich_l_schen)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Löschen des ausgewählten Events
                        viewModel.deleteEvent(event, context)
                        eventToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.ja_l_schen))
                }
            },
            dismissButton = {
                TextButton(onClick = { eventToDelete = null }) {
                    Text(stringResource(R.string.abbrechen))
                }
            }
        )
    }

    // ------------------------------------------------
    // Scaffold (Grundlayout mit TopAppBar)
    // ------------------------------------------------
    Scaffold(
        topBar = {
            TopAppBar(
                // Linkes Icon als NavigationIcon: öffnet hier den Info-Dialog
                navigationIcon = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f),
                            tint = Color.Unspecified
                        )
                    }
                },
                title = { Text(stringResource(R.string.ereignisz_hler)) },
                actions = {
                    // Menü-Button, der ein Dropdown-Menü toggelt
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.Menu, stringResource(R.string.menu))
                    }
                    // Dropdown-Menü mit verschiedenen Aktionen
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // CSV-Teilen
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.als_csv_datei_teilen)) },
                            onClick = {
                                scope.launch {
                                    viewModel.shareEvents(context)
                                }
                                showMenu = false
                            }
                        )
                        // Alle Ereignisse löschen (Reset)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.zur_cksetzen)) },
                            onClick = {
                                showResetDialog = true
                                showMenu = false
                            }
                        )
                        // Button-Texte anpassen (Settings)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ereignisarten)) },
                            onClick = {
                                showSettingsScreen = true
                                showMenu = false
                            }
                        )
                        // Info-Dialog aufrufen
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.info)) },
                            onClick = {
                                showInfoDialog = true
                                showMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        // Wenn showSettingsScreen true ist, wird das Einstellungsscreen angezeigt
        if (showSettingsScreen) {
            SettingsScreen(
                onClose = { showSettingsScreen = false },
                viewModel = viewModel
            )
        } else {
            // Ansonsten Inhalt des MainScreens
            Box(modifier = Modifier.fillMaxSize()) {
                // Halbtransparentes Icon als Wasserzeichen im Hintergrund
                Image(
                    painter = painterResource(id = R.drawable.ic_app_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.1f),
                    contentScale = ContentScale.Crop
                )

                // Spalte, in der sich die Buttons und die Ereignisliste befinden
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                ) {
                    // Zwei Buttons, deren Texte vom Nutzer konfiguriert wurden
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Button 1
                        Button(
                            onClick = { addEventWithScroll(event1Text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = event1Text,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Button 2
                        Button(
                            onClick = { addEventWithScroll(event2Text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = event2Text,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Anzeige der Ereignisse (LazyColumn) oder eine leere Ansicht
                    if (sortedEvents.isEmpty()) {
                        // Wenn keine Ereignisse vorhanden sind
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.keine_ereignisse_vorhanden_tippen_sie_auf_einen_der_buttons_um_ein_ereignis_zu_erfassen),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Liste der Ereignisse (LazyColumn) mit "Karten" für jedes Event
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = sortedEvents,
                                key = { "${it.type}-${it.timestamp}" }
                            ) { event ->
                                // Zeitstempel formatieren (z.B. "dd.MM.yyyy HH:mm")
                                val formattedTimestamp by remember(event.timestamp) {
                                    derivedStateOf {
                                        LocalDateTime.parse(
                                            event.timestamp,
                                            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                        ).format(
                                            DateTimeFormatter.ofPattern(
                                                context.getString(R.string.date_time_format)
                                            )
                                        )
                                    }
                                }

                                // Karte für ein einzelnes Ereignis
                                Card(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        // Erste Zeile: Ereignistyp und Lösch-Button
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = event.type,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            IconButton(
                                                onClick = { eventToDelete = event }
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.ereignis_l_schen1)
                                                )
                                            }
                                        }

                                        // Zweite Zeile: Zeitstempel
                                        Text(
                                            text = formattedTimestamp,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// 7) Composable: SettingsScreen
// ----------------------------------------------------
/**
 * Ein Bildschirm, der es erlaubt, die Bezeichnungen (Texte) der beiden Ereignistypen zu ändern.
 * @param onClose Callback-Funktion, um diesen Screen wieder zu schließen.
 * @param viewModel Referenz auf das CounterViewModel, um Änderungen zu speichern.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: CounterViewModel
) {
    val context = LocalContext.current

    // Aktuelle Texte aus dem ViewModel
    val currentEvent1Text by viewModel.event1Text.collectAsState()
    val currentEvent2Text by viewModel.event2Text.collectAsState()

    // Temporäre Zustandsvariablen für die Eingabefelder
    var text1 by remember(currentEvent1Text) { mutableStateOf(currentEvent1Text) }
    var text2 by remember(currentEvent2Text) { mutableStateOf(currentEvent2Text) }

    // Ein eigenes Scaffold für die Einstellungen
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.einstellungen)) },
                navigationIcon = {
                    // IconButton zum Schließen des Settings-Screens
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }
            )
        }
    ) { padding ->
        // Inhalt des Einstellungs-Screens
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Oberer Bereich: Erklärungstext
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ereignisarten_anpassen),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.hier_k_nnen_sie_die_bezeichnungen_f_r_die_beiden_ereignistypen_festlegen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Eingabefelder für die beiden Ereignisarten
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Textfeld für Ereignis 1
                    OutlinedTextField(
                        value = text1,
                        onValueChange = { text1 = it },
                        label = { Text(stringResource(R.string.bezeichnung_f_r_ereignis_1)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        singleLine = true
                    )

                    // Textfeld für Ereignis 2
                    OutlinedTextField(
                        value = text2,
                        onValueChange = { text2 = it },
                        label = { Text(stringResource(R.string.bezeichnung_f_r_ereignis_2)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        singleLine = true
                    )
                }
            }

            // Abstand, damit die Buttons unten ausgerichtet werden können
            Spacer(modifier = Modifier.weight(1f))

            // Buttons "Abbrechen" und "Speichern"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen-Button
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.abbrechen))
                }

                // Speichern-Button
                Button(
                    onClick = {
                        // Änderung im ViewModel speichern
                        viewModel.saveButtonTexts(context, text1, text2)
                        // Schließen des Settings-Screens
                        onClose()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.speichern))
                }
            }
        }
    }
}
