package pl.wariatcam

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ApkDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (id == -1L || id != prefs.getLong("pending_download_id", -2L)) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val successful = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        }
        if (!successful) {
            Toast.makeText(context, "Pobieranie aktualizacji nie powiodło się.", Toast.LENGTH_LONG).show()
            prefs.edit().remove("pending_download_id").apply()
            return
        }

        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            Toast.makeText(context, "APK pobrano, ale nie można otworzyć instalatora. Otwórz plik z folderu Pobrane.", Toast.LENGTH_LONG).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(installIntent)
            prefs.edit().remove("pending_download_id").apply()
        } catch (_: Exception) {
            Toast.makeText(context, "Nie udało się uruchomić instalatora. Otwórz APK z folderu Pobrane.", Toast.LENGTH_LONG).show()
        }
    }
}
