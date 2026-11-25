package app.phase.stem.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.phase.stem.data.ExportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Sharing and saving rendered mixes / recordings. */
object ExportActions {
    fun mimeOf(format: String) = if (format.equals("mp3", true)) "audio/mpeg" else "audio/wav"

    fun share(context: Context, e: ExportEntity) {
        val f = File(e.path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(e.format)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, e.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share remix").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies into the shared Music/Phase collection (Android 10+, no permission needed). */
    suspend fun saveToMusic(context: Context, e: ExportEntity): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Saving to Music requires Android 10+. Use Share instead.")
        val src = File(e.path)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(e.format))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Phase")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IOException("Cannot write to Music")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        "Music/Phase/${src.name}"
    }
}
