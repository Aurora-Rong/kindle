package com.example.kindlesend

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Kindle 传书"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "① 导入书籍  ② 打开手机热点，让 Kindle 连上  ③ 点“开始传输”  ④ 在 Kindle 实验性浏览器输入下方地址（建议存书签）"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(Button(this).apply { text = "导入书籍"; setOnClickListener { pick() } })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(16), 0, dp(8)) }
        root.addView(status)
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)
        root.addView(Button(this).apply { text = "刷新地址"; setOnClickListener { refresh() } })
        setContentView(ScrollView(this).apply { addView(root) })

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ServerState.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        ServerState.listener = null
        super.onPause()
    }

    // ---------- 导入 ----------
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION") startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris += it.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        importAll(uris)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        if (uris.isNotEmpty()) importAll(uris)
    }

    private fun importAll(uris: List<Uri>) {
        Thread {
            val errs = uris.mapNotNull {
                runCatching { BookStore.import(this, it) }.getOrElse { e -> "导入失败：${e.message}" }
            }
            runOnUiThread {
                refresh()
                if (errs.isNotEmpty()) toast(errs.joinToString("\n"))
            }
        }.start()
    }

    // ---------- 传输开关 ----------
    private fun onToggle() {
        if (ServerState.running) {
            stopService(Intent(this, TransferService::class.java))
            return
        }
        if (BookStore.list(this).isEmpty()) { toast("请先导入书籍"); return }
        if (NetUtil.candidates().isEmpty()) { toast("没有检测到热点/Wi-Fi，请先打开手机热点"); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        startForegroundService(Intent(this, TransferService::class.java))
    }

    // ---------- 界面刷新 ----------
    private fun refresh() {
        listBox.removeAllViews()
        val files = BookStore.list(this)
        if (files.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "还没有书，点上面“导入书籍”。"; setPadding(0, dp(12), 0, dp(12)) })
        }
        files.forEach { listBox.addView(row(it)) }

        val addrs = NetUtil.candidates()
        status.text = when {
            ServerState.running ->
                "传输中。在 Kindle 浏览器输入：\n" +
                    addrs.joinToString("\n") { "http://${it.second}:${ServerState.port}" } +
                    "\n（全部下载后或闲置 10 分钟会自动停止）"
            addrs.isEmpty() -> "没有检测到热点/Wi-Fi 地址，请先打开手机热点。"
            else -> "本机地址（开始传输后可用）：\n" + addrs.joinToString("\n") { "http://${it.second}:8080" }
        }
        toggle.text = if (ServerState.running) "停止传输" else "开始传输"
    }

    private fun row(f: File): View {
        val ext = f.extension.lowercase()
        val done = ServerState.running && ServerState.downloaded.contains(f.name)
        val tag = buildString {
            append(ext.uppercase()).append(" · ").append(Formatter.formatFileSize(this@MainActivity, f.length()))
            if (ext == "epub") append(" · ⚠ Kindle 可能无法直接打开")
            if (done) append(" · ✓ 已下载")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(context).apply {
                text = "${f.nameWithoutExtension}\n$tag"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(context).apply {
                text = "删除"
                setOnClickListener {
                    if (ServerState.running) toast("传输中无法删除，请先停止") else { f.delete(); refresh() }
                }
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
