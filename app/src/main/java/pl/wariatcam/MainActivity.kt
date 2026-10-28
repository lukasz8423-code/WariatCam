package pl.wariatcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private val cameraPermissionRequest = 1001
    private val updateExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101410.toInt()) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        statusView = TextView(this).apply {
            text = "WariatCam • uruchamianie kamery…"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(28, 22, 28, 22)
            setBackgroundColor(0xB9000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))
        setContentView(root)
        statusView.setOnClickListener { checkForUpdates() }
        statusView.text = "WariatCam • kamera lokalna (test)\\nDotknij tutaj, aby sprawdzić aktualizacje"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest)
        }
    }

    private fun checkForUpdates() {
        statusView.text = "WariatCam • sprawdzam aktualizacje…"
        updateExecutor.execute {
            try {
                val connection = URL("https://api.github.com/repos/lukasz8423-code/WariatCam/releases/latest").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "WariatCam-Android")
                val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                connection.disconnect()
                val tag = release.optString("tag_name")
                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", true)) { apkUrl = asset.optString("browser_download_url"); break }
                }
                runOnUiThread {
                    val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removeSuffix("-debug")
                    statusView.text = "WariatCam • kamera lokalna (test)"
                    if (apkUrl.isNullOrBlank()) {
                        AlertDialog.Builder(this).setTitle("WariatCam").setMessage("Nie znaleziono APK w najnowszym wydaniu.").setPositiveButton("OK", null).show()
                    } else if (tag.removePrefix("v") == current) {
                        AlertDialog.Builder(this).setTitle("Aplikacja jest aktualna").setMessage("Masz wersję $current.").setPositiveButton("OK", null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("Dostępna aktualizacja: $tag")
                            .setMessage("Naciśnij Pobierz APK. Po pobraniu otwórz plik z powiadomienia lub folderu Pobrane i zatwierdź aktualizację.")
                            .setNegativeButton("Później", null)
                            .setPositiveButton("Pobierz APK") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) }.show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "WariatCam • kamera lokalna (test)"
                    AlertDialog.Builder(this).setTitle("Nie udało się sprawdzić aktualizacji")
                        .setMessage("Sprawdź internet i spróbuj ponownie.\\n" + (e.localizedMessage ?: "Błąd połączenia"))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    override fun onDestroy() {
        updateExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequest && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (::statusView.isInitialized) {
            statusView.text = "WariatCam potrzebuje uprawnienia do kamery."
        }
    }

    private fun startCamera() {
        statusView.text = "WariatCam • kamera lokalna (test)"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (error: Exception) {
                statusView.text = "Nie udało się uruchomić kamery: ${error.localizedMessage ?: "błąd"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
