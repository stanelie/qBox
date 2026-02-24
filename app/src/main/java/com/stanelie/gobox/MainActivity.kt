package com.stanelie.gobox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: GoboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() - not supported in this version
        setContent {
            // Using standard MaterialTheme instead of GoboxTheme to avoid missing theme file errors
            MaterialTheme(colors = darkColors()) {
                Surface(color = Color.Black) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

// One entry per ancestor group that is active over this row.
// Layers are ordered outermost-first so drawing goes from widest inset to narrowest.
data class GroupLayer(
    val color: Color,
    val isStart: Boolean,   // this row is the group header
    val isEnd: Boolean,     // this row is the last row of the group
    val isRounded: Boolean  // timeline mode gets rounded corners
)

// Kept for API compatibility – wraps the list of layers
data class CueGroupInfo(
    val layers: List<GroupLayer> = emptyList()
) {
    val isMember: Boolean get() = layers.isNotEmpty()
}

// QLab 4 group mode colors (from official docs):
// sequential  = blue,   square corners  (start first child only)
// timeline    = blue,   rounded corners (start all simultaneously, with timeline editor)
// simultaneous= green,  square corners  (start all simultaneously, classic)
// random      = purple, square corners  (start random child)
fun groupModeColor(groupMode: String?): Color = when (groupMode) {
    "simultaneous" -> Color(0xFF4CAF50) // QLab green  - start all simultaneously
    "timeline"     -> Color(0xFF2196F3) // QLab blue   - timeline mode (rounded)
    "random"       -> Color(0xFF9C27B0) // QLab purple - start random child
    else           -> Color(0xFF2196F3) // QLab blue   - sequential (square)
}

fun computeGroupInfo(cues: List<Cue>): Map<String, CueGroupInfo> {
    if (cues.isEmpty()) return emptyMap()


    // Stack entry: index of the group header cue in the flat list
    val stack = ArrayDeque<Int>()

    // Per-cue: mutable list of layers (outermost first = lowest stack index first)
    // We build layers as (isStart, isEnd) flags keyed by groupHeaderIndex, then assemble at the end.
    data class LayerData(val groupIndex: Int, var isStart: Boolean, var isEnd: Boolean)
    val cueLayerData = Array(cues.size) { mutableListOf<LayerData>() }

    for (i in cues.indices) {
        val cue = cues[i]
        // Close groups that ended before this row
        while (stack.isNotEmpty() && cue.depth <= cues[stack.last()].depth) {
            val closedGroupIndex = stack.removeLast()
            // Mark the previous row as the end of this group
            val endRowIndex = i - 1
            if (endRowIndex >= 0) {
                cueLayerData[endRowIndex].find { it.groupIndex == closedGroupIndex }?.isEnd = true
            }
        }

        // Every active ancestor group contributes a layer to this row
        for (groupIndex in stack) {
            cueLayerData[i].add(LayerData(groupIndex, isStart = false, isEnd = false))
        }

        if (cue.isGroup) {
            // This row is the START of a new group — add its own layer
            cueLayerData[i].add(LayerData(i, isStart = true, isEnd = false))
            stack.addLast(i)
        }
    }

    // Close any groups that ran to the very end of the list
    while (stack.isNotEmpty()) {
        val closedGroupIndex = stack.removeLast()
        cueLayerData[cues.lastIndex].find { it.groupIndex == closedGroupIndex }?.isEnd = true
    }

    // Assemble final result — layers ordered outermost (lowest depth) first
    val result = mutableMapOf<String, CueGroupInfo>()
    for (i in cues.indices) {
        val rawLayers = cueLayerData[i]
        if (rawLayers.isEmpty()) continue
        val layers = rawLayers
            .sortedBy { cues[it.groupIndex].depth } // outermost first
            .map { ld ->
                val groupCue = cues[ld.groupIndex]
                GroupLayer(
                    color     = groupModeColor(groupCue.groupMode),
                    isStart   = ld.isStart,
                    isEnd     = ld.isEnd,
                    isRounded = groupCue.groupMode == "timeline"
                )
            }
        result[cues[i].id] = CueGroupInfo(layers)
    }
    return result
}

@Composable
fun MainScreen(viewModel: GoboxViewModel) {
    val isConnected by viewModel.isConnected.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val cues by viewModel.filteredCues.collectAsState()
    val selectedCueId by viewModel.selectedCueId.collectAsState()
    val isGroupFilterEnabled by viewModel.isGroupFilterEnabled.collectAsState()
    val playheadIsHidden by viewModel.playheadIsHidden.collectAsState()
    val cueLists by viewModel.cueLists.collectAsState()
    val selectedCueListId by viewModel.selectedCueListId.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val savedIp by viewModel.ipAddress.collectAsState()
    val savedPort by viewModel.port.collectAsState()
    val savedPassword by viewModel.password.collectAsState()

    val view = LocalView.current
    LaunchedEffect(isConnected) {
        val window = (view.context as? android.app.Activity)?.window
        if (isConnected) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showCueListDropdown by remember { mutableStateOf(false) }
    val groupInfoMap = remember(cues) { computeGroupInfo(cues) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                // Manual spacer instead of statusBarsPadding
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isConnecting = connectionState == ConnectionState.CONNECTING
                    val connectingTransition = rememberInfiniteTransition(label = "connecting")
                    val connectingAlpha by connectingTransition.animateFloat(
                        initialValue = 1f, targetValue = 0.2f,
                        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
                        label = "connectBlink"
                    )
                    val connectBgColor = when {
                        isConnecting -> Color(0xFFFF9500)
                        isConnected && connectionState == ConnectionState.ERROR_NETWORK -> Color(0xFFFF9500)
                        isConnected -> Color(0xFF34C759)
                        connectionState == ConnectionState.ERROR_DENIED ||
                                connectionState == ConnectionState.ERROR_TIMEOUT ||
                                connectionState == ConnectionState.ERROR_NETWORK -> Color.Red
                        else -> Color.Gray
                    }
                    Button(
                        onClick = { if (!isConnecting) viewModel.toggleConnection() },
                        modifier = Modifier.width(110.dp).alpha(if (isConnecting) connectingAlpha else 1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = connectBgColor)
                    ) {
                        Text(when {
                            isConnecting -> "Connecting..."
                            isConnected && connectionState == ConnectionState.ERROR_NETWORK -> "Disconnect"
                            isConnected -> "Connected"
                            connectionState == ConnectionState.ERROR_DENIED -> "Denied"
                            connectionState == ConnectionState.ERROR_TIMEOUT -> "Timeout"
                            connectionState == ConnectionState.ERROR_NETWORK -> "Lost"
                            else -> "Connect"
                        }, color = Color.White, maxLines = 1, fontSize = 13.sp)
                    }

                    FilterButton(isEnabled = isGroupFilterEnabled, playheadIsHidden = playheadIsHidden, onClick = { viewModel.toggleGroupFilter() })

                    // Running cue indicator — blinks green while any cue is running
                    RunningIndicator(isRunning = isRunning && isConnected)

                    IconButton(onClick = { run { showSettings = true } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
                connectionError?.let { error ->
                    Text(text = error, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (isConnected && cueLists.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { showCueListDropdown = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val selectedList = cueLists.find { it.id == selectedCueListId }
                            Text(text = selectedList?.name ?: "Select Cue List", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = showCueListDropdown, onDismissRequest = { showCueListDropdown = false }) {
                        cueLists.forEach { cueList ->
                            DropdownMenuItem(onClick = {
                                viewModel.selectCueList(cueList.id)
                                showCueListDropdown = false
                            }) {
                                Text(cueList.name)
                            }
                        }
                    }
                }
            }

            // Fader sheet state
            var faderCue by remember { mutableStateOf<Cue?>(null) }
            var faderDb by remember { mutableStateOf(0f) }
            var faderLoaded by remember { mutableStateOf(false) }

            if (faderCue != null) {
                val cue = faderCue!!
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // Left: cue name
                            Text(cue.name.ifEmpty { "—" }, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            // Center: dB value
                            Text(
                                text = if (faderDb <= -60f) "-∞ dB" else "${"%.1f".format(faderDb)} dB",
                                color = Color(0xFF1A7F3C),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            // Right: close button
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelFaderSends(); faderCue = null; faderLoaded = false },
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.Black),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.White, contentColor = Color.Black)
                                ) {
                                    Text("Close", color = Color.Black, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // Horizontal slider: left = -inf, right = +3dB
                        // Map dB (-120..+3) to 0..1 linearly for slider position
                        val sliderVal = if (faderLoaded) ((faderDb.coerceIn(-60f, 3f) + 60f) / 63f) else 0f
                        Slider(
                            value = sliderVal,
                            onValueChange = { v ->
                                val db = (v * 63f - 60f).coerceIn(-60f, 3f)
                                faderDb = db
                                val sendDb = if (db <= -60f) -60f else db
                                viewModel.setMasterLevel(cue.id, sendDb)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF1A7F3C),
                                activeTrackColor = Color(0xFF1A7F3C),
                                inactiveTrackColor = Color(0xFFCCCCCC)
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("-60", color = Color(0xFF666666), fontSize = 11.sp)
                            Text("-20", color = Color(0xFF666666), fontSize = 11.sp)
                            Text("0", color = Color(0xFF666666), fontSize = 11.sp)
                            Text("+3", color = Color(0xFF666666), fontSize = 11.sp)
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (cues.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = if (isConnected) "No cues found" else "Not connected", color = Color.Gray)
                    }
                } else {
                    val listState = rememberLazyListState()

                    // Auto-scroll: keep playhead at or above the midpoint of the visible list.
                    // Uses a coroutine that watches selectedCueId directly, so it always fires
                    // when the playhead changes. Manual scrolling is respected — if the user
                    // has scrolled the playhead above the midpoint we leave it alone.
                    LaunchedEffect(listState) {
                        var lastSelectedId: String? = null
                        // Combine selectedCueId AND cues so we always have a fresh list index
                        snapshotFlow { selectedCueId to cues }.collect { (cueId, cueList) ->
                            if (cueId == null) return@collect
                            // Only scroll when the playhead actually changed
                            if (cueId == lastSelectedId) return@collect
                            lastSelectedId = cueId

                            val index = cueList.indexOfFirst { it.id == cueId }
                            if (index < 0) return@collect

                            // Wait one frame for layout info to be valid
                            delay(50)

                            val layout = listState.layoutInfo
                            val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
                            if (viewportHeight <= 0) {
                                listState.animateScrollToItem(index)
                                return@collect
                            }

                            val visibleItems = layout.visibleItemsInfo
                            val selectedItem = visibleItems.firstOrNull { it.index == index }

                            if (selectedItem == null) {
                                // Off-screen: scroll so item lands at the midpoint
                                val avgItemHeight = if (visibleItems.isNotEmpty())
                                    visibleItems.sumOf { it.size } / visibleItems.size else 80
                                val offset = -(viewportHeight / 2 - avgItemHeight / 2)
                                listState.animateScrollToItem(index, scrollOffset = offset)
                            } else {
                                // On-screen: only scroll if it has drifted below the midpoint
                                val itemMid = selectedItem.offset + selectedItem.size / 2
                                if (itemMid > viewportHeight / 2) {
                                    val offset = -(viewportHeight / 2 - selectedItem.size / 2)
                                    listState.animateScrollToItem(index, scrollOffset = offset)
                                }
                            }
                        }
                    }

                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(cues) { index, cue ->
                            CueItem(
                                cue = cue,
                                index = index,
                                isSelected = cue.id == selectedCueId,
                                groupInfo = groupInfoMap[cue.id],
                                onClick = { viewModel.selectCue(cue) },
                                onLongClick = if (cue.type == "Audio") ({
                                    run { faderCue = cue }
                                    run { faderLoaded = false }
                                    run { faderDb = 0f }
                                    viewModel.getMasterLevel(cue.id) { db ->
                                        run { faderDb = db }
                                        run { faderLoaded = true }
                                    }
                                }) else null
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).height(75.dp)) {
                PressAndReleaseButton(
                    label = "PANIC",
                    normalBg = Color(0xFFFF3B30),
                    normalText = Color.White,
                    pressedBg = Color.White,
                    pressedText = Color(0xFFFF3B30),
                    fontSize = 18,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onRelease = { viewModel.panic() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                PressAndReleaseButton(
                    label = "GO",
                    normalBg = Color(0xFF34C759),
                    normalText = Color.White,
                    pressedBg = Color.White,
                    pressedText = Color(0xFF34C759),
                    fontSize = 24,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                    onRelease = { viewModel.go() }
                )
            }
        }

        if (showSettings) {
            SettingsDialog(
                initialIp = savedIp,
                initialPort = savedPort.toString(),
                initialPassword = savedPassword,
                onDismiss = { showSettings = false },
                onSave = { ip, port, password ->
                    viewModel.updateSettings(ip, port, password)
                    showSettings = false
                }
            )
        }
    }
}

@Composable
fun RunningIndicator(isRunning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "running")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(
                    color = if (isRunning) Color(0xFF34C759).copy(alpha = blinkAlpha) else Color(0xFF3A3A3C),
                    shape = CircleShape
                )
        )
    }
}

@Composable
fun FilterButton(isEnabled: Boolean, playheadIsHidden: Boolean, onClick: () -> Unit) {
    // Blink when the filter is on and the playhead has landed on a hidden cue
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val shouldBlink = isEnabled && playheadIsHidden
    val baseColor = if (isEnabled) Color(0xFF6200EE) else Color.LightGray
    val buttonColor = if (shouldBlink) baseColor.copy(alpha = blinkAlpha) else baseColor

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor = buttonColor)
    ) {
        Text("Collapse", color = Color.White.copy(alpha = if (shouldBlink) blinkAlpha else 1f))
    }
}

@Composable
fun CueItem(cue: Cue, index: Int, isSelected: Boolean, groupInfo: CueGroupInfo?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val rowBackground = when {
        isSelected -> Color(0xFF0A84FF).copy(alpha = 0.5f)
        index % 2 == 0 -> Color(0xFF1E1E1E)
        else -> Color(0xFF2A2A2A)
    }

    Box(modifier = Modifier.fillMaxWidth().background(rowBackground).pointerInput(onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = { onLongClick?.invoke() }
        )
    }.drawWithContent {
        drawContent()
        val layers = groupInfo?.layers ?: return@drawWithContent
        if (layers.isEmpty()) return@drawWithContent

        val stroke = 2.dp.toPx()
        // Outlines start after the cue number column (16dp padding + 50dp number width)
        val numberColumnWidth = 32.dp.toPx()
        val layerGap = 16.dp.toPx()
        val right = size.width - stroke / 2

        layers.forEachIndexed { layerIndex, layer ->
            val left = numberColumnWidth + layerIndex * layerGap + stroke / 2
            val top    = stroke / 2
            val bottom = size.height - stroke / 2
            val r = if (layer.isRounded) 6.dp.toPx() else 0f
            val c = layer.color

            // Left vertical — clipped to avoid overdrawing into cap areas
            val lineTop = if (layer.isStart) top + r else 0f
            val lineBottom = if (layer.isEnd) bottom - r else size.height
            drawLine(c, Offset(left, lineTop), Offset(left, lineBottom), stroke)
            // Right vertical — only outermost layer draws the right border
            if (layerIndex == 0) {
                drawLine(c, Offset(right, 0f), Offset(right, size.height), stroke)
            }

            // Top cap
            if (layer.isStart) {
                if (r > 0f) {
                    val rightCap = if (layerIndex == 0) right else size.width
                    drawLine(c, Offset(left + r, top), Offset(rightCap - r, top), stroke)
                    drawArc(c, 180f, 90f, false,
                        topLeft = Offset(left, top),
                        size = Size(r * 2, r * 2),
                        style = Stroke(stroke))
                    if (layerIndex == 0) {
                        drawArc(c, 270f, 90f, false,
                            topLeft = Offset(right - r * 2, top),
                            size = Size(r * 2, r * 2),
                            style = Stroke(stroke))
                    }
                } else {
                    val rightCap = if (layerIndex == 0) right else size.width
                    drawLine(c, Offset(left, top), Offset(rightCap, top), stroke)
                }
            }

            // Bottom cap
            if (layer.isEnd) {
                if (r > 0f) {
                    val rightCap = if (layerIndex == 0) right else size.width
                    drawLine(c, Offset(left + r, bottom), Offset(rightCap - r, bottom), stroke)
                    drawArc(c, 90f, 90f, false,
                        topLeft = Offset(left, bottom - r * 2),
                        size = Size(r * 2, r * 2),
                        style = Stroke(stroke))
                    if (layerIndex == 0) {
                        drawArc(c, 0f, 90f, false,
                            topLeft = Offset(right - r * 2, bottom - r * 2),
                            size = Size(r * 2, r * 2),
                            style = Stroke(stroke))
                    }
                } else {
                    val rightCap = if (layerIndex == 0) right else size.width
                    drawLine(c, Offset(left, bottom), Offset(rightCap, bottom), stroke)
                }
            }
        }
    }) {
        // Cue number sits outside all group outlines, always at a fixed left position
        Row(modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cue.number, modifier = Modifier.width(32.dp), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // Name and type are indented to stay clear of the nested border lines
            val layerGapDp = 16
            val nestIndent = (groupInfo?.layers?.size ?: 0) * layerGapDp
            val depthIndent = cue.depth * 12
            // 8dp inner padding so text breathes inside the outline
            val innerPad = if (groupInfo != null && groupInfo.isMember) 8 else 0
            Row(modifier = Modifier.weight(1f).padding(start = (nestIndent + depthIndent + innerPad).dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(cue.name.ifEmpty { "—" }, modifier = Modifier.weight(1f), color = Color.White, fontSize = 16.sp)
                Text(cue.type, color = Color.Cyan, fontSize = 10.sp)
            }
        }
    }
}


@Composable
fun PressAndReleaseButton(
    label: String,
    normalBg: Color,
    normalText: Color,
    pressedBg: Color,
    pressedText: Color,
    fontSize: Int,
    modifier: Modifier = Modifier,
    onRelease: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val bg   = if (isPressed) pressedBg   else normalBg
    val text = if (isPressed) pressedText else normalText
    // rememberCoroutineScope() gives us a stable CoroutineScope tied to this composable's
    // lifecycle — we pass it explicitly into the pointerInput lambda to sidestep the
    // restricted-scope limitations of onPress and awaitPointerEventScope.
    val coroutineScope = rememberCoroutineScope()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(bg, RoundedCornerShape(8.dp))
            .pointerInput(onRelease) {
                detectTapGestures(
                    onPress = {
                        val pressTime = System.currentTimeMillis()
                        run { isPressed = true }

                        // Fire action on release immediately — independent of visual
                        val released = tryAwaitRelease()
                        if (released) onRelease()

                        // Visual: keep inverted for at least 150ms from press-down.
                        // Uses the captured coroutineScope so launch/delay are unrestricted.
                        coroutineScope.launch {
                            val elapsed = System.currentTimeMillis() - pressTime
                            val remaining = 150L - elapsed
                            if (remaining > 0) delay(remaining)
                            run { isPressed = false }
                        }
                    }
                )
            }
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, color = text)
    }
}

@Composable
fun SettingsDialog(initialIp: String, initialPort: String, initialPassword: String, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var password by remember { mutableStateOf(initialPassword) }

    Dialog(onDismissRequest = onDismiss) {
        Card(elevation = 8.dp, shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Settings", style = MaterialTheme.typography.h6)
                TextField(value = ip, onValueChange = { ip = it }, label = { Text("IP") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(ip, port.toIntOrNull() ?: 53000, password) }) { Text("Save") }
                }
            }
        }
    }
}