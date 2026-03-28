package dev.cannoli.scorza.util

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private var writer: FileWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(dir: String) {
        val file = File(dir, "debug.log")
        writer = FileWriter(file, false)
        write("DebugLog initialized")
    }

    fun write(msg: String) {
        val w = writer ?: return
        w.appendLine("${fmt.format(Date())} $msg")
        w.flush()
    }
}
