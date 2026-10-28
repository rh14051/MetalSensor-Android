package com.metalsensor.app.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal synchronized CSV logger for calibration analysis.
 *
 * Files are written to the app-specific external directory (no storage permission needed):
 *   /Android/data/com.metalsensor.app/files/logs/log_YYYYMMDD_HHMMSS.csv
 *
 * Columns: timestamp, delta, state, baseline_x/y/z, raw_x/y/z
 */
class CsvLogger(context: Context) {

    private val dir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "logs"
    )

    private var writer: FileWriter? = null
    private var outFile: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    val isRecording: Boolean get() = writer != null
    val lastFile: File? get() = outFile

    /** Starts a new file. Returns the created file, or null on failure. */
    @Synchronized
    fun start(): File? {
        if (writer != null) return outFile
        return try {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "log_$stamp.csv")
            writer = FileWriter(file, false).also {
                it.write("timestamp,delta,state,baseline_x,baseline_y,baseline_z,raw_x,raw_y,raw_z\n")
            }
            outFile = file
            file
        } catch (e: Exception) {
            writer = null
            null
        }
    }

    /** Appends one row. raw may be null when RAW streaming is disabled. */
    @Synchronized
    fun log(delta: Int, state: String, bx: Int, by: Int, bz: Int, raw: IntArray?) {
        val w = writer ?: return
        try {
            val ts = timeFormat.format(Date())
            val r0 = raw?.getOrNull(0)?.toString() ?: ""
            val r1 = raw?.getOrNull(1)?.toString() ?: ""
            val r2 = raw?.getOrNull(2)?.toString() ?: ""
            w.write("$ts,$delta,$state,$bx,$by,$bz,$r0,$r1,$r2\n")
            w.flush()
        } catch (e: Exception) {
            // Ignore transient IO errors; recording simply keeps trying.
        }
    }

    /** Stops recording and returns the file that was written. */
    @Synchronized
    fun stop(): File? {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            // nothing else to do
        }
        writer = null
        return outFile
    }
}
