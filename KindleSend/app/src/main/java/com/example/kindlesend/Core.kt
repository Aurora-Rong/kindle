package com.example.kindlesend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class Book(val id: Int, val file: File)

/** 服务与界面共享的状态 */
object ServerState {
    @Volatile var running = false
    @Volatile var port = 8080
    val downloaded: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile var listener: (() -> Unit)? = null
    fun changed() { listener?.invoke() }
}

object BookStore {
    // MVP：只收 Kindle 通常能直接读的格式（epub 会提示可能打不开）
    val EXTS = setOf("azw3", "azw", "mobi", "prc", "pdf", "txt", "epub")

    fun dir(c: Context): File = File(c.filesDir, "books").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** 返回 null 表示成功，否则返回错误信息 */
    fun import(c: Context, uri: Uri): String? {
        val name = displayName(c, uri) ?: return "无法读取文件名"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in EXTS) return "不支持的格式：$name"
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        var target = File(dir(c), clean)
        var i = 1
        while (target.exists()) {
            target = File(dir(c), clean.substringBeforeLast('.') + " ($i)." + ext)
            i++
        }
        val input = c.contentResolver.openInputStream(uri) ?: return "无法打开：$name"
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        return null
    }

    private fun displayName(c: Context, uri: Uri): String? {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}

object NetUtil {
    private val skipPrefix = listOf("rmnet", "ccmni", "v4-rmnet", "dummy", "tun", "lo", "p2p")

    /** 返回 (网卡名, IPv4)，已排除蜂窝/回环等网卡；热点和普通 Wi-Fi 都会列出 */
    fun candidates(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val nis = NetworkInterface.getNetworkInterfaces() ?: return out
        for (ni in nis) {
            if (!ni.isUp || ni.isLoopback || skipPrefix.any { ni.name.startsWith(it) }) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) out += ni.name to (a.hostAddress ?: continue)
            }
        }
        return out
    }
}
