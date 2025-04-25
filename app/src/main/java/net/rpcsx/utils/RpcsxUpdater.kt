package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.BuildConfig
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.channels.DevRpcsxChannel
import java.io.File
import kotlin.system.exitProcess


object RpcsxUpdater {
    suspend fun checkForUpdate(): String? {
        val url = DevRpcsxChannel // TODO: update once RPCSX has release with android support

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release

                if (release.assets.find { it.name == "librpcsx-android-arm64-v8a-armv8-a.so" }?.browser_download_url == null) {
                    return null
                }

                if (RPCSX.activeLibrary.value == null) {
                    return release.name
                }

                val currentRpcsxVersion = "v" + RPCSX.instance.getVersion().trim().removeSuffix(" Draft").trim()

                if (currentRpcsxVersion != release.name && release.name != GeneralSettings["rpcsx_bad_version"]) {
                    return release.name
                }
            }
            is GitHub.FetchResult.Error -> {
                AlertDialogQueue.showDialog("Check For RPCSX Updates Error", fetchResult.message)
            }
        }

        return null
    }

    suspend fun downloadUpdate(destinationDir: File, progressCallback: (Long, Long) -> Unit): File? {
        val url = DevRpcsxChannel // TODO: GeneralSettings["rpcsx_channel"] as String

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                val releaseAsset = release.assets.find { it.name == "librpcsx-android-arm64-v8a-armv8-a.so" }

                if (release.name != BuildConfig.Version && releaseAsset?.browser_download_url != null) {
                    val target = File(destinationDir, "librpcsx-android-armv8-a-${release.name}.so")

                    if (target.exists()) {
                        return target
                    }

                    val tmp = File(destinationDir, "librpcsx.so.tmp")
                    if (tmp.exists()) {
                        withContext(Dispatchers.IO) {
                            tmp.delete()
                        }
                    }

                    withContext(Dispatchers.IO) {
                        tmp.createNewFile()
                    }

                    tmp.deleteOnExit()

                    when (val downloadStatus = GitHub.downloadAsset(releaseAsset.browser_download_url, tmp, progressCallback)) {
                        is GitHub.DownloadStatus.Success -> {
                            withContext(Dispatchers.IO) {
                                tmp.renameTo(target)
                            }
                            return target
                        }
                        is GitHub.DownloadStatus.Error ->
                            AlertDialogQueue.showDialog("RPCSX Download Error", downloadStatus.message ?: "Unexpected error")
                    }
                }
            }
            is GitHub.FetchResult.Error -> {
                AlertDialogQueue.showDialog("RPCSX Download Error", fetchResult.message)
            }
        }

        return null
    }

    fun installUpdate(context: Context, updateFile: File): Boolean {
        val restart = {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            mainIntent.setPackage(context.packageName)
            context.startActivity(mainIntent)
            GeneralSettings.sync()
            exitProcess(0)
        }

        val prevLibrary = GeneralSettings["rpcsx_library"] as? String
        GeneralSettings["rpcsx_library"] = updateFile.toString()
        GeneralSettings["rpcsx_update_status"] = null

        Log.e("RPCSX-UI", "registered update file ${GeneralSettings["rpcsx_library"]}")

        if (prevLibrary == null) {
            restart()
        }

        GeneralSettings["rpcsx_prev_library"] = prevLibrary
        AlertDialogQueue.showDialog("RPCSX Update", "Restart RPCSX UI to apply change", onConfirm = {
            restart()
        })
        return true
    }
}
