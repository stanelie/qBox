package com.stanelie.gobox

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*

class GoboxViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("gobox_settings", Context.MODE_PRIVATE)

    // Internal mutable holder populated when service connects
    private val _manager = MutableStateFlow<QlabManager?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _manager.value = (binder as QlabService.QlabBinder).getManager()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _manager.value = null
        }
    }

    init {
        val ctx = application.applicationContext
        val intent = Intent(ctx, QlabService::class.java)
        // startForegroundService requires API 26+; plain startService is fine below that
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // Delegated flows — flat-map through _manager so UI always gets a live flow
    val isConnected: StateFlow<Boolean> = _manager
        .flatMapLatest { it?.isConnected ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectionState: StateFlow<ConnectionState> = _manager
        .flatMapLatest { it?.connectionState ?: flowOf(ConnectionState.DISCONNECTED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val connectionError: StateFlow<String?> = _manager
        .flatMapLatest { it?.connectionError ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cues: StateFlow<List<Cue>> = _manager
        .flatMapLatest { it?.cues ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cueLists: StateFlow<List<CueList>> = _manager
        .flatMapLatest { it?.cueLists ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedCueListId: StateFlow<String?> = _manager
        .flatMapLatest { it?.selectedCueListId ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedCueId: StateFlow<String?> = _manager
        .flatMapLatest { it?.selectedCueId ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Settings
    private val _ipAddress = MutableStateFlow(sharedPrefs.getString("ip", "192.168.1.100") ?: "192.168.1.100")
    val ipAddress = _ipAddress.asStateFlow()

    private val _port = MutableStateFlow(sharedPrefs.getInt("port", 53000))
    val port = _port.asStateFlow()

    private val _password = MutableStateFlow(sharedPrefs.getString("password", "") ?: "")
    val password = _password.asStateFlow()

    // Group filter
    private val _isGroupFilterEnabled = MutableStateFlow(false)
    val isGroupFilterEnabled = _isGroupFilterEnabled.asStateFlow()

    val filteredCues: StateFlow<List<Cue>> = cues
        .combine(_isGroupFilterEnabled) { cueList, filterEnabled ->
            if (!filterEnabled) {
                cueList
            } else {
                val simultaneousGroupIds = cueList
                    .filter { it.isGroup && it.groupMode == "simultaneous" }
                    .map { it.id }
                    .toSet()
                val hiddenIds = mutableSetOf<String>()
                val activeSimultaneousDepths = ArrayDeque<Int>()
                for (cue in cueList) {
                    while (activeSimultaneousDepths.isNotEmpty() &&
                        cue.depth <= activeSimultaneousDepths.last()) {
                        activeSimultaneousDepths.removeLast()
                    }
                    if (activeSimultaneousDepths.isNotEmpty()) hiddenIds.add(cue.id)
                    if (cue.id in simultaneousGroupIds) activeSimultaneousDepths.addLast(cue.depth)
                }
                cueList.filter { it.id !in hiddenIds }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playheadIsHidden: StateFlow<Boolean> = filteredCues
        .combine(selectedCueId) { filtered, selId ->
            if (!_isGroupFilterEnabled.value || selId == null) false
            else filtered.none { it.id == selId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleGroupFilter() { _isGroupFilterEnabled.value = !_isGroupFilterEnabled.value }

    fun updateSettings(ip: String, port: Int, password: String) {
        _ipAddress.value = ip
        _port.value = port
        _password.value = password
        sharedPrefs.edit()
            .putString("ip", ip)
            .putInt("port", port)
            .putString("password", password)
            .apply()
    }

    fun connect() { _manager.value?.connect(_ipAddress.value, _port.value, _password.value) }
    fun disconnect() { _manager.value?.disconnect() }
    fun toggleConnection() { if (isConnected.value) disconnect() else connect() }
    fun getMasterLevel(cueId: String, callback: (Float) -> Unit) {
        _manager.value?.getMasterLevel(cueId, callback)
    }
    fun setMasterLevel(cueId: String, db: Float) {
        _manager.value?.setMasterLevel(cueId, db)
    }
    fun cancelFaderSends() { _manager.value?.cancelFaderSends() }
    fun go() { _manager.value?.go() }
    fun panic() { _manager.value?.panic() }
    fun selectCue(cue: Cue) { _manager.value?.selectCue(cue) }
    fun selectCueList(cueListId: String) { _manager.value?.selectCueList(cueListId) }

    override fun onCleared() {
        getApplication<Application>().unbindService(serviceConnection)
        super.onCleared()
    }
}