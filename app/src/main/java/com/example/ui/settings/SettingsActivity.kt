package com.example.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.AccessibilityHelperService
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyraSettingsTheme {
                SettingsScreen(
                    onBack = { finish() },
                    onOpenAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE) }

    // State Variables
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("api_key", "") ?: "") }
    var userName by remember { mutableStateOf(sharedPrefs.getString("user_name", "Sir") ?: "Sir") }
    
    var selectedModel by remember {
        mutableStateOf(sharedPrefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025") ?: "models/gemini-2.5-flash-native-audio-preview-12-2025")
    }
    var selectedVoice by remember {
        mutableStateOf(sharedPrefs.getString("gemini_voice", "Aoede") ?: "Aoede")
    }
    var selectedPersonality by remember {
        mutableStateOf(sharedPrefs.getString("personality_mode", "GF") ?: "GF")
    }

    // Prime Contacts State
    val primeContacts = remember { mutableStateListOf<JSONObject>() }

    // Init Prime Contacts from JSON array SharedPreferences
    LaunchedEffect(Unit) {
        val jsonStr = sharedPrefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    primeContacts.add(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            // Check legacy key configs
            val legacyName = sharedPrefs.getString("prime_name", "") ?: ""
            val legacyNum = sharedPrefs.getString("prime_number", "") ?: ""
            if (legacyName.isNotBlank() && legacyNum.isNotBlank()) {
                val fakeObj = JSONObject().put("name", legacyName).put("number", legacyNum)
                primeContacts.add(fakeObj)
            }
        }
    }

    // Checking Accessibility service dynamic status
    var isAccessibilityEnabled by remember { mutableStateOf(AccessibilityHelperService.isEnabled(context)) }

    // Poll status updates when activity takes window focus
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = AccessibilityHelperService.isEnabled(context)
    }

    // Dialog trigger
    var showAddContactDialog by remember { mutableStateOf(false) }

    // Model and Voice spinner listings
    val modelsList = listOf(
        "models/gemini-2.5-flash-native-audio-preview-12-2025" to "Native Audio (Human Voice) — DEFAULT",
        "models/gemini-2.0-flash-live-001" to "Flash Live (Fast)",
        "models/gemini-2.5-flash-preview-native-audio-dialog" to "Pro Audio Dialog"
    )

    val voicesList = listOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SETTINGS",
                        color = Color(0xFFFF1744),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF050505))
            )
        },
        containerColor = Color(0xFF050505)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF050505))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. API KEY ENTRY
            item {
                Text("API KEY CONFIGURATION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("Enter Gemini API Key...", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF111116),
                        unfocusedContainerColor = Color(0xFF111116)
                    )
                )
            }

            // 2. NAME CONFIG
            item {
                Text("YOUR NAME (WHAT MYRA SAYS ALOUD)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                TextField(
                    value = userName,
                    onValueChange = { userName = it },
                    placeholder = { Text("What should MYRA call you?", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF111116),
                        unfocusedContainerColor = Color(0xFF111116)
                    )
                )
            }

            // 3. AI MODEL CHOICE
            item {
                Text("AI MODEL SELECTION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                var expandedModel by remember { mutableStateOf(false) }
                val currentModelLabel = modelsList.firstOrNull { it.first == selectedModel }?.second ?: selectedModel

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111116))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .clickable { expandedModel = true }
                        .padding(16.dp)
                ) {
                    Text(currentModelLabel, color = Color.White)
                    DropdownMenu(
                        expanded = expandedModel,
                        onDismissRequest = { expandedModel = false },
                        modifier = Modifier.background(Color(0xFF111116))
                    ) {
                        modelsList.forEach { (modelId, displayLabel) ->
                            DropdownMenuItem(
                                text = { Text(displayLabel, color = Color.White) },
                                onClick = {
                                    selectedModel = modelId
                                    expandedModel = false
                                }
                            )
                        }
                    }
                }
            }

            // 4. CHOOSE VOICE
            item {
                Text("SPEECH VOICE SELECTION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                var expandedVoice by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111116))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .clickable { expandedVoice = true }
                        .padding(16.dp)
                ) {
                    Text(selectedVoice, color = Color.White)
                    DropdownMenu(
                        expanded = expandedVoice,
                        onDismissRequest = { expandedVoice = false },
                        modifier = Modifier.background(Color(0xFF111116))
                    ) {
                        voicesList.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice, color = Color.White) },
                                onClick = {
                                    selectedVoice = voice
                                    expandedVoice = false
                                }
                            )
                        }
                    }
                }
            }

            // 5. PERSONALITY SELECTION
            item {
                Text("PERSONALITY PROFILE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111116), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    listOf(
                        "GF" to "GF Mode 💖 (Hinglish, Loving & expressive)",
                        "Professional" to "Professional Mode 💼 (Formal English, Precise)",
                        "Assistant" to "Assistant Mode 🤖 (Neutral, Friendly)"
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPersonality = mode }
                                .padding(12.dp)
                        ) {
                            RadioButton(
                                selected = (selectedPersonality == mode),
                                onClick = { selectedPersonality = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF1744))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            // 6. ACCESSIBILITY OVERVIEW
            item {
                Text("SYSTEM CLEARANCE STATUS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
                    onClick = onOpenAccessibility
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Assistant helper", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isAccessibilityEnabled) "✅ ACTIVE — Open/close apps fully configured"
                                else "❌ DISABLED — Tap here to configure",
                                color = if (isAccessibilityEnabled) Color(0xFF00E676) else Color(0xFFFF1744),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 7. MULTIPLE SPEED DIAL PRIME CONTACTS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SPEED DIAL PRIME CONTACTS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showAddContactDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Add New", color = Color(0xFFFF1744), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (primeContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0C0C0F), RoundedCornerShape(8.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No contacts configured yet.", color = Color.DarkGray, fontSize = 13.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        primeContacts.forEachIndexed { idx, contact ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0000)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            contact.optString("name"),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            contact.optString("number"),
                                            color = Color.LightGray,
                                            fontSize = 12.sp
                                        )
                                    }
                                    IconButton(onClick = { primeContacts.removeAt(idx) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFFF1744)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 8. ACTIONS: SAVE PREFS
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val editor = sharedPrefs.edit()
                        editor.putString("api_key", apiKey)
                        editor.putString("user_name", userName)
                        editor.putString("gemini_model", selectedModel)
                        editor.putString("gemini_voice", selectedVoice)
                        editor.putString("personality_mode", selectedPersonality)
                        
                        // Convert State List of JSONObjects back to JSON String array
                        val array = JSONArray()
                        primeContacts.forEach { array.put(it) }
                        editor.putString("prime_contacts_json", array.toString())
                        
                        editor.apply()
                        
                        Toast.makeText(context, "Restart app to apply changes!", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFFF1744), Color(0xFFD500F9))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("SAVE CONFIG", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Modal dialog trigger additions
    if (showAddContactDialog) {
        var inputName by remember { mutableStateOf("") }
        var inputNumber by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add Prime Contact", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        placeholder = { Text("Contact Name (e.g. Priya)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = inputNumber,
                        onValueChange = { inputNumber = it },
                        placeholder = { Text("Phone Number (e.g. +91...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputName.isNotBlank() && inputNumber.isNotBlank()) {
                            val contact = JSONObject().put("name", inputName).put("number", inputNumber)
                            primeContacts.add(contact)
                            showAddContactDialog = false
                        }
                    }
                ) {
                    Text("ADD", color = Color(0xFFFF1744))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("CANCEL", color = Color.White)
                }
            },
            containerColor = Color(0xFF111116)
        )
    }
}

@Composable
fun MyraSettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF050505),
            surface = Color(0xFF111116),
            primary = Color(0xFFFF1744)
        ),
        content = content
    )
}
