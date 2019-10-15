package expo.modules.ota

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OtaUpdater(private val context: Context, private val persistence: ExpoOTAPersistence, private val id: String) {

    init {
        if(persistence.enqueqedReorderAtNextBoot) {
            markDownloadedCurrentAndCurrentOutdated()
            removeOutdatedBundle()
            persistence.enqueqedReorderAtNextBoot = false
        }
    }

    fun checkAndDownloadUpdate(success: (manifest: JSONObject, path: String) -> Unit,
                               updateUnavailable: (manifest: JSONObject) -> Unit,
                               error: (Exception?) -> Unit) {
        downloadManifest({ manifest ->
            if (persistence.config!!.manifestComparator.shouldDownloadBundle(persistence.newestManifest, manifest)) {
                downloadBundle(manifest, {
                    success(manifest, it)
                }) { error(it) }
            } else {
                updateUnavailable(manifest)
            }
        }) { error(it) }
    }


    private fun createManifestRequest(config: ExpoOTAConfig): Request {
        val requestBuilder = Request.Builder()
        requestBuilder.url(config.manifestUrl)
        config.manifestHeaders.forEach { requestBuilder.addHeader(it.key, it.value) }
        return requestBuilder.build()
    }

    fun downloadManifest(success: (JSONObject) -> Unit, error: (Exception) -> Unit) {
        val config = persistence.config
        if (config != null) {
            config.manifestHttpClient.newCall(createManifestRequest(config)).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    error(IllegalStateException("Manifest fetching failed: ", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        if (response.body() != null) {
                            verifyManifest(response, config, success, error)
                        } else {
                            error(IllegalStateException("Response body is null: ", response.body()))
                        }
                    } else {
                        error(IllegalStateException("Response not successful. Code: " + response.code() + ", body: " + response.body()?.toString()))
                    }
                }
            })
        } else {
            throwUninitializedExpoOtaError()
        }
    }

    fun verifyManifest(response: Response, config: ExpoOTAConfig, success: (JSONObject) -> Unit, error: (Exception) -> Unit) {
        config.manifestResponseValidator.validate(response, {
            success(JSONObject(it))
        }, error)
    }

    fun saveDownloadedManifestAndBundlePath(manifest: JSONObject, path: String) {
        val previousBundle = persistence.downloadedBundlePath
        if (previousBundle != null) {
            removeFile(previousBundle) // TODO: Move to persistence!
        }
        persistence.downloadedManifest = manifest
        persistence.downloadedBundlePath = path
    }

    fun prepareToReload() {
        persistence.enqueqedReorderAtNextBoot = true
        persistence.synchronize()
    }

    fun markDownloadedCurrentAndCurrentOutdated() {
        val outdated = persistence.outdatedBundlePath
        if (outdated != null) {
            removeFile(outdated)
        }
        persistence.markDownloadedCurrentAndCurrentOutdated()
    }

    fun removeOutdatedBundle() {
        val outdatedBundlePath = persistence.outdatedBundlePath
        persistence.outdatedBundlePath = null
        if (outdatedBundlePath != null) {
            removeFile(outdatedBundlePath)
        }
    }

    fun cleanUnusedFiles() {
        val bundlesDir = bundleDir()
        if (bundlesDir.exists() && bundlesDir.isDirectory) {
            bundlesDir.listFiles { directory, filename -> !validFilesSet.contains(File(directory, filename).path) }
                    .forEach { removeFile(it.path) }
        }
    }

    private val validFilesSet: Set<String>
        get() {
            val bundlePath = persistence.bundlePath
            val downloadedBundlePath = persistence.downloadedBundlePath
            var validFilesSet = setOf<String>()
            if (bundlePath != null) {
                validFilesSet = validFilesSet.plus(bundlePath)
            }
            if (downloadedBundlePath != null) {
                validFilesSet = validFilesSet.plus(downloadedBundlePath)
            }
            return validFilesSet
        }

    private fun downloadBundle(manifest: JSONObject, success: (String) -> Unit, error: (Exception?) -> Unit) {
        val bundleUrl = manifest.optString(KEY_MANIFEST_BUNDLE_URL)
        val bundleLoader = BundleLoader(context, bundleClient(persistence.config!!))
        val params = BundleLoader.BundleLoadParams(bundleUrl, bundleDir(), bundleFilename(manifest))
        bundleLoader.loadJsBundle(params, success, error)
    }

    private fun bundleFilename(manifest: JSONObject): String {
        return "${bundleFilePrefix()}_${manifest.optString("version")}_${System.currentTimeMillis()}"
    }

    private fun bundleFilePrefix(): String {
        return "bundle_${persistence.config?.channelIdentifier}"
    }

    private fun bundleClient(config: ExpoOTAConfig): OkHttpClient {
        return config.bundleHttpClient ?: longTimeoutHttpClient()
    }

    private fun longTimeoutHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()
    }

    private fun bundleDir(): File {
        return File(context.filesDir, "bundle-$id")
    }
}