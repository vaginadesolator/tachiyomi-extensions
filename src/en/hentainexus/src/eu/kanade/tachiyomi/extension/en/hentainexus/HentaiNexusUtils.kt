package eu.kanade.tachiyomi.extension.en.hentainexus

import android.annotation.TargetApi
import android.os.Build
import java.nio.charset.StandardCharsets
import java.util.Base64

@TargetApi(Build.VERSION_CODES.O)
fun decodeReader(encoded: String): String {

    var prev = 2
    var out = 0
    val json: MutableList<Int> = mutableListOf()

    arrayOfNulls<Boolean>(257).let { options ->
        while (json.size < 16) {
            if (options[prev] == null) {
                json.add(prev)
                out = prev.shl(1)
                while (out <= 256) {
                    options[out] = true
                    out += prev
                }
            }
            ++prev
        }
    }

    var type = 0
    prev = 0

    val b64Decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.ISO_8859_1)
    while (prev < 64) {
        type = type xor Character.codePointAt(b64Decoded, prev)
        out = 0
        while (out < 8) {
            type = if (type and 1 != 0) (type ushr 1) xor 12 else type ushr 1
            out++
        }
        prev++
    }
    type = type and 7
    val value = json[type]

    val p = (0..255).toMutableList()
    var curr = 0
    var i = 0
    for (key in 0..255) {
        i = (i + p[key] + Character.codePointAt(b64Decoded, key % 64)) % 256
        curr = p[key]
        p[key] = p[i]
        p[i] = curr
    }

    println("p: $p")

    var cur = 0
    var dir = 0
    var k = 0
    i = 0

    var x = 0
    var payload = ""
    while (x + 64 < b64Decoded.length) {
        k = (k + value) % 256
        i = (dir + p[(i + p[k]) % 256]) % 256
        dir = (dir + k + p[k]) % 256
        curr = p[k]
        p[k] = p[i]
        p[i] = curr
        cur = p[(i + p[(k + p[(cur + dir) % 256]) % 256]) % 256]
        payload += (Character.codePointAt(b64Decoded, x + 64) xor cur).toChar()
        x++
    }

    return payload
}
