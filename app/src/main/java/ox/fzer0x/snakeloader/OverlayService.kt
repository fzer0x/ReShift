package ox.fzer0x.snakeloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ox.fzer0x.snakeloader.ai.AutonomousAgentEngine
import ox.fzer0x.snakeloader.ui.viewmodels.AutonomousAgentState

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var scriptComposeView: ComposeView? = null
    private var logcatComposeView: ComposeView? = null
    private var fridaComposeView: ComposeView? = null
    private var agentComposeView: ComposeView? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private val textSize = mutableStateOf(10f)
    private val opacity = mutableStateOf(0.8f)
    private val isScriptMinimized = mutableStateOf(false)
    private val isLogcatMinimized = mutableStateOf(false)
    private val isFridaMinimized = mutableStateOf(false)

    companion object {
        const val TYPE_SCRIPT = 0
        const val TYPE_LOGCAT = 1
        const val TYPE_FRIDA = 2
        const val TYPE_AGENT = 3
    }

    private fun createLayoutParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private var scriptOverlayParams = createLayoutParams(50, 100)
    private var logcatOverlayParams = createLayoutParams(50, 450)
    private var fridaOverlayParams = createLayoutParams(50, 800)
    private var agentOverlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        this.x = 0
        this.y = 100
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        val settings = SettingsManager(this)
        textSize.value = settings.overlayTextSize * 0.8f
        opacity.value = settings.overlayOpacity
        
        if (settings.isOverlayEnabled) {
            if (scriptComposeView == null) {
                scriptComposeView = createOverlayView(TYPE_SCRIPT)
                windowManager.addView(scriptComposeView, scriptOverlayParams)
            }
        } else {
            scriptComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; scriptComposeView = null }
        }

        if (settings.isLogcatOverlayEnabled) {
            if (logcatComposeView == null) {
                logcatComposeView = createOverlayView(TYPE_LOGCAT)
                windowManager.addView(logcatComposeView, logcatOverlayParams)
                LogcatReader.start()
            }
        } else {
            logcatComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; logcatComposeView = null }
        }

        if (settings.isFridaOverlayEnabled) {
            if (fridaComposeView == null) {
                fridaComposeView = createOverlayView(TYPE_FRIDA)
                windowManager.addView(fridaComposeView, fridaOverlayParams)
            }
        } else {
            fridaComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; fridaComposeView = null }
        }

        if (agentComposeView == null) {
            agentComposeView = createAgentOverlayView()
            try {
                windowManager.addView(agentComposeView, agentOverlayParams)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error adding agentComposeView: ${e.message}", e)
            }
        } else {
            try {
                windowManager.updateViewLayout(agentComposeView, agentOverlayParams)
            } catch (e: Exception) {}
        }
        
        return START_STICKY
    }

    private fun createOverlayView(type: Int): ComposeView {
        return ComposeView(this).apply {
            val viewModelStore = ViewModelStore()
            val lifecycleOwner = this@OverlayService
            
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = viewModelStore
            })
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                val currentTextSize by textSize
                val currentOpacity by opacity
                val minimizedState = when(type) {
                    TYPE_SCRIPT -> isScriptMinimized
                    TYPE_LOGCAT -> isLogcatMinimized
                    TYPE_FRIDA -> isFridaMinimized
                    else -> isScriptMinimized
                }
                val minimized by minimizedState
                
                val logs = when(type) {
                    TYPE_SCRIPT -> LogManager.logs
                    TYPE_LOGCAT -> LogManager.logcatEntries.filter { it.isScriptRelated }
                    TYPE_FRIDA -> LogManager.fridaLogs
                    else -> emptyList()
                }.toList()

                val listState = rememberLazyListState()
                val density = LocalDensity.current
                
                var width by remember { mutableStateOf(280.dp) }
                var height by remember { mutableStateOf(180.dp) }
                val params = when(type) {
                    TYPE_SCRIPT -> scriptOverlayParams
                    TYPE_LOGCAT -> logcatOverlayParams
                    TYPE_FRIDA -> fridaOverlayParams
                    else -> scriptOverlayParams
                }

                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty() && !minimized) {
                        listState.scrollToItem(0)
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = currentOpacity * 0.8f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .sizeIn(minWidth = 50.dp, minHeight = 40.dp)
                        .then(if (minimized) Modifier.wrapContentSize() else Modifier.size(width, height))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                params.x += dragAmount.x.toInt()
                                params.y += dragAmount.y.toInt()
                                try {
                                    windowManager.updateViewLayout(this@apply, params)
                                } catch (e: Exception) {}
                            }
                        }
                ) {
                    if (minimized) {
                        MinimizedIcon(type, minimizedState)
                    } else {
                        OverlayExpandedView(type, logs, listState, currentTextSize, currentOpacity, minimizedState, width, height, { width = it }, { height = it })
                    }
                }
            }
        }
    }

    @Composable
    private fun MinimizedIcon(type: Int, minimizedState: MutableState<Boolean>) {
        val color = when(type) {
            TYPE_SCRIPT -> Color(0xFF81C784)
            TYPE_LOGCAT -> Color(0xFF64B5F6)
            TYPE_FRIDA -> Color(0xFFFFD54F)
            else -> Color.White
        }
        IconButton(onClick = { minimizedState.value = false }, modifier = Modifier.size(40.dp)) {
            Icon(
                when(type) {
                    TYPE_SCRIPT -> Icons.AutoMirrored.Filled.ListAlt
                    TYPE_LOGCAT -> Icons.Default.BugReport
                    TYPE_FRIDA -> Icons.Default.Terminal
                    else -> Icons.Default.Close
                },
                contentDescription = "Expand", 
                tint = color
            )
        }
    }

    @Composable
    private fun OverlayExpandedView(
        type: Int, 
        logs: List<LogManager.LogEntry>, 
        listState: androidx.compose.foundation.lazy.LazyListState,
        textSize: Float,
        opacity: Float,
        minimizedState: MutableState<Boolean>,
        width: androidx.compose.ui.unit.Dp,
        height: androidx.compose.ui.unit.Dp,
        onWidthChange: (androidx.compose.ui.unit.Dp) -> Unit,
        onHeightChange: (androidx.compose.ui.unit.Dp) -> Unit
    ) {
        val themeColor = when(type) {
            TYPE_SCRIPT -> Color(0xFF81C784)
            TYPE_LOGCAT -> Color(0xFF64B5F6)
            TYPE_FRIDA -> Color(0xFFFFD54F)
            else -> Color.White
        }
        val title = when(type) {
            TYPE_SCRIPT -> "Scripts"
            TYPE_LOGCAT -> "Logcat"
            TYPE_FRIDA -> "Frida CLI"
            else -> ""
        }

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(themeColor.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when(type) {
                        TYPE_SCRIPT -> Icons.Default.Description
                        TYPE_LOGCAT -> Icons.Default.BugReport
                        TYPE_FRIDA -> Icons.Default.Terminal
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    color = themeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { minimizedState.value = true }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = themeColor, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { 
                    val settings = SettingsManager(this@OverlayService)
                    when(type) {
                        TYPE_SCRIPT -> {
                            settings.isOverlayEnabled = false
                            scriptComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; scriptComposeView = null }
                        }
                        TYPE_LOGCAT -> {
                            settings.isLogcatOverlayEnabled = false
                            logcatComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; logcatComposeView = null }
                        }
                        TYPE_FRIDA -> {
                            settings.isFridaOverlayEnabled = false
                            fridaComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {}; fridaComposeView = null }
                        }
                    }
                    if (!settings.isOverlayEnabled && !settings.isLogcatOverlayEnabled && !settings.isFridaOverlayEnabled) {
                        stopSelf()
                    }
                }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = themeColor, modifier = Modifier.size(14.dp))
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = type == TYPE_FRIDA
                ) {
                    items(logs) { log ->
                        OverlayLogItem(log, themeColor, textSize)
                    }
                }

                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onWidthChange(width + with(density) { dragAmount.x.toDp() })
                                onHeightChange(height + with(density) { dragAmount.y.toDp() })
                            }
                        }
                ) {
                    Icon(
                        Icons.Default.OpenInFull, 
                        contentDescription = null, 
                        tint = themeColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp).align(Alignment.Center)
                    )
                }
            }
        }
    }

    @Composable
    private fun OverlayLogItem(log: LogManager.LogEntry, defaultColor: Color, textSize: Float) {
        val levelColor = when (log.level) {
            LogManager.LogLevel.ERROR -> Color(0xFFE57373)
            LogManager.LogLevel.WARN -> Color(0xFFFFB74D)
            LogManager.LogLevel.SUCCESS -> Color(0xFF81C784)
            else -> defaultColor
        }

        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(levelColor, RoundedCornerShape(1.dp))
                        .padding(top = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = log.message,
                    color = if (log.level == LogManager.LogLevel.ERROR) levelColor else Color.White.copy(alpha = 0.9f),
                    fontSize = textSize.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = (textSize * 1.2f).sp
                )
            }
        }
    }

    private fun createAgentOverlayView(): ComposeView {
        return ComposeView(this).apply {
            val viewModelStore = ViewModelStore()
            val lifecycleOwner = this@OverlayService

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = viewModelStore
            })
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                val activeEngine = AutonomousAgentEngine.activeEngineInstance?.get()
                val agentState by activeEngine?.agentState?.collectAsState(AutonomousAgentState.IDLE)
                    ?: remember { mutableStateOf(AutonomousAgentState.IDLE) }
                val currentIteration by activeEngine?.currentIteration?.collectAsState(0)
                    ?: remember { mutableStateOf(0) }

                // Dynamically update window flags depending on state
                LaunchedEffect(agentState) {
                    if (agentState == AutonomousAgentState.WAITING_USER_EVALUATION) {
                        // Make window touchable and focusable so buttons and text field work over target app
                        agentOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        try {
                            windowManager.updateViewLayout(this@apply, agentOverlayParams)
                        } catch (e: Exception) {}
                    } else {
                        // Non-blocking mode when just monitoring/loading
                        agentOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        try {
                            windowManager.updateViewLayout(this@apply, agentOverlayParams)
                        } catch (e: Exception) {}
                    }
                }

                if (agentState != AutonomousAgentState.IDLE) {
                    var showRefinementInput by remember { mutableStateOf(false) }
                    var refinedPromptInput by remember { mutableStateOf("") }

                    Surface(
                        color = Color(0xFF11111B).copy(alpha = 0.95f),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 12.dp,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .wrapContentSize()
                            .padding(8.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    agentOverlayParams.x += dragAmount.x.toInt()
                                    agentOverlayParams.y += dragAmount.y.toInt()
                                    try {
                                        windowManager.updateViewLayout(this@apply, agentOverlayParams)
                                    } catch (e: Exception) {}
                                }
                            }
                    ) {
                        if (agentState == AutonomousAgentState.WAITING_USER_EVALUATION) {
                            Column(
                                modifier = Modifier
                                    .width(320.dp)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Psychology,
                                            contentDescription = null,
                                            tint = Color(0xFF89B4FA),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "ReShift AI Agent",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color.White
                                        )
                                    }
                                    Surface(
                                        color = Color(0xFF313244),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "Versuch #$currentIteration",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFA6ADC8),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "Ziel im Spiel erreicht? Wurde das gewünschte Ergebnis (z.B. Budget/Coins auf 50 Mio erhöht) erzielt?",
                                    fontSize = 12.sp,
                                    color = Color(0xFFCDD6F4),
                                    lineHeight = 16.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val engine = activeEngine ?: AutonomousAgentEngine.activeEngineInstance?.get()
                                            engine?.submitUserEvaluation(goalAchieved = true)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("JA!", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            showRefinementInput = true
                                            agentOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                            try {
                                                windowManager.updateViewLayout(agentComposeView, agentOverlayParams)
                                            } catch (e: Exception) {}
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("NEIN", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (showRefinementInput) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = refinedPromptInput,
                                            onValueChange = { refinedPromptInput = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("Tipp für KI (z.B. 'Hooke AddCoins')", fontSize = 11.sp, color = Color.Gray) },
                                            maxLines = 2,
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White)
                                        )
                                        Button(
                                            onClick = {
                                                val engine = activeEngine ?: AutonomousAgentEngine.activeEngineInstance?.get()
                                                engine?.submitUserEvaluation(goalAchieved = false, refinedPrompt = refinedPromptInput.ifBlank { null })
                                                showRefinementInput = false
                                                refinedPromptInput = ""
                                                agentOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                                try {
                                                    windowManager.updateViewLayout(agentComposeView, agentOverlayParams)
                                                } catch (e: Exception) {}
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Nachjustieren & Erneut versuchen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF89B4FA)
                                )
                                Text(
                                    text = "ReShift Agent: ${agentState.label}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "OverlayServiceChannel",
                "Overlay Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "OverlayServiceChannel")
                .setContentTitle("ReShift Overlay")
                .setContentText("Monitoring system logs...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("ReShift Overlay")
                .setContentText("Monitoring system logs...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scriptComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {} }
        logcatComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {} }
        fridaComposeView?.let { try { windowManager.removeView(it) } catch(e: Exception) {} }
        LogcatReader.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
