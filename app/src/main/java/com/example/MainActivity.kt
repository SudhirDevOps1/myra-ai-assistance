package com.example

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.*
import com.example.model.AppCommand
import com.example.service.CallMonitorService
import com.example.service.MyraOverlayService
import com.example.ui.main.OrbAnimationView
import com.example.ui.main.OrbState
import com.example.ui.main.WaveformView
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.settings.SettingsActivity
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "MainActivity"
        val ALL_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
    }

    var geminiClient: GeminiLiveClient? = null
    var audioEngine: AudioEngine? = null

    // For in-call voice routing management
    private var isInCallMode = false
    private var ringingCallerName = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.myra.CALL_ENDED" -> {
                    Log.d(TAG, "Call ended broadcast received. Re-enabling standard voice loop.")
                    isInCallMode = false
                    ringingCallerName = ""
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Reg intent filters
        val filter = IntentFilter().apply {
            addAction("com.myra.CALL_ENDED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Request execution permissions all-at-once
        requestMultiplePermissions()

        setContent {
            MyApplicationTheme {
                MainAppScreen(
                    geminiClientInit = { setupGeminiAndAudio() },
                    onDisposeClient = { releaseEngines() },
                    onLaunchSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    isInCallModeState = isInCallMode,
                    callerNameState = ringingCallerName,
                    onAcceptAction = { acceptIncomingRingingCall() },
                    onRejectAction = { rejectIncomingRingingCall() }
                )
            }
        }

        // Check overlay start flags or bootstrap overlay if service starts
        startSystemServices()
        
        // Handle incoming calls from background intents
        handleIncomingTriggerIntent(intent)
    }

    private fun requestMultiplePermissions() {
        val nonGranted = ALL_PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (nonGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, nonGranted.toTypedArray(), 101)
        }
    }

    private fun startSystemServices() {
        try {
            val callServiceIntent = Intent(this, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(callServiceIntent)
            } else {
                startService(callServiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error spawning CallMonitorService: ${e.message}")
        }
    }

    private fun setupGeminiAndAudio() {
        if (geminiClient != null) return
        
        geminiClient = GeminiLiveClient(this)
        audioEngine = AudioEngine(this)

        // Connect flows automatically inside main UI side-effects
        geminiClient?.connect()
        audioEngine?.startRecording()
        audioEngine?.startPlayback()
    }

    private fun releaseEngines() {
        geminiClient?.disconnect()
        geminiClient = null

        audioEngine?.release()
        audioEngine = null
    }

    private fun acceptIncomingRingingCall() {
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecom.acceptRingingCall()
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Accept permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rejectIncomingRingingCall() {
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "End permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTriggerIntent(intent)
    }

    private fun handleIncomingTriggerIntent(intent: Intent?) {
        if (intent == null) return
        val caller = intent.getStringExtra("CALLER_NAME")
        if (!caller.isNullOrBlank()) {
            Log.d(TAG, "New incoming trigger for announcement: $caller")
            ringingCallerName = caller
            isInCallMode = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        releaseEngines()
    }
}

// Data class representation for beautiful UI logs
data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainAppScreen(
    geminiClientInit: () -> Unit,
    onDisposeClient: () -> Unit,
    onLaunchSettings: () -> Unit,
    isInCallModeState: Boolean,
    callerNameState: String,
    onAcceptAction: () -> Unit,
    onRejectAction: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Retrieve Shared Preferences
    val sharedPrefs = remember { context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE) }
    val myraName = remember { sharedPrefs.getString("user_name", "Sir") ?: "Sir" }
    val personality = remember { sharedPrefs.getString("personality_mode", "GF") ?: "GF" }

    // Init Engine
    DisposableEffect(Unit) {
        geminiClientInit()
        onDispose {
            onDisposeClient()
        }
    }

    // UI Status Monitors
    var batteryCapacity by remember { mutableStateOf(getBatteryPercentage(context)) }
    var ramUsage by remember { mutableStateOf(getRamUsage(context)) }
    var currentTime by remember { mutableStateOf(getFormattedTime()) }

    // Poll updates every 15 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            batteryCapacity = getBatteryPercentage(context)
            ramUsage = getRamUsage(context)
            currentTime = getFormattedTime()
        }
    }

    // Interactive states
    var orbState by remember { mutableStateOf(OrbState.IDLE) }
    var micAmplitude by remember { mutableStateOf(0.01f) }
    var statusMessage by remember { mutableStateOf("Tap karke bolo 💬") }
    var isMuted by remember { mutableStateOf(false) }

    // Voice transcript states
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    var inputBuffer by remember { mutableStateOf("") }
    var outputBuffer by remember { mutableStateOf("") }
    var currentCommandLog by remember { mutableStateOf<String?>(null) }

    // Action overlay animation
    var highlightOverlayAlpha by remember { mutableStateOf(0f) }

    // Hook listeners when engines are active
    val mMainActivity = (context as? MainActivity)
    val client = mMainActivity?.let {
        val cl = it.geminiClient
        val engine = it.audioEngine

        // Bind Audio Mic feed directly to WebSocket
        LaunchedEffect(engine) {
            engine?.onMicData?.collectLatest { pcmBytes ->
                cl?.sendMicAudio(pcmBytes)
            }
        }

        // Bind speaking notifications (speaking -> purple Orb | listening -> red/magenta Orb)
        LaunchedEffect(engine) {
            engine?.onSpeakingStarted?.collectLatest { speaking ->
                if (speaking) {
                    orbState = OrbState.SPEAKING
                    statusMessage = "Bol rahi hoon..."
                    highlightOverlayAlpha = 0.08f
                } else {
                    orbState = OrbState.LISTENING
                    statusMessage = "Sun rahi hoon..."
                    highlightOverlayAlpha = 0f
                }
            }
        }

        // Bind dynamic live amplitude calculations
        LaunchedEffect(engine) {
            engine?.amplitude?.collectLatest { amp ->
                micAmplitude = amp
            }
        }

        // Bind WebSocket connection status updates
        LaunchedEffect(cl) {
            cl?.connectionState?.collectLatest { state ->
                when (state) {
                    is GeminiLiveClient.ConnectionState.Connecting -> {
                        orbState = OrbState.THINKING
                        statusMessage = "Connecting to MYRA..."
                    }
                    is GeminiLiveClient.ConnectionState.Connected -> {
                        orbState = OrbState.IDLE
                        statusMessage = "Connected successfully!"
                        // Custom startup greeting based on personality config
                        delay(600)
                        val greeting = when (personality) {
                            "GF" -> "Hey $myraName! Main aa gayi hoon. Kya help chahiye tumhe? ❤️"
                            "Professional" -> "Good day $myraName. MYRA is online and ready to assist you."
                            else -> "Hello $myraName! Main MYRA hoon. Kaise help karun aapki? 😊"
                        }
                        cl.sendTextMessage(greeting)
                    }
                    is GeminiLiveClient.ConnectionState.Disconnected -> {
                        orbState = OrbState.THINKING
                        statusMessage = "Disconnected."
                    }
                    is GeminiLiveClient.ConnectionState.Error -> {
                        orbState = OrbState.THINKING
                        statusMessage = state.message
                    }
                }
            }
        }

        // Bind WebSocket message parsers
        LaunchedEffect(cl) {
            cl?.outputTranscriptSegment?.collectLatest { txt ->
                outputBuffer += txt
            }
        }

        LaunchedEffect(cl) {
            cl?.inputTranscriptSegment?.collectLatest { txt ->
                inputBuffer += txt
            }
        }

        // Handle turn-complete transitions, executing commands
        LaunchedEffect(cl) {
            cl?.turnComplete?.collectLatest {
                val userTxt = inputBuffer.trim()
                val clientTxt = outputBuffer.trim()

                if (userTxt.isNotBlank()) {
                    chatMessages.add(ChatMessage(userTxt, isUser = true))
                    
                    // Parse text input logic for device actions
                    val parsedCommand = CommandParser.parse(userTxt)
                    if (parsedCommand != null) {
                        viewModel.executeCommand(parsedCommand)
                    }
                }

                if (clientTxt.isNotBlank()) {
                    // Deduplicate identical last sentences
                    val lastMessageObj = chatMessages.lastOrNull { !it.isUser }
                    if (lastMessageObj == null || lastMessageObj.text != clientTxt) {
                        chatMessages.add(ChatMessage(clientTxt, isUser = false))
                    }
                }

                // Clean buffer states
                inputBuffer = ""
                outputBuffer = ""
            }
        }

        cl
    }

    // Monitor engine Command outcomes to declare inside chat lists too
    LaunchedEffect(Unit) {
        viewModel.executionStatus.collectLatest { log ->
            currentCommandLog = log
        }
    }

    LaunchedEffect(Unit) {
        viewModel.commandResult.observe(context as ComponentActivity) { ans ->
            if (!ans.isNullOrBlank()) {
                currentCommandLog = null
                chatMessages.add(ChatMessage("🔧 Action Update: $ans", isUser = false))
                // Read confirmation message via assistant aloud
                client?.sendTextMessage("Task update, Sir: $ans")
            }
        }
    }

    // Trigger Ring Announcement
    if (isInCallModeState && callerNameState.isNotBlank()) {
        LaunchedEffect(callerNameState) {
            val alertText = "Sir, $callerNameState ka call aa raha hai. Uthau ya reject karu?"
            client?.sendTextMessage(alertText)
            
            // Allow voice command windows to listen for "uthao" or "reject"
            delay(4500)
            statusMessage = "Listen to answer order..."
            
            // Wait for user word patterns to match call toggles
            // Simple timer checks user transcription stream
        }
    }

    // Pulse action fade animation
    val alphaAnim by animateFloatAsState(
        targetValue = highlightOverlayAlpha,
        animationSpec = tween(if (highlightOverlayAlpha > 0f) 300 else 500, easing = LinearOutSlowInEasing),
        label = "HighlightPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
    ) {
        // Red system glows behind layout
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaAnim)
                .background(Color(0xFFFF1744))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // TOP BAR MONITOR: Battery, Ram, Time, settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔋 $batteryCapacity%",
                        color = Color(0xFFFF6D6D),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "RAM: $ramUsage",
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "MYRA",
                        color = Color(0xFFFF1744),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        letterSpacing = 3.sp
                    )
                    Text(
                        "AI COMPANION",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        letterSpacing = 1.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentTime,
                        color = Color(0xFFFF6D6D),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.LightGray,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onLaunchSettings() }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.15f))

            // CENTER: Pulse Orb, Waveform and Tap indicators
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f),
                contentAlignment = Alignment.Center
            ) {
                OrbAnimationView(
                    state = orbState,
                    amplitude = micAmplitude,
                    modifier = Modifier.size(250.dp)
                )

                // Render micro bar waveform in center overlapping
                Box(
                    modifier = Modifier
                        .size(170.dp, 35.dp)
                        .align(Alignment.Center)
                ) {
                    WaveformView(
                        amplitude = micAmplitude,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Status notification text
            Text(
                text = statusMessage,
                color = Color.LightGray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            if (currentCommandLog != null) {
                Text(
                    text = currentCommandLog!!,
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(0.1f))

            // BOTTOM PANEL: Running scrolling transcript RecyclerView mapping and mic button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.35f)
                    .background(Color(0xFF0A0A0F))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .border(1.dp, Color.DarkGray.copy(alpha = 0.2f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(chatMessages.size) {
                        if (chatMessages.isNotEmpty()) {
                            listState.animateScrollToItem(chatMessages.size - 1)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chatMessages) { message ->
                            ChatBubbleItem(message)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Voice and interrupt control elements
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Oval Tap Mic button with beautiful border glows
                        Button(
                            onClick = {
                                isMuted = !isMuted
                                mMainActivity?.audioEngine?.setMuted(isMuted)
                                statusMessage = if (isMuted) "Muted — Speak paused 🚫" else "Tap karke bolo 💬"
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .border(1.5.dp, Color(0xFFFF1744), RoundedCornerShape(26.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF151520))
                        ) {
                            Text(
                                text = if (isMuted) "🔴 TAP TO UNMUTE" else "🎤 LISTENING LIVE",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Long Tap/Hold Interrupt trigger
                        Button(
                            onClick = {
                                client?.sendInterrupt()
                                mMainActivity?.audioEngine?.clearPlaybackQueue()
                                statusMessage = "Interrupt ordered!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF260005)),
                            modifier = Modifier.border(1.dp, Color.DarkGray, CircleShape)
                        ) {
                            Text("🛑 INTERRUPT", color = Color(0xFFFF1744), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Long-press Interrupt clears playback tracks instantly.",
                        color = Color.DarkGray,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubbleItem(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val bubbleColor = if (message.isUser) Color(0xFF26050C) else Color(0xFF15151A)
        val borderColor = if (message.isUser) Color(0xFFFF1744) else Color(0xFF303038)
        
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isUser) 12.dp else 0.dp,
                        bottomEnd = if (message.isUser) 0.dp else 12.dp
                    )
                )
                .background(bubbleColor)
                .border(
                    1.dp, borderColor,
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isUser) 12.dp else 0.dp,
                        bottomEnd = if (message.isUser) 0.dp else 12.dp
                    )
                )
                .padding(10.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.isUser) Color.White else Color.LightGray,
                fontSize = 13.sp
            )
        }
    }
}

// System Helpers
private fun getBatteryPercentage(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

private fun getRamUsage(context: Context): String {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalGb = memoryInfo.totalMem / (1024f * 1024f * 1024f)
    val availGb = memoryInfo.availMem / (1024f * 1024f * 1024f)
    val usedGb = totalGb - availGb
    return String.format(Locale.US, "%.1f/%.1f GB", usedGb, totalGb)
}

private fun getFormattedTime(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date())
}
