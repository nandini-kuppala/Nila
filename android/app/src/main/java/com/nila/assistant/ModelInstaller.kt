package com.nila.assistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.installerStore by preferencesDataStore("model_installer")

/**
 * Fetches the optional language model, once, if the user asks for it.
 *
 * This is the only outbound request the app can make, and it exists because the
 * alternative was worse in both directions: a 547 MB asset inside the APK, or a
 * shell script the user has to run over adb and re-run after every uninstall.
 *
 * It is opt-in, it is a single button, and everything the app does works without
 * it -- answers are retrieved and sourced either way, and the model only ever
 * rephrases them. Nothing is sent: this is a download, and the URL is a constant
 * in this file rather than anything derived from user data.
 *
 * [DownloadManager] does the work rather than a hand-rolled fetch, because it
 * survives the app being killed, resumes across a dropped connection, restricts
 * itself to Wi-Fi, and puts a progress notification in the shade for free.
 */
class ModelInstaller(
    private val context: Context,
    private val model: Model = Model.PHRASING,
) {

    /**
     * The two optional downloads, each earning its own decision.
     *
     * Neither is needed for the app to work, and they are very different
     * sizes, so they are offered separately rather than as one "enable AI"
     * switch that quietly costs 1.7 GB.
     */
    enum class Model(
        val fileName: String,
        val url: String,
        val approxBytes: Long,
        val title: String,
    ) {
        PHRASING(
            fileName = "qwen2.5-0.5b-instruct.task",
            url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
                "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            approxBytes = 547L * 1024 * 1024,
            title = "Shorter answers",
        ),
        VISION(
            fileName = "fastvlm-0.5b.litertlm",
            url = "https://huggingface.co/litert-community/FastVLM-0.5B/" +
                "resolve/main/FastVLM-0.5B.litertlm",
            approxBytes = 1156L * 1024 * 1024,
            title = "Describe the cot",
        ),
    }

    companion object {
        private const val TAG = "ModelInstaller"

        const val FILE_NAME = "qwen2.5-0.5b-instruct.task"


        private fun downloadKey(model: Model) =
            longPreferencesKey("download_id_${model.name}")
    }

    sealed interface State {
        data object Absent : State
        data class Downloading(val percent: Int) : State
        data object Installed : State
        data class Failed(val reason: String) : State
    }

    private val manager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun target() = File(context.getExternalFilesDir("models"), model.fileName)

    /** Enough of the file to be a model rather than an error page. */
    private val minimumBytes: Long get() = model.approxBytes / 4

    suspend fun state(): State {
        val file = target()
        if (file.exists() && file.length() > minimumBytes) return State.Installed

        val id = context.installerStore.data.first()[downloadKey(model)]
            ?: return State.Absent
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return State.Absent
            val status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 }
                ?: model.approxBytes
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Installed
                DownloadManager.STATUS_FAILED ->
                    State.Failed("Download failed. Check Wi-Fi and try again.")
                else -> State.Downloading(((soFar * 100) / total).toInt().coerceIn(0, 100))
            }
        }
    }

    /** @return false if a download is already running or the model is present. */
    suspend fun start(): Boolean {
        if (state() !is State.Absent && state() !is State.Failed) return false
        return try {
            target().parentFile?.mkdirs()
            val request = DownloadManager.Request(Uri.parse(model.url))
                .setTitle("Nila: ${model.title}")
                .setDescription("One-time download. The app works without it.")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(target()))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val id = manager.enqueue(request)
            context.installerStore.edit { it[downloadKey(model)] = id }
            Log.i(TAG, "download queued as $id")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start download", t)
            false
        }
    }

    suspend fun cancel() {
        val id = context.installerStore.data.first()[downloadKey(model)] ?: return
        manager.remove(id)
        context.installerStore.edit { it.remove(downloadKey(model)) }
        runCatching { target().delete() }
    }

    suspend fun remove() {
        cancel()
        runCatching { target().delete() }
    }
}
