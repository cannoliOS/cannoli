package dev.cannoli.scorza.server

import android.content.res.AssetManager
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class FileServer(
    private val cannoliRoot: File,
    private val assets: AssetManager,
    private val port: Int = 9090
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    var pin: String = ""
        private set

    fun start() {
        if (running) return
        pin = generatePin()
        running = true
        thread(isDaemon = true, name = "FileServer") {
            val socket = ServerSocket(port)
            serverSocket = socket
            while (running) {
                try {
                    val client = socket.accept()
                    thread(isDaemon = true) { handleClient(client) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    val isRunning: Boolean get() = running

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val rawPath = parts[1].split("?", limit = 2)[0]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
            }

            val output = client.getOutputStream()

            if (method == "OPTIONS") {
                sendCors(output, 204, "text/plain", ByteArray(0))
                return
            }

            if (!checkAuth(headers)) {
                sendUnauthorized(output)
                return
            }

            val segments = rawPath.removePrefix("/").split("/")
                .map { URLDecoder.decode(it, "UTF-8") }

            if (segments.firstOrNull() != "api") {
                if (method == "GET") serveStatic(output, rawPath)
                else sendJson(output, 404, """{"error":"not found"}""")
                return
            }

            val apiSegments = segments.drop(1)
            val resource = apiSegments.firstOrNull() ?: ""

            when {
                method == "GET" && resource == "info" -> handleInfo(output)
                method == "GET" && resource == "tags" -> handleTags(output)
                resource in TAGGED_RESOURCES -> {
                    val dir = RESOURCE_DIRS[resource]!!
                    val tag = apiSegments.getOrNull(1)
                    if (tag.isNullOrBlank()) {
                        sendJson(output, 400, """{"error":"tag required"}""")
                        return
                    }
                    val targetDir = File(cannoliRoot, "$dir/$tag")
                    when (method) {
                        "GET" -> handleList(output, targetDir, "$dir/$tag")
                        "POST" -> {
                            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                            val contentType = headers["content-type"] ?: ""
                            handleUpload(output, targetDir, contentType, contentLength, input)
                        }
                        else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                    }
                }
                resource in FLAT_RESOURCES -> {
                    val dir = RESOURCE_DIRS[resource]!!
                    val targetDir = File(cannoliRoot, dir)
                    when (method) {
                        "GET" -> handleList(output, targetDir, dir)
                        "POST" -> {
                            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                            val contentType = headers["content-type"] ?: ""
                            handleUpload(output, targetDir, contentType, contentLength, input)
                        }
                        else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                    }
                }
                else -> sendJson(output, 404, """{"error":"not found"}""")
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(output: OutputStream) {
        sendJson(output, 200, """{"name":"Cannoli Kitchen","version":1}""")
    }

    private fun handleTags(output: OutputStream) {
        val romsDir = File(cannoliRoot, "Roms")
        val tags = romsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
        sendJson(output, 200, """{"tags":[$json]}""")
    }

    private fun handleList(output: OutputStream, dir: File, displayPath: String) {
        if (!isSecure(dir)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        if (!dir.exists() || !dir.isDirectory) {
            sendJson(output, 404, """{"error":"not found"}""")
            return
        }
        val entries = dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        val items = entries.joinToString(",") { f ->
            val name = escapeJson(f.name)
            val type = if (f.isDirectory) "dir" else "file"
            val size = if (f.isFile) f.length() else 0
            """{"name":"$name","type":"$type","size":$size}"""
        }
        sendJson(output, 200, """{"path":"${escapeJson(displayPath)}","entries":[$items]}""")
    }

    private fun handleUpload(
        output: OutputStream,
        destDir: File,
        contentType: String,
        contentLength: Int,
        input: java.io.InputStream
    ) {
        if (!isSecure(destDir)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        destDir.mkdirs()

        if (contentType.startsWith("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim()
            if (boundary.isEmpty()) {
                sendJson(output, 400, """{"error":"missing boundary"}""")
                return
            }
            handleMultipart(output, destDir, boundary, contentLength, input)
        } else {
            sendJson(output, 400, """{"error":"multipart upload required"}""")
        }
    }

    private fun handleMultipart(
        output: OutputStream,
        destDir: File,
        boundary: String,
        contentLength: Int,
        input: java.io.InputStream
    ) {
        val fullBoundary = "--$boundary"
        val uploaded = mutableListOf<String>()
        val buf = ByteArray(contentLength.coerceAtMost(256 * 1024 * 1024))
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(buf, totalRead, minOf(buf.size - totalRead, contentLength - totalRead))
            if (read <= 0) break
            totalRead += read
        }
        val body = String(buf, 0, totalRead, Charsets.ISO_8859_1)
        val parts = body.split(fullBoundary).drop(1)
        for (part in parts) {
            if (part.startsWith("--")) break
            val headerEnd = part.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            val partHeaders = part.substring(0, headerEnd)
            val filenameMatch = Regex("""filename="([^"]+)"""").find(partHeaders) ?: continue
            val filename = sanitizeFilename(filenameMatch.groupValues[1])
            val fileData = part.substring(headerEnd + 4).let {
                if (it.endsWith("\r\n")) it.dropLast(2) else it
            }
            File(destDir, filename).writeBytes(fileData.toByteArray(Charsets.ISO_8859_1))
            uploaded.add(filename)
        }
        val files = uploaded.joinToString(",") { "\"${escapeJson(it)}\"" }
        sendJson(output, 200, """{"ok":true,"files":[$files]}""")
    }

    private fun serveStatic(output: OutputStream, endpoint: String) {
        val path = if (endpoint == "/") "index.html" else endpoint.removePrefix("/")
        if (path.contains("..")) {
            sendCors(output, 403, "text/plain", "forbidden".toByteArray())
            return
        }
        try {
            val body = assets.open("kitchen/$path").readBytes()
            sendCors(output, 200, mimeForPath(path), body)
        } catch (_: Exception) {
            sendCors(output, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun checkAuth(headers: Map<String, String>): Boolean {
        val auth = headers["authorization"] ?: return false
        if (!auth.startsWith("Basic ")) return false
        val decoded = try {
            String(Base64.decode(auth.removePrefix("Basic "), Base64.NO_WRAP))
        } catch (_: Exception) { return false }
        val parts = decoded.split(":", limit = 2)
        return parts.size == 2 && parts[0] == "nonna" && parts[1] == pin
    }

    private fun sendUnauthorized(output: OutputStream) {
        val body = """{"error":"unauthorized"}""".toByteArray()
        val header = buildString {
            append("HTTP/1.1 401 Unauthorized\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("WWW-Authenticate: Basic realm=\"Cannoli Kitchen\"\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun isSecure(file: File): Boolean {
        return file.canonicalPath.startsWith(cannoliRoot.canonicalPath)
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[/\\\\]"), "_").trim()
    }

    private fun mimeForPath(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun sendJson(output: OutputStream, status: Int, json: String) {
        sendCors(output, status, "application/json", json.toByteArray())
    }

    private fun sendCors(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun generatePin(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    companion object {
        private val TAGGED_RESOURCES = setOf("roms", "art", "saves", "states")
        private val FLAT_RESOURCES = setOf("bios", "wallpapers")
        private val RESOURCE_DIRS = mapOf(
            "roms" to "Roms",
            "art" to "Art",
            "saves" to "Saves",
            "states" to "Save States",
            "bios" to "BIOS",
            "wallpapers" to "Wallpapers"
        )
    }
}
