package com.example.kindlesend

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** 极简 HTTP 服务：首页书单 + /d/{id}/bookN.ext 下载。无第三方依赖。 */
class SimpleHttpServer(
    private val books: List<Book>,
    private val onActivity: () -> Unit,
    private val onDownloaded: (Book) -> Unit
) {
    private var ss: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    /** 返回实际监听端口，失败返回 -1 */
    fun start(): Int {
        for (p in 8080..8090) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                ss = s
                Thread {
                    while (!s.isClosed) {
                        try {
                            val c = s.accept()
                            pool.execute { try { c.use { serve(it) } } catch (_: Exception) {} }
                        } catch (_: Exception) { break }
                    }
                }.start()
                return p
            } catch (_: Exception) { }
        }
        return -1
    }

    fun stop() {
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun serve(c: Socket) {
        c.soTimeout = 15000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val line = reader.readLine() ?: return
        var n = 0
        while (true) {
            val h = reader.readLine()
            if (h.isNullOrEmpty() || ++n > 100) break
        }
        val parts = line.split(" ")
        if (parts.size < 2) return
        val head = parts[0] == "HEAD"
        if (parts[0] != "GET" && !head) {
            send(c, 405, "text/plain", "Method Not Allowed".toByteArray(), head); return
        }
        onActivity()
        val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        when {
            path == "/" || path == "/index.html" ->
                send(c, 200, "text/html; charset=utf-8", indexHtml().toByteArray(Charsets.UTF_8), head)
            path.startsWith("/d/") -> {
                val id = path.split("/").getOrNull(2)?.toIntOrNull()
                val book = books.firstOrNull { it.id == id }
                if (book == null) send(c, 404, "text/plain", "Not Found".toByteArray(), head)
                else download(c, book, head)
            }
            else -> send(c, 404, "text/plain", "Not Found".toByteArray(), head)
        }
    }

    private fun download(c: Socket, b: Book, head: Boolean) {
        val f = b.file
        val ext = f.extension.lowercase()
        val ascii = f.nameWithoutExtension.filter { it.code in 32..126 && it !in "\"\\/:*?<>|;" }.trim()
            .ifEmpty { "book${b.id}" } + "." + ext
        val utf8 = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        val disp = "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf8"
        val out = BufferedOutputStream(c.getOutputStream())
        writeHeader(out, 200, mime(ext), f.length(), disp)
        if (!head) {
            f.inputStream().use { it.copyTo(out, 16 * 1024) }
            out.flush()
            onDownloaded(b)   // 传输中断会抛异常，不会走到这里
        } else out.flush()
        onActivity()
    }

    private fun mime(ext: String) = when (ext) {
        "azw3", "azw" -> "application/vnd.amazon.ebook"
        "mobi", "prc" -> "application/x-mobipocket-ebook"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"   // txt 也用它，避免浏览器直接打开而不是下载
    }

    private fun indexHtml(): String {
        val rows = if (books.isEmpty()) "<p>没有书</p>" else books.joinToString("") { b ->
            val ext = b.file.extension.lowercase()
            val mark = if (ServerState.downloaded.contains(b.file.name)) " （已下载）" else ""
            val mb = "%.1f MB".format(b.file.length() / 1048576.0)
            "<p style=\"font-size:150%;margin:28px 0\"><a href=\"/d/${b.id}/book${b.id}.$ext\">" +
                "${esc(b.file.nameWithoutExtension)}</a>$mark<br><small>${ext.uppercase()} · $mb</small></p>"
        }
        return "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>Books</title></head><body style=\"font-family:sans-serif;padding:12px\">" +
            "<h2>点击书名下载</h2>$rows</body></html>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun send(c: Socket, code: Int, type: String, body: ByteArray, head: Boolean) {
        val out = BufferedOutputStream(c.getOutputStream())
        writeHeader(out, code, type, body.size.toLong(), null)
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHeader(out: BufferedOutputStream, code: Int, type: String, len: Long, disp: String?) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $len\r\n")
        sb.append("Connection: close\r\nCache-Control: no-store\r\n")
        if (disp != null) sb.append("Content-Disposition: $disp\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }
}
