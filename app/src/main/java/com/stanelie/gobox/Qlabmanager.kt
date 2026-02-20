package com.stanelie.gobox

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    private var socket: DatagramSocket? = null
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
    private var qlabMajorVersion: Int = 4  // default to 4 (poll mode)
    private val pendingLevelCallbacks = mutableMapOf<String, (Float) -> Unit>()

    // Dedicated thread for real-time fader sends — bypasses coroutine scheduler entirely
    private val faderThread = HandlerThread("fader-osc").also { it.start() }
    private val faderHandler = Handler(faderThread.looper)
    // Holds the latest pending level send; atomic so UI thread can update without locking
    private val pendingFaderSend = AtomicReference<Triple<String, String, Float>?>(null)
    private var hasReceivedAnyResponse = false
    private var currentPassword: String? = null

    private var qlabAddress: InetAddress? = null
    private var sendPort = 53000
    private var receivePort = 53001

    private val cueListCache = mutableMapOf<String, List<Cue>>()
    private val cueListOrder = mutableListOf<CueList>()
    private val groupCueListMap = mutableMapOf<String, String>()

    fun connect(ip: String, port: Int, password: String?) {
        Log.d("QlabManager", "connect() called: ip=$ip port=$port password=${if (password.isNullOrEmpty()) "(none)" else "(set)"}")
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _connectionError.value = null
                disconnectInternal()

                withContext(Dispatchers.IO) {
                    Log.d("QlabManager", "Resolving hostname: $ip")
                    qlabAddress = InetAddress.getByName(ip)
                    Log.d("QlabManager", "Resolved to: ${qlabAddress?.hostAddress} | Binding UDP socket on receivePort=$receivePort")
                    socket = DatagramSocket(receivePort)
                    socket?.soTimeout = 0
                    Log.d("QlabManager", "Socket bound OK on port $receivePort | Will send to ${qlabAddress?.hostAddress}:$sendPort")
                }

                _isConnected.value = true
                lastMessageTime = System.currentTimeMillis()

                startReceiving()
                startConnectionTimeoutCheck()
                delay(100)

                if (!password.isNullOrEmpty()) {
                    Log.d("QlabManager", "Sending /connect with password")
                    sendUdpMessage("/connect", listOf(password))
                    delay(200)
                }

                Log.d("QlabManager", "Sending /workspaces")
                sendUdpMessage("/workspaces")
                delay(200)
                Log.d("QlabManager", "Sending /alwaysReply")
                sendUdpMessage("/alwaysReply", listOf(1))

            } catch (e: java.net.UnknownHostException) {
                Log.e("QlabManager", "connect() FAILED — DNS/hostname error: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_NETWORK
                _connectionError.value = "Cannot resolve '$ip'. Check the IP address."
                _isConnected.value = false
            } catch (e: java.net.BindException) {
                Log.e("QlabManager", "connect() FAILED — port $receivePort already in use: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR_NETWORK
                _connectionError.value = "Port $receivePort is already in use. Try restarting the app."
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
            Log.d("QlabManager", "Timeout check started — will fire in 2s if no response")
            delay(2000)
            if (!hasReceivedAnyResponse) {
                Log.e("QlabManager", "TIMEOUT: No response from QLab after 2s. Target was ${qlabAddress?.hostAddress}:$sendPort, listening on $receivePort")
                _connectionState.value = ConnectionState.ERROR_TIMEOUT
                _connectionError.value = "No response from ${qlabAddress?.hostAddress} after 2s. Check that QLab is open, OSC is enabled in Workspace Settings, and the IP is correct."
                disconnectInternal()
            } else {
                Log.d("QlabManager", "Timeout check passed — response was received OK")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTED
        _connectionError.value = null
        heartbeatJob = scope.launch {
            delay(3000)
            while (isActive) {
                if (_isConnected.value && hasReceivedAnyResponse) {
                    sendUdpMessage("/thump")
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

    private fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val s = socket ?: run {
                Log.e("QlabManager", "startReceiving: socket is null, cannot receive!")
                return@launch
            }
            Log.d("QlabManager", "startReceiving: listening on port $receivePort")
            try {
                val buffer = ByteArray(65536)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    withContext(Dispatchers.IO) { s.receive(packet) }
                    Log.d("QlabManager", "Raw packet received: ${packet.length} bytes from ${packet.address?.hostAddress}:${packet.port}")

                    if (!hasReceivedAnyResponse) {
                        hasReceivedAnyResponse = true
                        Log.d("QlabManager", "First response received — starting heartbeat")
                        startHeartbeat()
                    }

                    val data = packet.data.copyOfRange(0, packet.length)
                    onRawPacketReceived(data)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e("QlabManager", "startReceiving EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                    _connectionState.value = ConnectionState.ERROR_NETWORK
                    _connectionError.value = "Network error: ${e.message}. Tap to reconnect."
                    _isConnected.value = false
                } else {
                    Log.d("QlabManager", "startReceiving stopped (coroutine cancelled)")
                }
            }
        }
    }

    private fun onRawPacketReceived(data: ByteArray) {
        lastMessageTime = System.currentTimeMillis()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        try {
            val first = readOscString(bb)
            if (first == "#bundle") {
                bb.getLong()
                while (bb.hasRemaining()) {
                    val size = bb.int
                    if (size <= 0 || size > bb.remaining()) break
                    val msgData = ByteArray(size)
                    bb.get(msgData)
                    parseMessage(ByteBuffer.wrap(msgData).order(ByteOrder.BIG_ENDIAN))
                }
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
        } catch (e: Exception) {}
    }

    private fun handleParsedMessage(address: String, arguments: List<Any>) {
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
                Log.d("QlabManager", "playbackPosition (raw OSC) address=$address arg=$arg qlabMajor=$qlabMajorVersion")
                if (!arg.isNullOrEmpty()) {
                    if (qlabMajorVersion >= 5) {
                        // QLab 5 pushes the unique ID directly
                        _selectedCueId.value = arg
                    } else {
                        if (address.startsWith("/update/")) {
                            // Push notification: arg is the unique ID directly
                            Log.d("QlabManager", "playbackPosition push update: id=$arg")
                            _selectedCueId.value = arg
                        } else {
                            // Poll reply: arg is the cue number — resolve via cache
                            val cueListId = address
                                .substringAfter("cue_id/")
                                .substringBefore("/playbackPosition")
                            val cueList = cueListCache[cueListId]
                            val match = cueList?.firstOrNull { it.number == arg && it.number.isNotEmpty() }
                                ?: cueList?.firstOrNull { it.id == arg }
                            Log.d("QlabManager", "playbackPosition poll raw: cueListId=$cueListId arg=$arg -> id=${match?.id}")
                            if (match != null) _selectedCueId.value = match.id
                        }
                    }
                }
            }
        }
    }

    private fun processJsonResponse(address: String, json: String) {
        try {
            Log.d("QlabManager", "Reply address: $address | data: ${json.take(200)}")
            val reply: QlabReply = gson.fromJson(json, QlabReply::class.java)
            if (reply.status == "denied") {
                _connectionState.value = ConnectionState.ERROR_DENIED
                _connectionError.value = "QLab denied access. Check the password in Settings, or enable OSC access in QLab's Workspace Settings."
                return
            }
            if (reply.status != "ok") return

            when {
                address.contains("/cue_id/") && address.endsWith("/mode") -> {
                    val cueId = address.substringAfter("/cue_id/").substringBefore("/mode")
                    val modeInt = reply.data?.asInt
                    // QLab 4 group modes: 0=sequential, 1=timeline (simultaneous+timeline), 2=random, 3=start all simultaneously
                    val groupMode = when (modeInt) { 3 -> "simultaneous"; 2 -> "random"; 1 -> "timeline"; else -> "sequential" }
                    Log.d("QlabManager", "MODE REPLY cueId=$cueId | raw modeInt=$modeInt | resolved groupMode=$groupMode | full data=${reply.data}")
                    val cueListId = groupCueListMap[cueId] ?: run {
                        Log.w("QlabManager", "MODE REPLY: cueId=$cueId not found in groupCueListMap (keys=${groupCueListMap.keys})")
                        return
                    }
                    val currentList = cueListCache[cueListId] ?: run {
                        Log.w("QlabManager", "MODE REPLY: cueListId=$cueListId not found in cueListCache")
                        return
                    }
                    val updatedList = currentList.map { if (it.id == cueId) it.copy(groupMode = groupMode) else it }
                    cueListCache[cueListId] = updatedList
                    if (_selectedCueListId.value == cueListId) _cues.value = updatedList
                }
                address.contains("/cue_id/") && address.contains("/children") -> {
                    val cueListId = address.substringAfter("/cue_id/").substringBefore("/children")
                    handleCueListChildren(cueListId, reply.data)
                }
                address.contains("sliderLevel") -> {
                    val db = reply.data?.let { if (it.isJsonPrimitive) it.asFloat else null }
                    val cueId = address.substringAfter("cue_id/").substringBefore("/sliderLevel")
                    Log.d("QlabManager", "sliderLevel reply: cueId=$cueId db=$db")
                    if (db != null) {
                        pendingLevelCallbacks.remove(cueId)?.invoke(db)
                    }
                }
                address.contains("playbackPosition") -> {
                    // QLab poll reply: data is the cue NUMBER (e.g. "6")
                    // Resolve to unique ID via cache
                    val pos = reply.data?.let { if (it.isJsonPrimitive) it.asString else null }
                    Log.d("QlabManager", "playbackPosition (JSON poll reply) address=$address pos='$pos'")
                    if (!pos.isNullOrEmpty()) {
                        val cueListId = address
                            .removePrefix("/reply/")
                            .substringAfter("cue_id/")
                            .substringBefore("/playbackPosition")
                        val cueList = cueListCache[cueListId]
                        // Number match first, then fall back to unique ID (for unnumbered cues)
                        val match = cueList?.firstOrNull { it.number == pos && it.number.isNotEmpty() }
                            ?: cueList?.firstOrNull { it.id == pos }
                        Log.d("QlabManager", "playbackPosition lookup: cueListId=$cueListId pos=$pos -> id=${match?.id}")
                        if (match != null) _selectedCueId.value = match.id
                    }
                }
                address.contains("/cueLists") -> {
                    val data = reply.data ?: return
                    if (data.isJsonArray) {
                        val cueListsData: List<CueData> = gson.fromJson(data, object : TypeToken<List<CueData>>() {}.type)
                        val lists = cueListsData.mapNotNull { cl ->
                            val clId = cl.uniqueID ?: return@mapNotNull null
                            CueList(clId, cl.name ?: cl.listName ?: "Cue List")
                        }
                        _cueLists.value = lists
                        if (lists.isNotEmpty() && _selectedCueListId.value == null) _selectedCueListId.value = lists[0].id
                        lists.forEach { sendUdpMessage("/workspace/$currentWorkspaceId/cue_id/${it.id}/children") }
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
                            sendUdpMessage("/workspace/$id/updates", listOf(1))
                            sendUdpMessage("/workspace/$id/cueLists")
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun handleCueListChildren(cueListId: String, data: JsonElement?) {
        if (data == null) return
        val cuesForList = mutableListOf<Cue>()
        if (data.isJsonArray) {
            val cueArray: List<CueData> = gson.fromJson(data, object : TypeToken<List<CueData>>() {}.type)
            cueArray.forEach { flattenCues(it, cuesForList, cueListId = cueListId) }
        }
        cueListCache[cueListId] = cuesForList
        if (_selectedCueListId.value == cueListId) _cues.value = cuesForList

        // Request mode for every group cue so outline colors can be determined
        val wsId = currentWorkspaceId ?: run {
            Log.w("QlabManager", "handleCueListChildren: currentWorkspaceId is null, cannot request modes")
            return
        }
        val groupCues = cuesForList.filter { it.isGroup }
        Log.d("QlabManager", "Requesting mode for ${groupCues.size} group cues in cueList=$cueListId: ${groupCues.map { it.id + "(" + it.name + ")" }}")
        groupCues.forEach { cue ->
            sendUdpMessage("/workspace/$wsId/cue_id/${cue.id}/mode")
        }

        // Request the current playback position for this cue list so the playhead
        // is correct immediately after connecting, without waiting for QLab to move
        Log.d("QlabManager", "Requesting playbackPosition for cueList=$cueListId")
        sendUdpMessage("/workspace/$wsId/cue_id/$cueListId/playbackPosition")
    }

    private fun flattenCues(cueData: CueData, list: MutableList<Cue>, depth: Int = 0, cueListId: String? = null) {
        val id = cueData.uniqueID ?: return
        val type = cueData.type ?: "cue"
        val isGroup = type.contains("Group", true) || type.contains("List", true)
        list.add(Cue(id, cueData.number ?: "", cueData.name?.takeIf { it.isNotEmpty() } ?: cueData.listName?.takeIf { it.isNotEmpty() } ?: cueData.displayName ?: "", type, isGroup, null, depth))
        if (isGroup && cueListId != null) groupCueListMap[id] = cueListId
        cueData.cues?.forEach { flattenCues(it, list, depth + 1, cueListId) }
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

    private fun sendUdpMessage(address: String, arguments: List<Any> = emptyList()) {
        scope.launch {
            val s = socket ?: run {
                Log.e("QlabManager", "sendUdpMessage: socket is null, cannot send $address")
                return@launch
            }
            val addr = qlabAddress ?: run {
                Log.e("QlabManager", "sendUdpMessage: qlabAddress is null, cannot send $address")
                return@launch
            }
            try {
                val oscData = encodeOscMessage(address, arguments)
                Log.d("QlabManager", "Sending OSC: $address args=$arguments (${oscData.size} bytes) -> ${addr.hostAddress}:$sendPort")
                val packet = DatagramPacket(oscData, oscData.size, addr, sendPort)
                withContext(Dispatchers.IO) { s.send(packet) }
            } catch (e: Exception) {
                Log.e("QlabManager", "sendUdpMessage FAILED for $address: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
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

    // --- UI HELPER METHODS ---
    fun getMasterLevel(cueId: String, callback: (Float) -> Unit) {
        val wsId = currentWorkspaceId ?: return
        pendingLevelCallbacks[cueId] = callback
        sendUdpMessage("/workspace/$wsId/cue_id/$cueId/sliderLevel/0")
    }

    fun setMasterLevel(cueId: String, db: Float) {
        val wsId = currentWorkspaceId ?: return
        val s = socket ?: return
        val addr = qlabAddress ?: return
        val address = "/workspace/$wsId/cue_id/$cueId/sliderLevel/0"
        // Always store the latest value
        pendingFaderSend.set(Triple(address, cueId, db))
        // Remove any previously queued send and post a fresh one
        // This ensures only one send is ever queued at a time, always with the latest value
        faderHandler.removeCallbacksAndMessages(null)
        faderHandler.post {
            val latest = pendingFaderSend.getAndSet(null) ?: return@post
            try {
                val oscData = encodeOscMessage(latest.first, listOf(latest.third))
                s.send(DatagramPacket(oscData, oscData.size, addr, sendPort))
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
        val wsId = currentWorkspaceId ?: run { sendUdpMessage("/go"); return }
        sendUdpMessage("/workspace/$wsId/go")

    }
    fun panic() {
        val wsId = currentWorkspaceId ?: run { sendUdpMessage("/panic"); return }
        sendUdpMessage("/workspace/$wsId/panic")
    }


    fun selectCueList(id: String) {
        _selectedCueListId.value = id
        _cues.value = cueListCache[id] ?: emptyList()
        sendUdpMessage("/workspace/$currentWorkspaceId/cue_id/$id/children")
    }

    fun selectCue(cue: Cue) {
        _selectedCueId.value = cue.id
        sendUdpMessage("/workspace/$currentWorkspaceId/select_id/${cue.id}")
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        timeoutCheckJob?.cancel(); heartbeatJob?.cancel(); receiveJob?.cancel()
        withContext(Dispatchers.IO) { socket?.close() }
        socket = null
        qlabAddress = null
        hasReceivedAnyResponse = false
        currentWorkspaceId = null
        pendingFaderSend.set(null)
        qlabMajorVersion = 4
        cueListCache.clear()
        groupCueListMap.clear()
        _cues.value = emptyList()
        _cueLists.value = emptyList()
        _selectedCueListId.value = null
        _selectedCueId.value = null   // reset so reconnect always triggers a state change
        _isConnected.value = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}