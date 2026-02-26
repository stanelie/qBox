package com.stanelie.gobox

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import android.os.HandlerThread
import android.os.Handler

data class Cue(
    val id: String,
    val number: String,
    val name: String,
    val type: String,
    val isGroup: Boolean = false,
    val groupMode: String? = null,
    val depth: Int = 0
)

data class CueList(
    val id: String,
    val name: String
)

data class QlabReply(
    @SerializedName("status") val status: String,
    @SerializedName("data") val data: JsonElement?,
    @SerializedName("address") val address: String?
)

data class CueData(
    @SerializedName("uniqueID") val uniqueID: String?,
    @SerializedName("number") val number: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("listName") val listName: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("mode") val mode: Int?,
    @SerializedName("cues") val cues: List<CueData>?
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR_TIMEOUT,
    ERROR_DENIED,
    ERROR_NETWORK
}

class QlabManager {

    // TCP replaces UDP. QLab listens on port 53000 for both UDP and TCP OSC.
    // OSC-over-TCP uses a 4-byte big-endian length prefix before each OSC packet.
    private var tcpSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // SLIP framing constants (RFC 1055)
    private val SLIP_END: Byte = 0xC0.toByte()
    private val SLIP_ESC: Byte = 0xDB.toByte()
    private val SLIP_ESC_END: Byte = 0xDC.toByte()
    private val SLIP_ESC_ESC: Byte = 0xDD.toByte()
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var timeoutCheckJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val gson = Gson()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues = _cues.asStateFlow()

    private val _cueLists = MutableStateFlow<List<CueList>>(emptyList())
    val cueLists = _cueLists.asStateFlow()

    private val _selectedCueListId = MutableStateFlow<String?>(null)
    val selectedCueListId = _selectedCueListId.asStateFlow()

    private val _selectedCueId = MutableStateFlow<String?>(null)
    val selectedCueId = _selectedCueId.asStateFlow()

    private var lastMessageTime = 0L
    private var currentWorkspaceId: String? = null
    private var qlabMajorVersion: Int = 4
    private val pendingLevelCallbacks = mutableMapOf<String, (Float) -> Unit>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    // Dedicated thread for real-time fader sends — bypasses coroutine scheduler entirely
    private val faderThread = HandlerThread("fader-osc").also { it.start() }
    private val faderHandler = Handler(faderThread.looper)
    private val pendingFaderSend = AtomicReference<Triple<String, String, Float>?>(null)

    private var hasReceivedAnyResponse = false
    private var currentPassword: String? = null

    private var qlabHost: String = ""
    private val oscPort = 53000  // QLab TCP OSC port

    private val cueListCache = mutableMapOf<String, List<Cue>>()
    private val groupCueListMap = mutableMapOf<String, String>()

    fun connect(ip: String, @Suppress("UNUSED_PARAMETER") port: Int, password: String?) {
        currentPassword = password
        qlabHost = ip
        Log.d("QlabManager", "connect() called: ip=$ip port=$oscPort (TCP) password=${if (password.isNullOrEmpty()) "(none)" else "(set)"}")
        _connectionState.value = ConnectionState.CONNECTING
        _connectionError.value = null
        scope.launch {
            try {
                disconnectInternal(resetState = false)

                withContext(Dispatchers.IO) {
                    Log.d("QlabManager", "Opening TCP socket to $ip:$oscPort")
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, oscPort), 5000)  // 5s connect timeout
                    socket.soTimeout = 0  // blocking reads, no timeout (we use our own heartbeat)
                    socket.tcpNoDelay = true  // low-latency for fader/go commands
                    tcpSocket = socket
                    inputStream = socket.getInputStream().buffered(1024 * 1024)
                    outputStream = socket.getOutputStream()
                    Log.d("QlabManager", "TCP connected to $ip:$oscPort")
                }

                lastMessageTime = System.currentTimeMillis()
                startReceiving()
                startConnectionTimeoutCheck()
                delay(100)

                if (!password.isNullOrEmpty()) {
                    Log.d("QlabManager", "Sending /connect with password")
                    sendOscMessage("/connect", listOf(password))
                    delay(200)
                }

                Log.d("QlabManager", "Sending /workspaces")
                sendOscMessage("/workspaces")

            } catch (e: java.net.UnknownHostException) {
                Log.e("QlabManager", "connect() FAILED — DNS/hostname error: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_NETWORK
                _connectionError.value = "Cannot resolve '$ip'. Check the IP address."
                _isConnected.value = false
            } catch (e: java.net.ConnectException) {
                Log.e("QlabManager", "connect() FAILED — TCP refused: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_NETWORK
                _connectionError.value = "Connection refused at $ip:$oscPort. Ensure QLab OSC is enabled (TCP) in Workspace Settings."
                _isConnected.value = false
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("QlabManager", "connect() FAILED — TCP timeout: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_TIMEOUT
                _connectionError.value = "Timed out connecting to $ip. Check the IP and that QLab is running."
                _isConnected.value = false
            } catch (e: Exception) {
                Log.e("QlabManager", "connect() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_NETWORK
                _connectionError.value = "Connection failed: ${e.message}"
                _isConnected.value = false
            }
        }
    }

    private fun startConnectionTimeoutCheck() {
        timeoutCheckJob?.cancel()
        timeoutCheckJob = scope.launch {
            Log.d("QlabManager", "Timeout check started — will fire in 5s if no response")
            delay(5000)
            if (!hasReceivedAnyResponse) {
                Log.e("QlabManager", "TIMEOUT: No response from QLab after 5s. Target was $qlabHost:$oscPort")
                _connectionState.value = ConnectionState.ERROR_TIMEOUT
                _connectionError.value = "No response from $qlabHost after 5s. Check that QLab is open and TCP OSC is enabled in Workspace Settings."
                disconnectInternal()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        _isConnected.value = true
        _connectionState.value = ConnectionState.CONNECTED
        _connectionError.value = null
        heartbeatJob = scope.launch {
            delay(3000)
            while (isActive) {
                if (_isConnected.value && hasReceivedAnyResponse) {
                    sendOscMessage("/thump")
                    if (System.currentTimeMillis() - lastMessageTime > 15000) {
                        _connectionState.value = ConnectionState.ERROR_NETWORK
                        _connectionError.value = "Lost connection to QLab — no response for 15s. Tap to reconnect."
                        _isConnected.value = false
                    }
                }
                delay(4000L)
            }
        }
    }

    /**
     * TCP receive loop using SLIP framing (RFC 1055).
     * QLab wraps each OSC packet with 0xC0 END bytes.
     * Frame format: [0xC0][OSC data with escaping][0xC0]
     * We read byte-by-byte, accumulating into a buffer until we see a 0xC0 END byte,
     * then dispatch the accumulated (unescaped) bytes as a complete OSC packet.
     */
    private fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val ins = inputStream ?: run {
                Log.e("QlabManager", "startReceiving: inputStream is null")
                return@launch
            }
            Log.d("QlabManager", "startReceiving: SLIP stream reader started")
            try {
                val frameBuffer = mutableListOf<Byte>()
                var escaped = false
                while (isActive) {
                    val b: Int = withContext(Dispatchers.IO) { ins.read() }
                    if (b == -1) throw java.io.EOFException("Stream closed")
                    val byte = b.toByte()

                    when {
                        byte == SLIP_END -> {
                            // END byte signals the boundary of a complete frame
                            if (frameBuffer.isNotEmpty()) {
                                val oscData = frameBuffer.toByteArray()
                                frameBuffer.clear()
                                escaped = false

                                if (!hasReceivedAnyResponse) {
                                    hasReceivedAnyResponse = true
                                    timeoutCheckJob?.cancel()
                                }
                                lastMessageTime = System.currentTimeMillis()
                                Log.d("QlabManager", "SLIP frame received: ${oscData.size} bytes")
                                onRawPacketReceived(oscData)
                            }
                            // else: leading/trailing END byte — ignore
                        }
                        escaped -> {
                            escaped = false
                            when (byte) {
                                SLIP_ESC_END -> frameBuffer.add(SLIP_END)
                                SLIP_ESC_ESC -> frameBuffer.add(SLIP_ESC)
                                else -> {
                                    // Protocol violation — add as-is and continue
                                    Log.w("QlabManager", "SLIP: unexpected escape byte 0x${byte.toInt().and(0xFF).toString(16)}")
                                    frameBuffer.add(byte)
                                }
                            }
                        }
                        byte == SLIP_ESC -> {
                            escaped = true
                        }
                        else -> {
                            frameBuffer.add(byte)
                        }
                    }
                }
            } catch (_: java.io.EOFException) {
                if (isActive) {
                    Log.w("QlabManager", "TCP connection closed by QLab (EOF)")
                    _connectionState.value = ConnectionState.ERROR_NETWORK
                    _connectionError.value = "QLab closed the connection. Tap to reconnect."
                    _isConnected.value = false
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e("QlabManager", "startReceiving EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                    _connectionState.value = ConnectionState.ERROR_NETWORK
                    _connectionError.value = "Network error: ${e.message}. Tap to reconnect."
                    _isConnected.value = false
                }
            }
        }
    }

    private fun onRawPacketReceived(data: ByteArray) {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        try {
            val first = readOscString(bb)
            if (first == "#bundle") {
                Log.d("QlabManager", "Bundle received, total size=${bb.capacity()}")
                bb.getLong() // timetag
                var msgCount = 0
                while (bb.hasRemaining()) {
                    val size = bb.int
                    if (size <= 0 || size > bb.remaining()) {
                        Log.w("QlabManager", "Bundle: bad size=$size remaining=${bb.remaining()} after $msgCount messages")
                        break
                    }
                    val msgData = ByteArray(size)
                    bb.get(msgData)
                    parseMessage(ByteBuffer.wrap(msgData).order(ByteOrder.BIG_ENDIAN))
                    msgCount++
                }
                Log.d("QlabManager", "Bundle parsed: $msgCount messages")
            } else {
                bb.position(0)
                parseMessage(bb)
            }
        } catch (e: Exception) {
            Log.e("QlabManager", "Parse error: ${e.message}")
        }
    }

    private fun parseMessage(bb: ByteBuffer) {
        try {
            val address = readOscString(bb)
            val typeTag = readOscString(bb)
            val args = mutableListOf<Any>()
            if (typeTag.startsWith(",")) {
                for (i in 1 until typeTag.length) {
                    when (typeTag[i]) {
                        's' -> args.add(readOscString(bb))
                        'i' -> if (bb.remaining() >= 4) args.add(bb.int)
                        'f' -> if (bb.remaining() >= 4) args.add(bb.float)
                        'b' -> {
                            val size = bb.int
                            val b = ByteArray(size)
                            bb.get(b)
                            args.add(b)
                            val pad = (4 - (size % 4)) % 4
                            repeat(pad) { if (bb.hasRemaining()) bb.get() }
                        }
                    }
                }
            }
            handleParsedMessage(address, args)
        } catch (e: Exception) {
            Log.e("QlabManager", "parseMessage error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun handleParsedMessage(address: String, arguments: List<Any>) {
        if (address.startsWith("/update/") && address.contains("/cue_id/") && !address.contains("playbackPosition")) {
            pollRunningCues()
            return
        }
        if (arguments.isNotEmpty()) {
            val raw = arguments[0]
            val json = when (raw) {
                is String -> raw
                is ByteArray -> String(raw, Charset.forName("UTF-8"))
                else -> null
            }
            if (json != null && json.trim().startsWith("{")) {
                processJsonResponse(address, json)
            } else if (address.contains("playbackPosition")) {
                val arg = arguments.firstOrNull() as? String
                if (!arg.isNullOrEmpty()) {
                    if (qlabMajorVersion >= 5) {
                        _selectedCueId.value = arg
                    } else {
                        if (address.startsWith("/update/")) {
                            _selectedCueId.value = arg
                        } else {
                            val cueListId = address
                                .substringAfter("cue_id/")
                                .substringBefore("/playbackPosition")
                            val cueList = cueListCache[cueListId]
                            val match = cueList?.firstOrNull { it.number == arg && it.number.isNotEmpty() }
                                ?: cueList?.firstOrNull { it.id == arg }
                            if (match != null) _selectedCueId.value = match.id
                        }
                    }
                }
            }
        }
    }

    private fun processJsonResponse(address: String, json: String) {
        Log.d("QlabManager", "processJson: address=$address json=${json.take(300)}")
        try {
            val reply: QlabReply = gson.fromJson(json, QlabReply::class.java)
            if (reply.status == "denied") {
                _connectionState.value = ConnectionState.ERROR_DENIED
                _connectionError.value = "QLab denied access. Check the password in Settings, or enable OSC access in QLab's Workspace Settings."
                return
            }
            if (reply.status != "ok" && reply.status != "ok_currentpasscode") return

            when {
                address.contains("/cue_id/") && address.endsWith("/mode") -> {
                    val cueId = address.substringAfter("/cue_id/").substringBefore("/mode")
                    val modeInt = reply.data?.asInt
                    val groupMode = when (modeInt) { 3 -> "simultaneous"; 2 -> "random"; 1 -> "timeline"; else -> "sequential" }
                    val cueListId = groupCueListMap[cueId] ?: run {
                        Log.w("QlabManager", "MODE REPLY: cueId=$cueId not found in groupCueListMap")
                        return
                    }
                    val currentList = cueListCache[cueListId] ?: return
                    val updatedList = currentList.map { if (it.id == cueId) it.copy(groupMode = groupMode) else it }
                    cueListCache[cueListId] = updatedList
                    if (_selectedCueListId.value == cueListId) _cues.value = updatedList
                }
                address.contains("/cue_id/") && address.contains("/children") -> {
                    Log.d("QlabManager", "children reply: address=$address")
                    val cueListId = address.substringAfter("/cue_id/").substringBefore("/children")
                    handleCueListChildren(cueListId, reply.data)
                }
                address.contains("runningOrPausedCues") -> {
                    val running = reply.data?.isJsonArray == true && reply.data.asJsonArray.size() > 0
                    _isRunning.value = running
                    if (running) {
                        scope.launch {
                            delay(300)
                            pollRunningCues()
                        }
                    }
                }
                address.contains("sliderLevel") -> {
                    val db = reply.data?.let { if (it.isJsonPrimitive) it.asFloat else null }
                    val cueId = address.substringAfter("cue_id/").substringBefore("/sliderLevel")
                    if (db != null) {
                        pendingLevelCallbacks.remove(cueId)?.invoke(db)
                    }
                }
                address.contains("playbackPosition") -> {
                    val pos = reply.data?.let { if (it.isJsonPrimitive) it.asString else null }
                    if (!pos.isNullOrEmpty()) {
                        val cueListId = address
                            .removePrefix("/reply/")
                            .substringAfter("cue_id/")
                            .substringBefore("/playbackPosition")
                        val cueList = cueListCache[cueListId]
                        val match = cueList?.firstOrNull { it.number == pos && it.number.isNotEmpty() }
                            ?: cueList?.firstOrNull { it.id == pos }
                        if (match != null) _selectedCueId.value = match.id
                    }
                }
                address.contains("/connect") && address.contains("/workspace/") -> {
                    val wsId = currentWorkspaceId ?: return
                    Log.d("QlabManager", "Workspace connect reply: status=${reply.status}")
                    startHeartbeat()
                    sendOscMessage("/alwaysReply", listOf(1))
                    sendOscMessage("/workspace/$wsId/updates", listOf(1))
                    // Both QLab 4 and 5 support /cueLists.
                    // QLab 5 returns the full nested cue tree; QLab 4 returns shallow list objects
                    // with empty .cues arrays, so we follow up with /children per list.
                    sendOscMessage("/workspace/$wsId/cueLists")
                }
                address.matches(Regex(".*/workspace/[^/]+/cueLists$")) -> {
                    val data = reply.data ?: return
                    if (!data.isJsonArray) return
                    val wsId = currentWorkspaceId ?: return
                    val cueListsData: List<CueData> = gson.fromJson(data, object : TypeToken<List<CueData>>() {}.type)

                    val lists = cueListsData.mapNotNull { cl ->
                        val clId = cl.uniqueID ?: return@mapNotNull null
                        CueList(clId, cl.name ?: cl.listName ?: cl.displayName ?: "Cue List")
                    }
                    if (lists.isEmpty()) return

                    _cueLists.value = lists
                    if (_selectedCueListId.value == null) _selectedCueListId.value = lists[0].id

                    cueListsData.forEach { clData ->
                        val clId = clData.uniqueID ?: return@forEach
                        val nestedCues = clData.cues
                        if (!nestedCues.isNullOrEmpty()) {
                            // QLab 5: full tree in one shot
                            val cuesForList = mutableListOf<Cue>()
                            nestedCues.forEach { flattenCues(it, cuesForList, cueListId = clId) }
                            cueListCache[clId] = cuesForList
                            cuesForList.filter { it.isGroup }.forEach { cue ->
                                sendOscMessage("/workspace/$wsId/cue_id/${cue.id}/mode")
                            }
                            sendOscMessage("/workspace/$wsId/cue_id/$clId/playbackPosition")
                        } else {
                            // QLab 4: shallow — request children separately
                            sendOscMessage("/workspace/$wsId/cue_id/$clId/children")
                        }
                    }

                    // Push cues for selected list if already cached (QLab 5 path)
                    val selId = _selectedCueListId.value
                    if (selId != null && cueListCache.containsKey(selId)) {
                        _cues.value = cueListCache[selId] ?: emptyList()
                    }
                }
                address.contains("workspaces") -> {
                    val data = reply.data
                    if (data != null && data.isJsonArray && data.asJsonArray.size() > 0) {
                        val ws = data.asJsonArray[0].asJsonObject
                        val id = ws.get("uniqueID")?.asString
                        val versionString = ws.get("version")?.asString ?: ""
                        qlabMajorVersion = versionString.substringBefore(".").toIntOrNull() ?: 4
                        Log.d("QlabManager", "QLab version: $versionString -> major=$qlabMajorVersion")
                        if (id != null) {
                            currentWorkspaceId = id
                            val hasPasscode = ws.get("hasPasscode")?.asBoolean ?: false
                            Log.d("QlabManager", "Workspace found: id=$id hasPasscode=$hasPasscode")
                            val wsPassword = currentPassword
                            if (hasPasscode && !wsPassword.isNullOrEmpty()) {
                                sendOscMessage("/workspace/$id/connect", listOf(wsPassword))
                            } else {
                                sendOscMessage("/workspace/$id/connect")
                            }
                        }
                    } else {
                        _connectionState.value = ConnectionState.ERROR_DENIED
                        _connectionError.value = "QLab denied access or no workspace is open. Enable OSC access in QLab's Workspace Settings."
                        scope.launch { disconnectInternal() }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("QlabManager", "processJsonResponse error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun handleCueListChildren(cueListId: String, data: JsonElement?) {
        if (data == null) return
        val cuesForList = mutableListOf<Cue>()
        if (data.isJsonArray) {
            val cueArray: List<CueData> = gson.fromJson(data, object : TypeToken<List<CueData>>() {}.type)
            cueArray.forEach { flattenCues(it, cuesForList, cueListId = cueListId) }
        }
        cueListCache[cueListId] = cuesForList
        // If no cue list is selected yet (QLab 4: shallow reply may not have arrived),
        // select this one so cues are visible immediately
        if (_selectedCueListId.value == null) _selectedCueListId.value = cueListId
        if (_selectedCueListId.value == cueListId) _cues.value = cuesForList

        val wsId = currentWorkspaceId ?: return
        cuesForList.filter { it.isGroup }.forEach { cue ->
            sendOscMessage("/workspace/$wsId/cue_id/${cue.id}/mode")
        }
        sendOscMessage("/workspace/$wsId/cue_id/$cueListId/playbackPosition")
    }

    private fun flattenCues(cueData: CueData, list: MutableList<Cue>, depth: Int = 0, cueListId: String? = null) {
        val id = cueData.uniqueID ?: return
        val type = cueData.type ?: "cue"
        // "Group" cues are containers within a cue list — recurse into them.
        // "Cue List" type means a separate cue list object — treat as a leaf (don't bleed its
        // children into the current list's cache).
        val isGroup = type.equals("Group", ignoreCase = true)
        val isCueList = type.contains("List", ignoreCase = true) // "Cue List", "Cart", etc.
        list.add(Cue(
            id,
            cueData.number ?: "",
            cueData.name?.takeIf { it.isNotEmpty() }
                ?: cueData.listName?.takeIf { it.isNotEmpty() }
                ?: cueData.displayName ?: "",
            type,
            isGroup || isCueList,  // still shown as a group visually
            null,
            depth
        ))
        if ((isGroup || isCueList) && cueListId != null) groupCueListMap[id] = cueListId
        // Only recurse into true Group cues — not into nested Cue Lists
        if (isGroup) {
            cueData.cues?.forEach { flattenCues(it, list, depth + 1, cueListId) }
        }
    }

    private fun readOscString(bb: ByteBuffer): String {
        val start = bb.position()
        var end = start
        while (end < bb.limit() && bb.get(end) != 0.toByte()) end++
        val length = end - start
        val bytes = ByteArray(length)
        bb.get(bytes)
        if (bb.hasRemaining()) bb.get()
        val total = length + 1
        val pad = (4 - (total % 4)) % 4
        repeat(pad) { if (bb.hasRemaining()) bb.get() }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Encode and send an OSC message over TCP using SLIP framing.
     * Frame format: [0xC0][escaped OSC data][0xC0]
     * Thread-safe: output stream writes are synchronized.
     */
    private fun sendOscMessage(address: String, arguments: List<Any> = emptyList()) {
        scope.launch {
            val os = outputStream ?: run {
                Log.e("QlabManager", "sendOscMessage: outputStream is null, cannot send $address")
                return@launch
            }
            try {
                val frame = buildSlipFrame(encodeOscMessage(address, arguments))
                withContext(Dispatchers.IO) {
                    synchronized(os) { os.write(frame); os.flush() }
                }
                Log.d("QlabManager", "TCP SLIP sent: $address (${frame.size} bytes framed)")
            } catch (e: Exception) {
                Log.e("QlabManager", "sendOscMessage FAILED for $address: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /** Wrap raw OSC bytes in SLIP framing: END + escaped data + END */
    private fun buildSlipFrame(oscData: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(SLIP_END)
        for (b in oscData) {
            when (b) {
                SLIP_END -> { out.add(SLIP_ESC); out.add(SLIP_ESC_END) }
                SLIP_ESC -> { out.add(SLIP_ESC); out.add(SLIP_ESC_ESC) }
                else -> out.add(b)
            }
        }
        out.add(SLIP_END)
        return out.toByteArray()
    }

    private fun encodeOscMessage(address: String, arguments: List<Any>): ByteArray {
        val buffer = mutableListOf<Byte>()
        fun addOscString(str: String) {
            val bytes = str.toByteArray(Charsets.UTF_8)
            buffer.addAll(bytes.toList()); buffer.add(0)
            repeat((4 - ((bytes.size + 1) % 4)) % 4) { buffer.add(0) }
        }
        addOscString(address)
        val typeTag = StringBuilder(",")
        arguments.forEach { when(it) { is String -> typeTag.append("s"); is Int -> typeTag.append("i"); is Float -> typeTag.append("f") } }
        addOscString(typeTag.toString())
        arguments.forEach { when(it) {
            is String -> addOscString(it)
            is Int -> buffer.addAll(ByteBuffer.allocate(4).putInt(it).array().toList())
            is Float -> buffer.addAll(ByteBuffer.allocate(4).putFloat(it).array().toList())
        }}
        return buffer.toByteArray()
    }

    private fun pollRunningCues() {
        val wsId = currentWorkspaceId ?: return
        sendOscMessage("/workspace/$wsId/runningOrPausedCues")
    }

    fun getMasterLevel(cueId: String, callback: (Float) -> Unit) {
        val wsId = currentWorkspaceId ?: return
        pendingLevelCallbacks[cueId] = callback
        sendOscMessage("/workspace/$wsId/cue_id/$cueId/sliderLevel/0")
    }

    /**
     * Real-time fader send — still uses the dedicated HandlerThread but now writes to the TCP stream.
     */
    fun setMasterLevel(cueId: String, db: Float) {
        val wsId = currentWorkspaceId ?: return
        val os = outputStream ?: return
        val address = "/workspace/$wsId/cue_id/$cueId/sliderLevel/0"
        pendingFaderSend.set(Triple(address, cueId, db))
        faderHandler.removeCallbacksAndMessages(null)
        faderHandler.post {
            val latest = pendingFaderSend.getAndSet(null) ?: return@post
            try {
                val frame = buildSlipFrame(encodeOscMessage(latest.first, listOf(latest.third)))
                synchronized(os) { os.write(frame); os.flush() }
            } catch (e: Exception) {
                Log.e("QlabManager", "setMasterLevel send failed: ${e.message}")
            }
        }
    }

    fun cancelFaderSends() {
        faderHandler.removeCallbacksAndMessages(null)
        pendingFaderSend.set(null)
    }

    fun go() {
        val wsId = currentWorkspaceId ?: run { sendOscMessage("/go"); return }
        sendOscMessage("/workspace/$wsId/go")
    }

    fun panic() {
        val wsId = currentWorkspaceId ?: run { sendOscMessage("/panic"); return }
        sendOscMessage("/workspace/$wsId/panic")
    }

    fun selectCueList(id: String) {
        _selectedCueListId.value = id
        _cues.value = cueListCache[id] ?: emptyList()
        // Tell QLab to make this cue list active so subsequent select_id commands
        // land in the correct list context.
        sendOscMessage("/workspace/$currentWorkspaceId/select_id/$id")
        // Also refresh the cache for this list
        sendOscMessage("/workspace/$currentWorkspaceId/cue_id/$id/children")
    }

    fun selectCue(cue: Cue) {
        val ownerList = groupCueListMap[cue.id] ?: _selectedCueListId.value
        Log.d("QlabManager", "selectCue: id=${cue.id} name='${cue.name}' type=${cue.type} ownerList=$ownerList selectedList=${_selectedCueListId.value}")
        _selectedCueId.value = cue.id
        sendOscMessage("/workspace/$currentWorkspaceId/select_id/${cue.id}")
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal(resetState: Boolean = true) {
        timeoutCheckJob?.cancel()
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        withContext(Dispatchers.IO) {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
        tcpSocket = null
        inputStream = null
        outputStream = null
        hasReceivedAnyResponse = false
        currentWorkspaceId = null
        pendingFaderSend.set(null)
        qlabMajorVersion = 4
        cueListCache.clear()
        groupCueListMap.clear()
        _cues.value = emptyList()
        _cueLists.value = emptyList()
        _selectedCueListId.value = null
        _selectedCueId.value = null
        _isRunning.value = false
        _isConnected.value = false
        if (resetState) _connectionState.value = ConnectionState.DISCONNECTED
    }
}