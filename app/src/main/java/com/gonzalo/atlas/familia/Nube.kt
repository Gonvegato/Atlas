package com.gonzalo.atlas.familia

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gonzalo.atlas.editor.Lamina
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.ui.EstadoApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.round

/** Subir y bajar mapas de la cuenta (la ficha en familia/atlas, el archivo y la lámina en Drive, la miniatura en familia_img). */
object Nube {
    private val principal = Handler(Looper.getMainLooper())
    private var ultimaLamina = HashMap<String, Long>()

    /** Sube el mapa abierto en un hilo aparte (con aviso en [Cuenta.estado]). */
    fun subeEnSegundoPlano(e: EstadoApp, manual: Boolean) {
        val ed = e.editor ?: return
        val c = e.cuenta
        if (!c.dentro || c.subiendo) return
        c.subiendo = true
        c.estado = "Subiendo «${ed.mapa.nombre}»…"
        ed.guardaYa {
            Thread {
                val r = runCatching { sube(e, ed, manual) }.getOrElse { "Error: ${it.message}" }
                principal.post { c.subiendo = false; c.estado = if (r.isBlank()) "Subido a las " + java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) } else r }
            }.start()
        }
    }

    /** Sube un mapa ya guardado. "" o el error. La lámina se rehace como mucho cada 10 minutos (o siempre si es a mano). */
    fun sube(e: EstadoApp, ed: Editor, manual: Boolean): String {
        val c = e.cuenta
        val m = ed.mapa
        if (!c.dentro) return "Sin sesión en la cuenta."
        if (c.driveToken().isBlank()) return "Tu Google Drive no está configurado en la cuenta (se pone una vez en Polaris)."
        val dir = m.dir ?: return "El mapa no está guardado."
        val datos = empaqueta(dir)
        val nombre = m.id + ".atlas"
        val gd = c.sube(nombre, datos)
        if (gd.isBlank()) return "No se pudo subir el mapa a Google Drive."
        val campos = LinkedHashMap<String, Any?>()
        campos["t"] = m.nombre
        campos["obra"] = m.obras.firstOrNull() ?: ""
        campos["obras"] = m.obras.joinToString(",")
        campos["mundo"] = m.mundo
        // De qué es (mundo, continente, región, ciudad, lugar, otro): el visor de la familia lo pone en su ficha
        campos["k"] = m.tipo
        val l = ed.lienzo.limites()
        val w = l.width().toFloat(); val h = l.height().toFloat()
        campos["w"] = l.width(); campos["h"] = l.height()
        campos["nube"] = JSONObject().put("gd", gd).put("n", nombre).put("s", datos.size)
        // Los lugares, con su posición sobre la lámina (0..1), para el BUSCAR del visor de los escritos
        campos["lug"] = JSONArray().also { a ->
            m.lugares.forEach { p -> a.put(JSONObject().put("n", p.nombre).put("k", p.tipo).put("x", r4(((p.x - l.left) / w).toFloat())).put("y", r4(((p.y - l.top) / h).toFloat()))) }
        }.toString()
        val ahora = System.currentTimeMillis()
        if (manual || ahora - (ultimaLamina[m.id] ?: 0L) > 10 * 60_000L) {
            val capas = com.gonzalo.atlas.vista.Capas(e.ctx)
            val b = Lamina.pinta(ed, 3072, capas) { e.recursos.bitmap(it) }
            val o = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, o)
            val nl = m.id + ".lamina.webp"
            val gl = c.sube(nl, o.toByteArray(), "image/webp")
            if (gl.isNotBlank()) { campos["lam"] = JSONObject().put("gd", gl).put("n", nl).put("s", o.size()).put("w", b.width).put("h", b.height); ultimaLamina[m.id] = ahora }
        }
        campos["ul"] = ahora
        val mini = File(dir, "miniatura.png")
        if (mini.exists()) {
            val b = android.graphics.BitmapFactory.decodeFile(mini.path)
            if (b != null) {
                val k = 256f / maxOf(b.width, b.height)
                val p = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
                val o = ByteArrayOutputStream(); p.compress(Bitmap.CompressFormat.PNG, 100, o)
                campos["portada"] = c.subeImagen(o.toByteArray())
            }
        }
        return c.escribe("atlas", m.id, campos)
    }

    private fun r4(v: Float) = round(v * 10000.0) / 10000.0

    /** La carpeta del mapa en un zip (lo que se sube a la nube: un .atlas). */
    fun empaqueta(dir: File): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun desempaqueta(datos: ByteArray, dir: File): Boolean {
        var ok = false
        dir.mkdirs()
        java.util.zip.ZipInputStream(datos.inputStream()).use { z ->
            while (true) {
                val en = z.nextEntry ?: break
                val n = en.name
                if (en.isDirectory || n.contains("..") || n.startsWith("/")) continue
                val f = File(dir, n); f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
                if (n == "mapa.json") ok = true
            }
        }
        return ok
    }

    /** Baja un mapa de la cuenta a la biblioteca. "" o el error. */
    fun baja(e: EstadoApp, mn: Cuenta.MapaNube): String {
        if (mn.nubeGd.isBlank()) return "Ese mapa no tiene archivo en Google Drive."
        val datos = e.cuenta.baja(mn.nubeGd) ?: return "No se pudo bajar el mapa de Google Drive."
        val dir = e.bib.dirMapa(mn.id)
        val tmp = File(dir.path + ".bajando"); tmp.deleteRecursively()
        if (!desempaqueta(datos, tmp) || com.gonzalo.atlas.motor.Mapa.lee(tmp) == null) { tmp.deleteRecursively(); return "Ese archivo no es un mapa de esta versión de ATLAS (quizá es de la de Godot)." }
        principal.post { e.cierraMapaSiEs(mn.id) }
        Thread.sleep(300)
        dir.deleteRecursively(); tmp.renameTo(dir)
        return ""
    }

    fun borraDeLaNube(e: EstadoApp, id: String): String = e.cuenta.escribe("atlas", id, mapOf("b" to System.currentTimeMillis()))
}

/**
 * Entrar una vez: la app de la familia que ya tiene la cuenta se la da a sus hermanas, solo a las firmadas con la misma clave
 * (la de la familia). El mismo contrato que `familia/Acceso.kt` de Familia-Revista: content://<paquete>.familia.acceso/sesion,
 * con las columnas usuario y contrasena.
 */
object Acceso {
    /** La huella SHA-256 del certificado de la familia. */
    val CERTIFICADO: ByteArray = "1d3d3e1a631bbbfdff5e05d449a0fb52f968dae87c596a9bdd4a23fe08671463".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Las apps de la familia (las que pueden darse la cuenta). */
    val APPS = listOf("com.gonzalo.hiedra", "com.gonzalo.marfil", "com.gonzalo.marfil.seskobo", "com.gonzalo.cobalto", "com.gonzalo.gamerate",
        "com.gonzalo.mostaza", "com.gonzalo.cobre", "com.gonzalo.ammit", "com.gonzalo.ptah", "com.gonzalo.shai", "com.gonzalo.atlas")

    fun esDeLaFamilia(ctx: Context, paquete: String): Boolean = paquete in APPS && runCatching {
        ctx.packageManager.hasSigningCertificate(paquete, CERTIFICADO, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    /** Sin sesión aquí: se pregunta a las hermanas instaladas y se entra con la de la primera que la tenga. Bloquea (red). */
    fun pideAHermanas(ctx: Context): Boolean {
        val c = Cuenta.de(ctx)
        if (c.dentro) return true
        if (!esDeLaFamilia(ctx, ctx.packageName)) return false
        for (p in APPS) {
            if (p == ctx.packageName || !esDeLaFamilia(ctx, p)) continue
            val par = runCatching {
                ctx.contentResolver.query(Uri.parse("content://$p.familia.acceso/sesion"), null, null, null, null)?.use { cu -> if (cu.moveToFirst()) cu.getString(0) to cu.getString(1) else null }
            }.getOrNull() ?: continue
            if (c.entrar(par.first, par.second).isBlank()) return true
        }
        return false
    }
}

/** Da la cuenta a las hermanas de la familia (y a nadie más). */
class AccesoProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val quien = callingPackage ?: return null
        if (quien == ctx.packageName || !Acceso.esDeLaFamilia(ctx, quien) || !Acceso.esDeLaFamilia(ctx, ctx.packageName)) return null
        val par = Cuenta.de(ctx).credenciales() ?: return null
        return MatrixCursor(arrayOf("usuario", "contrasena")).apply { addRow(arrayOf(par.first, par.second)) }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
