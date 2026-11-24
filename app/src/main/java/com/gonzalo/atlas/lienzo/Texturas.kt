package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.gonzalo.atlas.motor.Ruido
import com.gonzalo.atlas.motor.RuidoTabla
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Una textura del motor de pinceles: cuadrada, un byte por píxel (cuánto pinta: 0 nada, 255 todo). Sirve de punta (la forma
 * que se estampa) o de grano (el papel que asoma). Las mitades sucesivas ([mitad]) evitan dientes al estampar pequeño.
 */
class Textura(val lado: Int, val a: ByteArray, val fina: Boolean = false) {
    /**
     * La mitad de grande. En las puntas ([fina]) no es la media sin más: se queda a medio camino del valor más alto, para
     * que las líneas y motas finas (una montaña a tinta, las cerdas de un pincel seco) no se apaguen al estampar pequeño.
     */
    val mitad: Textura? by lazy {
        if (lado <= 8) null else {
            val l = lado / 2; val o = ByteArray(l * l)
            for (y in 0 until l) for (x in 0 until l) {
                val i = (y * 2) * lado + x * 2
                val p0 = a[i].toInt() and 255; val p1 = a[i + 1].toInt() and 255; val p2 = a[i + lado].toInt() and 255; val p3 = a[i + lado + 1].toInt() and 255
                val media = (p0 + p1 + p2 + p3 + 2) shr 2
                o[y * l + x] = (if (fina) (media + maxOf(maxOf(p0, p1), maxOf(p2, p3)) + 1) shr 1 else media).toByte()
            }
            Textura(l, o, fina)
        }
    }

    /** La reducción más pequeña que aún tiene al menos [px] píxeles de lado. */
    fun nivel(px: Float): Textura { var t = this; while (t.lado / 2 >= px) t = t.mitad ?: break; return t }

    /** Como imagen (blanca o de [color]) con la textura de transparencia: para enseñarla. */
    fun bitmap(color: Int = Color.WHITE): Bitmap {
        val c = color and 0xFFFFFF
        return Bitmap.createBitmap(IntArray(lado * lado) { ((a[it].toInt() and 255) shl 24) or c }, lado, lado, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** De una imagen: su transparencia (si es toda opaca, su claridad). Se encaja en un cuadrado. */
        fun de(b: Bitmap, maxLado: Int = 512, fina: Boolean = false): Textura {
            val l0 = max(b.width, b.height)
            val l = if (l0 > maxLado) maxLado else l0
            val src = if (b.width == l && b.height == l) b else Bitmap.createBitmap(l, l, Bitmap.Config.ARGB_8888).also { d ->
                val k = l.toFloat() / l0
                val c = Canvas(d); c.translate((l - b.width * k) / 2f, (l - b.height * k) / 2f); c.scale(k, k)
                c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(l * l); src.getPixels(px, 0, l, 0, 0, l, l)
            val opaca = px.all { (it ushr 24) == 255 }
            return Textura(l, ByteArray(l * l) { i -> (if (opaca) (Color.red(px[i]) * 299 + Color.green(px[i]) * 587 + Color.blue(px[i]) * 114) / 1000 else px[i] ushr 24).toByte() }, fina)
        }
    }
}

/** Las puntas y los granos de los pinceles (hechos aquí, sin imágenes de nadie) y los de los pinceles importados. */
object Texturas {
    val PUNTAS = listOf("t_aspera" to "Áspera", "t_carbon" to "Carbón", "t_tiza" to "Tiza", "t_cerdas" to "Cerdas", "t_esponja" to "Esponja", "t_salpicadura" to "Salpicadura",
        "t_hojas" to "Hojas", "t_piedra" to "Piedras", "t_hierba" to "Hierba", "t_ola" to "Ola", "t_montana" to "Montaña", "t_colina" to "Colina", "t_pino" to "Pino",
        "t_arbol" to "Árbol", "t_duna" to "Duna", "t_pantano" to "Pantano")
    val GRANOS = listOf("g_papel" to "Papel", "g_acuarela" to "Papel de acuarela", "g_lienzo" to "Lienzo", "g_ruido" to "Ruido", "g_rayado" to "Rayado", "g_piedra" to "Piedra", "g_arena" to "Arena")

    private val hechas = ConcurrentHashMap<String, Textura>()
    private val puestas = ConcurrentHashMap<String, Textura>()
    private const val L = 256

    /** La textura con ese nombre (se hace la primera vez que se pide), o null si no existe. */
    fun de(id: String): Textura? {
        if (id.isBlank()) return null
        puestas[id]?.let { return it }
        hechas[id]?.let { return it }
        val t = runCatching { genera(id) }.getOrNull()?.let { if (id.startsWith("t_")) Textura(it.lado, it.a, true) else it } ?: return null
        hechas[id] = t
        return t
    }

    /** Añade la de un pincel importado. */
    fun pon(id: String, t: Textura) { puestas[id] = t }

    private fun genera(id: String): Textura? = when (id) {
        "t_suave" -> punta { d, _, _ -> val t = 1f - d; t * t * (3f - 2f * t) }
        "t_aspera" -> punta { d, x, y -> val n = Ruido.hash01(x / 5, y / 5, 5); val m = Ruido.hash01(x / 2, y / 2, 6); if (d < 0.72f + n * 0.28f) (0.55f + m * 0.45f) else 0f }
        "t_carbon" -> punta { d, x, y -> val n = Ruido.hash01(x / 3, y / 3, 9); val m = Ruido.hash01(x, y, 4); if (d < 0.95f && n > 0.22f + d * 0.6f) 0.45f + m * 0.55f else 0f }
        "t_tiza" -> tiza()
        "t_cerdas" -> cerdas()
        "t_esponja" -> esponja()
        "t_salpicadura" -> salpicadura()
        "t_hojas" -> hojas()
        "t_piedra" -> piedras()
        "t_hierba" -> hierba()
        "t_ola" -> dibujo(0.06f) { c, p -> val q = Path(); q.moveTo(0.06f * L, 0.56f * L); q.cubicTo(0.25f * L, 0.3f * L, 0.4f * L, 0.3f * L, 0.5f * L, 0.5f * L); q.cubicTo(0.6f * L, 0.7f * L, 0.78f * L, 0.7f * L, 0.94f * L, 0.44f * L); c.drawPath(q, p) }
        "t_montana" -> montana()
        "t_colina" -> colina()
        "t_pino" -> pino()
        "t_arbol" -> arbol()
        "t_duna" -> duna()
        "t_pantano" -> pantano()
        "g_papel" -> grano(7, 3f, 0.35f)
        "g_acuarela" -> grano(23, 5f, 0.18f)
        "g_lienzo" -> lienzo()
        "g_ruido" -> grano(11, 1.2f, 0.6f)
        "g_rayado" -> bmp { x, y -> val d = ((x + y) % 32) / 32f; val n = Ruido.hash01(x / 2, y / 2, 3) * 0.12f; if (d < 0.3f) 1f - n else if (d < 0.38f) 0.5f else 0.1f + n }
        "g_piedra" -> grano(13, 8f, 0.2f)
        "g_arena" -> grano(17, 0.8f, 0.8f)
        else -> null
    }

    private fun bmp(f: (Int, Int) -> Float) = Textura(L, ByteArray(L * L) { i -> (f(i % L, i / L).coerceIn(0f, 1f) * 255).toInt().toByte() })

    private fun punta(f: (Float, Int, Int) -> Float): Textura {
        val c = (L - 1) / 2f
        return bmp { x, y -> val d = hypot(x - c, y - c) / c; if (d >= 1f) 0f else f(d, x, y) }
    }

    private fun grano(semilla: Long, escala: Float, fino: Float): Textura {
        val t = RuidoTabla(L, semilla, L / escala / 8f, 3)
        return bmp { x, y -> (t.v[y * L + x] * 0.5f + 0.5f) * (1f - fino) + Ruido.hash01(x, y, semilla.toInt()) * fino }
    }

    /** La trama de un lienzo: hilos que se cruzan, cada uno un poco distinto (para que no parezca una rejilla perfecta). */
    private fun lienzo(): Textura {
        val t = RuidoTabla(L, 71, 20f, 2)
        return bmp { x, y ->
            val hx = x / 16; val hy = y / 16
            val a = sin((x % 16) / 16f * PI.toFloat()); val b = sin((y % 16) / 16f * PI.toFloat())
            val encima = (hx + hy) % 2 == 0
            val hilo = if (encima) a * (0.7f + 0.3f * Ruido.hash01(hx, 0, 81)) else b * (0.7f + 0.3f * Ruido.hash01(0, hy, 83))
            val otro = if (encima) b else a
            (0.25f + 0.55f * hilo + 0.2f * otro * hilo + t.v[y * L + x] * 0.12f)
        }
    }

    private fun deMapa(px: FloatArray) = bmp { x, y -> px[y * L + x] }

    private fun tiza(): Textura {
        val t = RuidoTabla(L, 41, 9f, 3)
        return punta { d, x, y -> val n = t.v[y * L + x] * 0.5f + 0.5f; val m = Ruido.hash01(x / 2, y / 2, 21); val borde = 0.8f + n * 0.2f; if (d > borde) 0f else ((0.35f + n * 0.65f) * (0.6f + m * 0.4f) * ((borde - d) * 10f).coerceAtMost(1f)) }
    }

    /** Un pincel plano de cerdas: rayas a lo largo (x), más claras o más cargadas. */
    private fun cerdas(): Textura = bmp { x, y ->
        val u = (x - L / 2f) / (L / 2f); val v = (y - L / 2f) / (L / 2f)
        val dentro = abs(v) < 0.92f && abs(u) < 0.34f + 0.1f * Ruido.hash01(y / 3, 0, 31)
        if (!dentro) 0f else { val cerda = Ruido.hash01(y / 3, 1, 33); val falta = Ruido.hash01(y / 5, 2, 35); if (falta < 0.12f) 0f else (0.4f + 0.6f * cerda) * (1f - abs(u) * 1.6f).coerceIn(0.15f, 1f) }
    }

    private fun esponja(): Textura {
        val t = RuidoTabla(L, 53, 14f, 3)
        return punta { d, x, y -> val n = t.v[y * L + x] * 0.5f + 0.5f; if (n < 0.42f + d * 0.25f) 0f else ((n - 0.42f) * 3.2f).coerceAtMost(1f) * (1f - d * d) }
    }

    private fun salpicadura(): Textura {
        val px = FloatArray(L * L)
        for (k in 0 until 30) {
            val a = Ruido.hash01(k, 1, 7) * 2 * PI; val r = Ruido.hash01(k, 2, 7) * 100f; val s = 3f + Ruido.hash01(k, 3, 7) * Ruido.hash01(k, 4, 7) * 22f
            val cx = L / 2 + cos(a).toFloat() * r; val cy = L / 2 + sin(a).toFloat() * r
            disco(px, cx, cy, s, 1f)
        }
        return deMapa(px)
    }

    private fun disco(px: FloatArray, cx: Float, cy: Float, r: Float, v: Float) {
        for (y in (cy - r - 1).toInt().coerceAtLeast(0)..(cy + r + 1).toInt().coerceAtMost(L - 1)) for (x in (cx - r - 1).toInt().coerceAtLeast(0)..(cx + r + 1).toInt().coerceAtMost(L - 1)) {
            val q = (r - hypot(x - cx, y - cy) + 0.5f).coerceIn(0f, 1f) * v
            if (q > px[y * L + x]) px[y * L + x] = q
        }
    }

    private fun hojas(): Textura {
        val px = FloatArray(L * L)
        for (k in 0 until 14) {
            val a = Ruido.hash01(k, 1, 3) * 2 * PI; val r = Ruido.hash01(k, 2, 3) * 80f
            val cx = L / 2 + cos(a).toFloat() * r; val cy = L / 2 + sin(a).toFloat() * r; val g = Ruido.hash01(k, 4, 3) * PI.toFloat()
            val v = 0.7f + 0.3f * Ruido.hash01(k, 5, 3)
            for (y in 0 until L) for (x in 0 until L) {
                val dx = x - cx; val dy = y - cy
                val u = dx * cos(g) + dy * sin(g); val w = -dx * sin(g) + dy * cos(g)
                val e = (u / 32f) * (u / 32f) + (w / 14f) * (w / 14f)
                if (e < 1f) px[y * L + x] = maxOf(px[y * L + x], v * ((1f - e) * 6f).coerceAtMost(1f))
            }
        }
        return deMapa(px)
    }

    private fun piedras(): Textura {
        val px = FloatArray(L * L)
        for (k in 0 until 16) {
            val cx = 30 + Ruido.hash01(k, 1, 11) * (L - 60); val cy = 30 + Ruido.hash01(k, 2, 11) * (L - 60); val s = 9 + Ruido.hash01(k, 3, 11) * 20
            for (y in 0 until L) for (x in 0 until L) { val d = hypot(x - cx, (y - cy) * 1.3f) / s; if (d < 1f) px[y * L + x] = if (d > 0.78f) 1f else 0.3f }
        }
        return deMapa(px)
    }

    /** Dibuja con trazos de tinta (líneas gruesas de punta redonda) en un cuadrado de [L] px. */
    private fun dibujo(grosor: Float = 0.045f, f: (Canvas, Paint) -> Unit): Textura {
        val b = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = grosor * L; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
        f(Canvas(b), p)
        val px = IntArray(L * L); b.getPixels(px, 0, L, 0, 0, L, L)
        return Textura(L, ByteArray(L * L) { (px[it] ushr 24).toByte() })
    }

    private fun linea(c: Canvas, p: Paint, vararg q: Float) { val pa = Path(); pa.moveTo(q[0] * L, q[1] * L); var i = 2; while (i + 1 < q.size) { pa.lineTo(q[i] * L, q[i + 1] * L); i += 2 }; c.drawPath(pa, p) }

    private fun hierba(): Textura = dibujo(0.04f) { c, p ->
        for (k in 0 until 7) {
            val bx = 0.2f + k * 0.1f; val alto = 0.25f + Ruido.hash01(k, 2, 13) * 0.4f; val curva = (Ruido.hash01(k, 3, 13) - 0.5f) * 0.3f
            val q = Path(); q.moveTo(bx * L, 0.86f * L); q.quadTo((bx + curva * 0.3f) * L, (0.86f - alto * 0.6f) * L, (bx + curva) * L, (0.86f - alto) * L); c.drawPath(q, p)
        }
    }

    private fun montana(): Textura = dibujo(0.04f) { c, p ->
        // La silueta: sube por la izquierda, cumbre, baja con un quiebro
        linea(c, p, 0.06f, 0.84f, 0.26f, 0.5f, 0.34f, 0.56f, 0.52f, 0.16f, 0.66f, 0.42f, 0.74f, 0.4f, 0.95f, 0.84f)
        // Sombra de la ladera derecha: rayas que bajan desde la arista
        p.strokeWidth = 0.022f * L
        linea(c, p, 0.52f, 0.16f, 0.5f, 0.42f, 0.57f, 0.56f, 0.52f, 0.8f)
        for (i in 0 until 5) { val t = i / 5f; linea(c, p, 0.58f + t * 0.22f, 0.36f + t * 0.3f, 0.6f + t * 0.16f, 0.56f + t * 0.22f) }
    }

    private fun colina(): Textura = dibujo(0.04f) { c, p ->
        val q = Path(); q.moveTo(0.06f * L, 0.78f * L); q.cubicTo(0.22f * L, 0.3f * L, 0.7f * L, 0.22f * L, 0.94f * L, 0.78f * L); c.drawPath(q, p)
        p.strokeWidth = 0.022f * L
        for (i in 0 until 4) { val t = i / 4f; linea(c, p, 0.62f + t * 0.2f, 0.44f + t * 0.2f, 0.6f + t * 0.2f, 0.58f + t * 0.14f) }
    }

    private fun pino(): Textura = dibujo(0.035f) { c, p ->
        linea(c, p, 0.5f, 0.74f, 0.5f, 0.92f)
        val relleno = Paint(p).apply { style = Paint.Style.FILL_AND_STROKE }
        for (i in 0 until 3) {
            val y0 = 0.1f + i * 0.2f; val w = 0.16f + i * 0.09f
            val q = Path(); q.moveTo(0.5f * L, y0 * L); q.lineTo((0.5f + w) * L, (y0 + 0.3f) * L); q.lineTo((0.5f - w) * L, (y0 + 0.3f) * L); q.close(); c.drawPath(q, relleno)
        }
    }

    private fun arbol(): Textura = dibujo(0.04f) { c, p ->
        linea(c, p, 0.5f, 0.62f, 0.5f, 0.92f)
        linea(c, p, 0.5f, 0.74f, 0.6f, 0.66f)
        // La copa: bultos alrededor
        for (k in 0 until 9) { val a = k / 9f * 2 * PI; val cx = 0.5f + cos(a).toFloat() * 0.2f; val cy = 0.38f + sin(a).toFloat() * 0.17f; c.drawCircle(cx * L, cy * L, 0.11f * L, p) }
        val borra = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        c.drawOval(0.3f * L, 0.22f * L, 0.7f * L, 0.54f * L, borra)
        p.strokeWidth = 0.02f * L
        linea(c, p, 0.58f, 0.3f, 0.64f, 0.4f); linea(c, p, 0.5f, 0.34f, 0.56f, 0.46f)
    }

    private fun duna(): Textura = dibujo(0.05f) { c, p ->
        val q = Path(); q.moveTo(0.05f * L, 0.66f * L); q.cubicTo(0.3f * L, 0.3f * L, 0.45f * L, 0.3f * L, 0.56f * L, 0.52f * L); q.cubicTo(0.66f * L, 0.36f * L, 0.82f * L, 0.4f * L, 0.95f * L, 0.66f * L); c.drawPath(q, p)
        for (k in 0 until 7) c.drawPoint((0.2f + Ruido.hash01(k, 1, 61) * 0.6f) * L, (0.6f + Ruido.hash01(k, 2, 61) * 0.22f) * L, p)
    }

    private fun pantano(): Textura = dibujo(0.045f) { c, p ->
        linea(c, p, 0.1f, 0.72f, 0.42f, 0.72f); linea(c, p, 0.56f, 0.8f, 0.9f, 0.8f); linea(c, p, 0.3f, 0.88f, 0.52f, 0.88f)
        for (k in 0 until 3) { val bx = 0.26f + k * 0.06f; linea(c, p, bx, 0.7f, bx + (k - 1) * 0.07f, 0.4f + abs(k - 1) * 0.08f) }
        for (k in 0 until 3) { val bx = 0.68f + k * 0.06f; linea(c, p, bx, 0.78f, bx + (k - 1) * 0.06f, 0.54f + abs(k - 1) * 0.07f) }
    }
}
