package com.gonzalo.atlas.motor

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Un PNG sin Android ni AWT (para ver en las pruebas lo que pinta el motor). */
object Png {
    fun escribe(px: IntArray, w: Int, h: Int, f: File) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val fila = ByteArray(1 + w * 3)
            for (y in 0 until h) {
                fila[0] = 0
                for (x in 0 until w) { val c = px[y * w + x]; fila[1 + x * 3] = (c shr 16).toByte(); fila[2 + x * 3] = (c shr 8).toByte(); fila[3 + x * 3] = c.toByte() }
                z.write(fila)
            }
        }
        f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream()).use { o ->
            o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            fun trozo(tipo: String, d: ByteArray) {
                o.writeInt(d.size); val t = tipo.toByteArray(); o.write(t); o.write(d)
                val c = CRC32(); c.update(t); c.update(d); o.writeInt(c.value.toInt())
            }
            val ih = ByteArrayOutputStream(); DataOutputStream(ih).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0) }
            trozo("IHDR", ih.toByteArray()); trozo("IDAT", raw.toByteArray()); trozo("IEND", ByteArray(0))
        }
    }
}
