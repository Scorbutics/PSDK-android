package com.psdk.ui.wizard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.iyxan23.zipalignjava.ZipAlign
import com.psdk.R
import com.psdk.signing.Signer
import com.psdk.signing.buildDefaultSigningOptions
import com.psdk.ui.CompileWizardViewModel
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom

class ExportApkFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()
    private var applicationLogo: InputStream? = null

    private lateinit var appNameInput: TextInputEditText
    private lateinit var appLogoPreview: ImageView
    private lateinit var exportButton: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var exportStepTitle: TextView
    private lateinit var exportProgressBar: ProgressBar

    private val chooseLogoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data?.data!!
            appLogoPreview.setImageURI(uri)
            applicationLogo?.close()
            applicationLogo = requireContext().contentResolver.openInputStream(uri)
        }
    }

    private val shareApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_export_apk, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appNameInput = view.findViewById(R.id.appNameInput)
        appLogoPreview = view.findViewById(R.id.appLogoPreview)
        exportButton = view.findViewById(R.id.exportButton)
        progressCard = view.findViewById(R.id.progressCard)
        exportStepTitle = view.findViewById(R.id.exportStepTitle)
        exportProgressBar = view.findViewById(R.id.exportProgressBar)
        val changeLogoButton = view.findViewById<MaterialButton>(R.id.changeLogoButton)

        appNameInput.setText(getApplicationName(requireContext()) + " fan game")

        changeLogoButton.setOnClickListener { chooseApplicationLogo() }

        exportButton.setOnClickListener {
            val appFolder = viewModel.apkFolderLocation.value
            if (appFolder != null) {
                startExport(File(appFolder))
            }
        }
    }

    private fun getApplicationName(context: Context): String {
        val applicationInfo = context.applicationInfo
        val stringId = applicationInfo.labelRes
        return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else context.getString(stringId)
    }

    private fun chooseApplicationLogo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        chooseLogoLauncher.launch(Intent.createChooser(intent, "Choose a logo"))
    }

    private fun startExport(appFolder: File) {
        val context = requireContext()
        val newApplicationName = appNameInput.text.toString()
        var newPackageName = newApplicationName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (newPackageName.isEmpty() || newApplicationName == getApplicationName(context)) {
            newPackageName = generateRandomString(10)
        }
        newPackageName = "com.psdk.$newPackageName"

        progressCard.visibility = View.VISIBLE
        exportButton.isEnabled = false

        val tmpCacheExportDirectory = File(context.cacheDir.path + "/exports")
        if (tmpCacheExportDirectory.exists()) {
            tmpCacheExportDirectory.deleteRecursively()
        }
        tmpCacheExportDirectory.mkdir()

        val totalSteps = 7
        val progressIncrement = (100.0 - 5.0) / totalSteps
        var progressValue = 0

        val finalPackageName = newPackageName
        Thread(null, {
            try {
                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Copying into temporary APK...")
                val tmpApk = copySelfApk(tmpCacheExportDirectory)

                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Modifying application identifier...")
                val apkModule = ApkModule.loadApkFile(tmpApk)
                changeApplicationId(finalPackageName, apkModule)

                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Modifying name and logo...")
                changeApplicationNameAndLogo(apkModule, finalPackageName, newApplicationName, applicationLogo)

                progressValue = incrementProgress(progressValue, progressIncrement)
                apkModule.writeApk(tmpApk)
                apkModule.close()

                updateStepTitle("Bundling compiled game...")
                bundleCompiledGame(appFolder, tmpApk)

                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Zip aligning...")
                val alignedApk = zipalign(tmpApk, tmpCacheExportDirectory)

                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Signing APK...")
                val signedApk = signApk(alignedApk, tmpCacheExportDirectory, finalPackageName)

                progressValue = incrementProgress(progressValue, progressIncrement)
                updateStepTitle("Ready to share!")
                shareApk(signedApk)
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                activity?.runOnUiThread {
                    progressCard.visibility = View.GONE
                    exportButton.isEnabled = true
                }
            }
        }, "Thread-export-PSDK", 4096 * 2).start()
    }

    private fun updateStepTitle(title: String) {
        activity?.runOnUiThread { exportStepTitle.text = title }
    }

    private fun incrementProgress(current: Int, increment: Double): Int {
        val result = current + increment.toInt()
        activity?.runOnUiThread {
            exportProgressBar.progress = 0
            exportProgressBar.setProgress(result, true)
        }
        return result
    }

    private fun copySelfApk(tmpDir: File): File {
        Thread.sleep(500)
        val tmpApk = File.createTempFile("app-output-unsigned", ".apk", tmpDir)
        File(requireContext().applicationInfo.publicSourceDir).copyTo(tmpApk, true)
        return tmpApk
    }

    private fun changeApplicationId(newPackageName: String, apkModule: ApkModule) {
        val oldPackageName = apkModule.androidManifest.packageName
        apkModule.androidManifest.packageName = newPackageName
        apkModule.tableBlock.listPackages().filter { it.name == oldPackageName }.forEach { it.name = newPackageName }

        val itProvider = apkModule.androidManifest.applicationElement.getElements("provider")
        while (itProvider.hasNext()) {
            val provider = itProvider.next()
            val authorities = provider.listAttributes().find { it.name == "authorities" }
            if (authorities != null && authorities.valueString.startsWith(oldPackageName)) {
                authorities.valueAsString = authorities.valueString.replace(oldPackageName, newPackageName)
            }
        }
    }

    private fun changeApplicationNameAndLogo(apkModule: ApkModule, newPackageName: String, newLabel: String, newLogo: InputStream?) {
        apkModule.androidManifest.setApplicationLabel(newLabel)
        apkModule.tableBlock.listPackages().filter { it.name == newPackageName }.forEach { packageBlock ->
            val appName = packageBlock.getOrCreate("", "string", "app_name")
            appName.setValueAsString(newLabel)
        }

        val logo = apkModule.tableBlock.getResource(newPackageName, "drawable", "logo")
        val logoFilePath = logo.get().resValue
        val logoSource = apkModule.zipEntryMap.getInputSource(logoFilePath.valueAsString)

        val byteStream = ByteArrayOutputStream()
        streamToStream(newLogo ?: logoSource.openStream(), byteStream)
        val newLogoSource = ByteInputSource(byteStream.toByteArray(), logoFilePath.valueAsString)
        apkModule.zipEntryMap.remove(logoSource)
        apkModule.add(newLogoSource)
    }

    private fun streamToStream(ins: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var len = ins.read(buffer)
        while (len != -1) {
            out.write(buffer, 0, len)
            len = ins.read(buffer)
        }
    }

    private fun bundleCompiledGame(appFolder: File, tmpApk: File) {
        val zipParameters = ZipParameters()
        zipParameters.rootFolderNameInZip = "assets"
        val bundledApk = ZipFile(tmpApk)
        bundledApk.addFolder(appFolder, zipParameters)
        bundledApk.close()
    }

    private fun zipalign(apk: File, tmpDir: File): File {
        val alignedApk = File.createTempFile("app-output-aligned", ".apk", tmpDir)
        ZipAlign.alignZip(RandomAccessFile(apk, "r"), alignedApk.outputStream(), 4)
        return alignedApk
    }

    private fun signApk(inApk: File, tmpDir: File, outputName: String): File {
        val signedApk = File.createTempFile(outputName, ".apk", tmpDir)
        val signingOptions = buildDefaultSigningOptions(requireActivity().application)
        Signer(signingOptions).signApk(inApk, signedApk)
        return signedApk
    }

    private fun shareApk(signedApk: File) {
        Thread.sleep(1000)
        activity?.runOnUiThread {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".provider",
                    signedApk
                )
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            shareApkLauncher.launch(Intent.createChooser(share, "Share App"))
        }
    }

    private fun generateRandomString(length: Int): String {
        val characters = "abcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        return (1..length).map { characters[random.nextInt(characters.length)] }.joinToString("")
    }
}
