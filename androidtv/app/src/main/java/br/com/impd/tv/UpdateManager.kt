package br.com.impd.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    /**
     * Points at `version.json` on the `main` branch of the project repository.
     * raw.githubusercontent.com serves it as a plain file over HTTPS with no
     * account, no API key and no rate limit worth worrying about for one check
     * per launch, so publishing a new build is one commit to that file plus an
     * APK attached to a GitHub release.
     *
     * The file looks like:
     * ```json
     * {
     *   "versionCode": 2,
     *   "apkUrl": "https://github.com/gabrielsaimo/IMPD-TV/releases/download/v1.1/impd-tv.apk",
     *   "releaseNotes": "Correções de estabilidade."
     * }
     * ```
     */
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/gabrielsaimo/IMPD-TV/main/version.json"

    fun checkForUpdates(context: Context) {
        Thread {
            try {
                val url = URL(UPDATE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    val latestVersionCode = json.getInt("versionCode")
                    val downloadUrl = json.getString("apkUrl")
                    val releaseNotes = json.optString("releaseNotes", "Nova atualização disponível.")

                    val currentVersionCode = currentVersionCode(context)

                    if (latestVersionCode > currentVersionCode) {
                        Handler(Looper.getMainLooper()).post {
                            showUpdateDialog(context, downloadUrl, releaseNotes)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun showUpdateDialog(context: Context, downloadUrl: String, releaseNotes: String) {
        AlertDialog.Builder(context)
            .setTitle("Atualização Disponível")
            .setMessage(releaseNotes)
            .setPositiveButton("Baixar e Instalar") { _, _ ->
                downloadAndInstall(context, downloadUrl)
            }
            .setNegativeButton("Agora Não", null)
            .setCancelable(false)
            .show()
    }

    /**
     * From Android 8 the package installer refuses an APK from an app that was
     * never granted "instalar apps desconhecidos", and it refuses it *after*
     * the download, on a system screen the viewer has to back out of. Asking
     * up front turns that into one trip to a settings toggle: coming back from
     * it resumes MainActivity, which re-runs the check immediately, so the
     * update offer reappears on its own with nothing left to remember.
     */
    private fun canInstall(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission(context: Context) {
        Toast.makeText(
            context,
            "Autorize a instalação de aplicativos para atualizar.",
            Toast.LENGTH_LONG
        ).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Some TV ROMs ship no per-app screen; the global one is the fallback.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun downloadAndInstall(context: Context, apkUrl: String) {
        if (!canInstall(context)) {
            requestInstallPermission(context)
            return
        }

        Toast.makeText(context, "Baixando atualização...", Toast.LENGTH_LONG).show()

        Thread {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connect()

                // A stick with no SD card and no emulated external storage
                // returns null here; provider_paths covers both directories.
                val file = File(context.externalCacheDir ?: context.cacheDir, "update.apk")
                val outputStream = FileOutputStream(file)
                val inputStream = connection.inputStream

                val buffer = ByteArray(1024)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }

                outputStream.close()
                inputStream.close()

                Handler(Looper.getMainLooper()).post {
                    installApk(context, file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Erro ao baixar atualização.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun installApk(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Erro ao instalar atualização.", Toast.LENGTH_SHORT).show()
        }
    }
}
