package com.fajar.threadflow.service

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RunnerEvents {
    const val STATE_IDLE = "IDLE"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_PAUSED = "PAUSED"
    const val STATE_STOPPED = "STOPPED"

    private val _state = MutableLiveData<String>(STATE_IDLE)
    val state: LiveData<String> = _state

    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    private val _progress = MutableLiveData<Pair<Int, Int>>(Pair(0, 0))
    val progress: LiveData<Pair<Int, Int>> = _progress

    private val logItems = mutableListOf<String>()

    fun updateState(newState: String) {
        _state.postValue(newState)
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message"
        synchronized(logItems) {
            logItems.add(0, entry)
            if (logItems.size > 200) {
                logItems.removeAt(logItems.lastIndex)
            }
            _logs.postValue(ArrayList(logItems))
        }
    }

    fun updateProgress(current: Int, total: Int) {
        _progress.postValue(Pair(current, total))
    }

    fun clearLogs() {
        synchronized(logItems) {
            logItems.clear()
            _logs.postValue(emptyList())
        }
    }
}
