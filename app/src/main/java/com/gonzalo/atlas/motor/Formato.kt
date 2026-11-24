package com.gonzalo.atlas.motor

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cómo se guarda un mapa en el móvil: una carpeta con `mapa.json` (lo de arriba y todo lo vectorial) y `trozos/<cx>_<cy>.bin`
 * con los trozos que has tocado (los demás se vuelven a generar de la semilla). Para la nube, la carpeta entera en un zip (.atlas).
 */
object Formato {
    private const val MAGIA = 0x41545231 // «ATR1»
    private const val ESCALA_ALT = 32000f

    fun escribeTrozo(f: File, t: Trozo) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(tmp.outputStream(), Deflater(5)), 1 shl 16)).use { o ->
            o.writeInt(MAGIA); o.writeInt(1); o.writeInt(t.cx); o.writeInt(t.cy)
            val b = java.nio.ByteBuffer.allocate(Trozo.N * 2)
            for (a in t.alt) b.putShort(Math.round(a.coerceIn(-1f, 1f) * ESCALA_ALT).toShort())
            o.write(b.array())
            o.write(t.bio); o.write(t.reino); o.write(t.region); o.write(t.zona)
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun leeTrozo(f: File, cx: Int, cy: Int): Trozo? = runCatching {
        DataInputStream(BufferedInputStream(InflaterInputStream(f.inputStream()), 1 shl 16)).use { i ->
            if (i.readInt() != MAGIA) return null
            i.readInt(); i.readInt(); i.readInt()
            val t = Trozo(cx, cy)
            val b = ByteArray(Trozo.N * 2); i.readFully(b)
            val bb = java.nio.ByteBuffer.wrap(b)
            for (k in 0 until Trozo.N) t.alt[k] = bb.getShort() / ESCALA_ALT
            i.readFully(t.bio); i.readFully(t.reino); i.readFully(t.region); i.readFully(t.zona)
            t.editado = true
            t
        }
    }.getOrNull()

    /** Guarda lo cambiado. Devuelve cuántos trozos ha escrito. */
    fun guarda(m: Mapa): Int {
        val dir = m.dir ?: return 0
        dir.mkdirs()
        var n = 0
        for (t in m.trozosEnMemoria().toList()) {
            if (!t.sucio) continue
            // Se copia primero: si mientras se escribe sigues pintando, el trozo queda sucio para la próxima vez
            val v = t.version
            val c = synchronized(t) { t.copia() }
            escribeTrozo(m.archivoTrozo(t.cx, t.cy), c)
            m.apuntaEnDisco(t.cx, t.cy)
            if (t.version == v) t.sucio = false
            n++
        }
        val o = m.metaJson().put("vector", m.vectorJson())
        val tmp = File(dir, "mapa.json.tmp")
        tmp.writeText(o.toString())
        val f = File(dir, "mapa.json")
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return n
    }

    fun leeMapa(dir: File): Mapa? = runCatching {
        val o = JSONObject(File(dir, "mapa.json").readText())
        val m = Mapa(o.getString("id"), o.optString("mundo"), o.optString("nombre"), o.getLong("semilla"),
            runCatching { Generador.Estilo.valueOf(o.optString("estilo")) }.getOrDefault(Generador.Estilo.CONTINENTES),
            o.optInt("w"), o.optInt("h"), o.optDouble("tierra", 0.42).toFloat(), dir)
        m.kmPorPx = o.optDouble("km", 1.0).toFloat()
        m.escalaPuesta = o.optBoolean("kmPuesta")
        o.optJSONArray("obras")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { m.obras += it } }
        m.creado = o.optLong("creado", m.creado)
        m.modificado = o.optLong("modificado", m.modificado)
        o.optJSONArray("vista")?.let { a -> if (a.length() == 3) for (i in 0 until 3) m.vista[i] = if (a.isNull(i)) Float.NaN else a.optDouble(i).toFloat() }
        o.optJSONObject("vector")?.let { m.ponVector(it) }
        File(dir, "trozos").listFiles()?.forEach { f ->
            val p = f.name.removeSuffix(".bin").split('_')
            if (f.name.endsWith(".bin") && p.size == 2) { val cx = p[0].toIntOrNull(); val cy = p[1].toIntOrNull(); if (cx != null && cy != null) m.apuntaEnDisco(cx, cy) }
        }
        m
    }.getOrNull()

    /** La carpeta del mapa en un zip (lo que se sube a la nube). */
    fun empaqueta(dir: File): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun desempaqueta(datos: ByteArray, dir: File): Boolean {
        var ok = false
        dir.mkdirs()
        ZipInputStream(datos.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                if (e.isDirectory || n.contains("..") || n.startsWith("/")) continue
                val f = File(dir, n)
                f.parentFile?.mkdirs()
                f.outputStream().use { z.copyTo(it) }
                if (n == "mapa.json") ok = true
            }
        }
        return ok
    }
}
