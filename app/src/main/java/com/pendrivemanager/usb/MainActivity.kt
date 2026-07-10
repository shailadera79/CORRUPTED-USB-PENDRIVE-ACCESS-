package com.pendrivemanager.usb

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pendrivemanager.usb.driver.ScsiBlockDevice
import com.pendrivemanager.usb.driver.UsbBulkTransport
import com.pendrivemanager.usb.fs.ExFatEntry
import com.pendrivemanager.usb.fs.ExFatFileSystem
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Stack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pen Drive Manager - strictly exFAT.
 *
 * Flow: USB permission -> raw bulk transport claim -> SCSI block device init
 * -> exFAT filesystem mount (apna khud ka parser) -> browse/copy/delete.
 *
 * Saara USB/disk I/O ek hi single-thread queue (ioExecutor) par chalta hai,
 * taaki do operations kabhi ek saath USB connection ko touch na karein
 * (isse commands corrupt/fail ho sakte the).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pendrivemanager.usb.USB_PERMISSION"
        private const val STORAGE_PERMISSION_REQUEST = 100
    }

    private lateinit var usbManager: UsbManager
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var txtStatus: TextView
    private lateinit var txtCurrentPath: TextView
    private lateinit var adapter: FileAdapter

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var fileSystem: ExFatFileSystem? = null
    private val dirStack = Stack<Pair<Int, String>>() // (cluster, folder name)
    private var pendingCopyEntry: ExFatEntry? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) mountExFat(device)
                    else showStatus("USB permission was denied. Please try again.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> checkForDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    fileSystem = null
                    dirStack.clear()
                    showStatus(getString(R.string.no_device))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        recyclerFiles = findViewById(R.id.recyclerFiles)
        txtStatus = findViewById(R.id.txtStatus)
        txtCurrentPath = findViewById(R.id.txtCurrentPath)

        adapter = FileAdapter(emptyList(), ::onFileClicked, ::onFileMoreClicked)
        recyclerFiles.layoutManager = LinearLayoutManager(this)
        recyclerFiles.adapter = adapter

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkForDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        ioExecutor.shutdownNow()
    }

    private fun checkForDevices() {
        val device = usbManager.deviceList.values.firstOrNull()
        if (device == null) {
            showStatus(getString(R.string.no_device))
            return
        }
        if (fileSystem != null) return // already mounted, don't re-claim the USB interface

        if (usbManager.hasPermission(device)) {
            mountExFat(device)
        } else {
            showStatus(getString(R.string.requesting_permission))
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun mountExFat(device: UsbDevice) {
        showStatus("Reading drive…")
        ioExecutor.execute {
            try {
                val transport = UsbBulkTransport.open(usbManager, device)
                    ?: throw Exception("This USB device doesn't look like a mass-storage device.")
                val scsi = ScsiBlockDevice(transport)
                val fs = ExFatFileSystem.mount(scsi)
                fileSystem = fs
                dirStack.clear()
                dirStack.push(Pair(fs.rootDirCluster, ""))
                runOnUiThread { showFolder() }
            } catch (e: Exception) {
                runOnUiThread {
                    showStatus(getString(R.string.unsupported_fs) + "\n\n(${e.message})")
                }
            }
        }
    }

    private fun showFolder() {
        val fs = fileSystem ?: return
        txtStatus.visibility = View.GONE
        recyclerFiles.visibility = View.VISIBLE
        txtCurrentPath.text = "/" + dirStack.drop(1).joinToString("/") { it.second }

        ioExecutor.execute {
            try {
                val (cluster, _) = dirStack.peek()
                val entries = fs.listDirectory(cluster)
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                runOnUiThread { adapter.updateData(entries) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not read folder: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onFileClicked(entry: ExFatEntry) {
        if (entry.isDirectory) {
            dirStack.push(Pair(entry.firstCluster, entry.name))
            showFolder()
        } else {
            onFileMoreClicked(entry)
        }
    }

    private fun onFileMoreClicked(entry: ExFatEntry) {
        val options = arrayOf("Copy to phone", "Delete (experimental)")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToPhone(entry)
                    1 -> confirmDelete(entry)
                }
            }
            .show()
    }

    // ---------- Copy to phone's Downloads folder ----------

    private fun copyToPhone(entry: ExFatEntry) {
        if (entry.isDirectory) {
            Toast.makeText(this, "Only single files can be copied right now, not whole folders.", Toast.LENGTH_LONG).show()
            return
        }

        // Purane Android (9 aur usse neeche) par Downloads folder mein likhne ke liye
        // runtime permission chahiye. Naye Android par MediaStore API isse handle kar leta hai.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCopyEntry = entry
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST
            )
            return
        }

        startCopy(entry)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            val entry = pendingCopyEntry
            pendingCopyEntry = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && entry != null) {
                startCopy(entry)
            } else {
                Toast.makeText(this, "Storage permission is needed to save the file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCopy(entry: ExFatEntry) {
        val fs = fileSystem ?: return
        Toast.makeText(this, "Copying \"${entry.name}\"…", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            try {
                val (outputStream, displayLocation) = openDownloadsOutputStream(entry.name)
                outputStream.use { out -> fs.readFile(entry, out) }
                runOnUiThread {
                    Toast.makeText(this, "Saved to $displayLocation", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Downloads folder mein file ke liye ek OutputStream kholta hai (Android version ke hisaab se sahi tareeke se). */
    private fun openDownloadsOutputStream(fileName: String): Pair<OutputStream, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val extension = fileName.substringAfterLast('.', "")
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create file in Downloads")
            val out = contentResolver.openOutputStream(uri) ?: throw Exception("Could not open file for writing")
            return Pair(out, "Downloads/$fileName")
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, fileName)
            return Pair(FileOutputStream(destFile), destFile.absolutePath)
        }
    }

    // ---------- Delete ----------

    private fun confirmDelete(entry: ExFatEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(
                "\"${entry.name}\" will disappear from the list.\n\n" +
                    "Note: this is a \"soft delete\" — the storage space isn't freed immediately, " +
                    "but the file will stop showing up."
            )
            .setPositiveButton("Delete") { _, _ -> deleteEntry(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEntry(entry: ExFatEntry) {
        val fs = fileSystem ?: return
        ioExecutor.execute {
            try {
                fs.deleteEntry(entry)
                runOnUiThread { showFolder() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (dirStack.size > 1) {
            dirStack.pop()
            showFolder()
        } else {
            super.onBackPressed()
        }
    }

    private fun showStatus(text: String) {
        txtStatus.text = text
        txtStatus.visibility = View.VISIBLE
        recyclerFiles.visibility = View.GONE
    }
}
