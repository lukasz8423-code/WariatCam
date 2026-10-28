package pl.wariatcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var nightButton: Button
    private lateinit var torchButton: Button
    private var camera: Camera? = null
    private var nightModeOn = false
    private var grayscaleOn = false
    private var torchOn = false
    private var lastLuma = 255
    private var previousExposureIndex = 0
    private var cameraProvider: ProcessCameraProvider? = null
    private var nightCameraSelector: CameraSelector? = null
    private val cameraPermissionRequest = 1001
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private var pendingDownloadId = -1L
    private var downloadReceiverRegistered = false

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId == -1L || completedId != pendingDownloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(completedId)
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Pobieranie APK nie powiodło się. Spróbuj ponownie.", Toast.LENGTH_LONG).show() }
                    return
                }
            }
            val apkUri = manager.getUriForDownloadedFile(completedId)
            if (apkUri == null) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Pobrano plik, ale Android nie udostępnił go instalatorowi. Otwórz folder Pobrane.", Toast.LENGTH_LONG).show() }
                return
            }
            runOnUiThread {
                try {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                } catch (e: Exception) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Nie można otworzyć instalatora")
                        .setMessage("Otwórz plik APK z folderu Pobrane. " + (e.localizedMessage ?: ""))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }


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
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xD9000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xD9000000.toInt())
        }
        val menuButton = Button(this).apply {
            text = "☰ MENU"
            setOnClickListener { showMenu() }
        }
        nightButton = Button(this).apply {
            text = "☾ NOCNY: AUTO"
            setOnClickListener { toggleNightMode() }
        }
        torchButton = Button(this).apply {
            text = "LED: WYŁ."
            setOnClickListener { toggleTorch() }
        }
        controls.addView(menuButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(nightButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        controls.addView(torchButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusView.setPadding(dp(16), top + dp(8), dp(16), dp(10))
            controls.setPadding(dp(12), dp(8), dp(12), bottom + dp(8))
            insets
        }

        setContentView(root)
        ContextCompat.registerReceiver(this, downloadCompleteReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        downloadReceiverRegistered = true
        statusView.text = "WariatCam • kamera lokalna (test)"
        statusView.setOnClickListener { checkForUpdates() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMenu() {
        val items = arrayOf(
            if (nightModeOn) "Wyłącz tryb nocny automatyczny" else "Włącz tryb nocny automatyczny",
            if (torchOn) "Wyłącz lampę LED" else "Włącz lampę LED",
            "Sprawdź aktualizacje",
            "Jak działa tryb nocny?"
        )
        AlertDialog.Builder(this)
            .setTitle("WariatCam — menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleNightMode()
                    1 -> toggleTorch()
                    2 -> checkForUpdates()
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Tryb nocny automatyczny")
                        .setMessage("WariatCam analizuje jasność obrazu. Po wykryciu ciemności przełącza podgląd na czarno-biały i zwiększa ekspozycję, jeśli aparat na to pozwala. Lampę LED możesz włączyć osobnym przyciskiem. To nie jest prawdziwa noktowizja IR — telefon nie ma gwarantowanego oświetlacza podczerwieni przy tylnym aparacie.")
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun toggleNightMode() {
        nightModeOn = !nightModeOn
        if (!nightModeOn) {
            setGrayscale(false)
            camera?.let { cam ->
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                cam.cameraControl.setExposureCompensationIndex(previousExposureIndex.coerceIn(range.lower, range.upper))
            }
            nightButton.text = "☾ NOCNY: AUTO"
            statusView.text = "WariatCam • tryb dzienny"
        } else {
            camera?.let { cam -> previousExposureIndex = cam.cameraInfo.exposureState.exposureCompensationIndex }
            nightButton.text = "☾ NOCNY: WŁ."
            applyLowLight(lastLuma)
        }
    }

    private fun setGrayscale(enabled: Boolean) {
        grayscaleOn = enabled
        val paint = if (enabled) Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        } else null
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun applyLowLight(luma: Int) {
        if (!nightModeOn) return
        val shouldBeNight = if (grayscaleOn) luma < 86 else luma < 62
        setGrayscale(shouldBeNight)
        camera?.let { cam ->
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            if (range.lower != range.upper) {
                val target = if (shouldBeNight) minOf(range.upper, maxOf(1, range.upper / 2)) else previousExposureIndex
                cam.cameraControl.setExposureCompensationIndex(target.coerceIn(range.lower, range.upper))
            }
        }
        statusView.text = if (shouldBeNight) "WariatCam • TRYB NOCNY • obraz B/W" else "WariatCam • tryb dzienny • kolor"
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            AlertDialog.Builder(this).setTitle("Lampa niedostępna")
                .setMessage("Ten aparat nie udostępnia lampy LED dla aplikacji.")
                .setPositiveButton("OK", null).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.text = if (torchOn) "LED: WŁ." else "LED: WYŁ."
    }
    private fun downloadApk(apkUrl: String, tag: String) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("WariatCam $tag")
                .setDescription("Pobieranie aktualizacji aplikacji…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "WariatCam-$tag.apk"
                )
            pendingDownloadId = manager.enqueue(request)
            statusView.text = "WariatCam • pobieranie aktualizacji…"
            Toast.makeText(
                this,
                "Pobieranie rozpoczęte. Po zakończeniu stuknij powiadomienie, aby zainstalować APK.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Nie udało się pobrać aktualizacji")
                .setMessage(e.localizedMessage ?: "Sprawdź dostęp do internetu i miejsce w pamięci.")
                .setPositiveButton("OK", null)
                .show()
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
                            .setMessage("Pobiorę plik APK do folderu Pobrane. Po zakończeniu stuknij powiadomienie o pobraniu, aby uruchomić instalator Androida.")
                            .setNegativeButton("Później", null)
                            .setPositiveButton("Pobierz APK") { _, _ -> downloadApk(apkUrl, tag) }.show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "WariatCam • kamera lokalna (test)"
                    AlertDialog.Builder(this).setTitle("Nie udało się sprawdzić aktualizacji")
                        .setMessage("Sprawdź internet i spróbuj ponownie.\n" + (e.localizedMessage ?: "Błąd połączenia"))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    override fun onDestroy() {
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadCompleteReceiver) } catch (_: Exception) {}
            downloadReceiverRegistered = false
        }
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

    private fun bindCamera(selector: CameraSelector) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(updateExecutor) { image ->
            try {
                val buffer = image.planes[0].buffer
                var sum = 0L
                var count = 0
                while (buffer.hasRemaining()) { sum += (buffer.get().toInt() and 0xFF); count++ }
                if (count > 0) {
                    val luma = (sum / count).toInt()
                    lastLuma = luma
                    if (nightModeOn) runOnUiThread { applyLowLight(luma) }
                }
            } catch (_: Exception) {
            } finally { image.close() }
        }
        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
        torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
    }
    private fun startCamera() {
        statusView.text = "WariatCam • kamera lokalna (test)"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                nightCameraSelector = null
                bindCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                nightButton.isEnabled = true
            } catch (error: Exception) {
                statusView.text = "Nie udało się uruchomić kamery: ${error.localizedMessage ?: "błąd"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
