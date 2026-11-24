package com.gonzalo.atlas.motor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Cómo se ve el mapa (lo que se puede cambiar en Mundo → Aspecto). */
class Aspecto(
    /** Cuánto se funden los biomas entre sí (px del mapa). */
    var fundido: Float = 14f,
    /** Exageración del relieve (sombreado). */
    var relieve: Float = 1f,
    var colorReinos: Float = 0.2f,
    var bordesReinos: Boolean = true,
    var ondas: Boolean = true,
    var texturas: Float = 1f,
    var regiones: Boolean = true,
    var zonas: Boolean = true,
)

/**
 * Pinta el mapa en píxeles (ARGB), sin Android: el mismo código para la pantalla, la lámina y las pruebas.
 * El píxel (i, j) de la salida es el punto del mapa (x0 + (i + ½)·escala, y0 + (j + ½)·escala).
 */
class Pintor(private val mapa: Mapa, var aspecto: Aspecto = Aspecto()) {

    private class Muestras(val w: Int, val h: Int) {
        val alt = FloatArray((w + 2) * (h + 2))
        val bio = IntArray(w * h)
        val reino = IntArray((w + 2) * (h + 2))
        val region = IntArray((w + 2) * (h + 2))
        val zona = IntArray((w + 2) * (h + 2))
    }

    /** Un lector de mips con caché del último trozo (cada hilo el suyo). */
    private inner class Lector(val nivel: Int) {
        val lado = Trozo.LADO shr nivel
        val bits = Trozo.BITS - nivel
        private var ck = Long.MIN_VALUE
        private var m: Mip? = null
        fun mip(cx: Int, cy: Int): Mip? {
            val k = Trozo.clave(cx, cy)
            if (k != ck) { ck = k; m = if (nivel == 0) null else mapa.mip(cx, cy, nivel); tr = if (nivel == 0) mapa.trozo(cx, cy) else null }
            return m
        }
        private var tr: Trozo? = null
        /** Altura del píxel (u, v) del nivel. */
        fun alt(u: Int, v: Int): Float {
            val cx = u shr bits; val cy = v shr bits
            val mm = mip(cx, cy)
            val i = (v and (lado - 1)) * lado + (u and (lado - 1))
            return if (nivel == 0) tr?.alt?.get(i) ?: -0.3f else mm?.alt?.get(i) ?: -0.3f
        }
        fun ids(u: Int, v: Int, que: Int): Int {
            val cx = u shr bits; val cy = v shr bits
            val mm = mip(cx, cy)
            val i = (v and (lado - 1)) * lado + (u and (lado - 1))
            val a = if (nivel == 0) tr?.let { when (que) { 0 -> it.bio; 1 -> it.reino; 2 -> it.region; else -> it.zona } }
                    else mm?.let { when (que) { 0 -> it.bio; 1 -> it.reino; 2 -> it.region; else -> it.zona } }
            return (a?.get(i)?.toInt() ?: 0) and 0xff
        }
    }

    private fun dentro(x: Float, y: Float) = mapa.infinito || (x >= 0 && y >= 0 && x < mapa.ancho && y < mapa.alto)

    fun pinta(x0: Float, y0: Float, escala: Float, w: Int, h: Int, out: IntArray) {
        val nivel = if (escala <= 1f) 0 else floor(ln(escala) / ln(2f)).toInt().coerceIn(0, 6)
        val f = (1 shl nivel).toFloat()
        val lec = Lector(nivel)
        val ww = w + 2
        val s = Muestras(w, h)
        // 1. Muestras con un píxel de borde (para las pendientes y los bordes)
        for (j in -1..h) {
            val my = y0 + (j + 0.5f) * escala
            for (i in -1..w) {
                val mx = x0 + (i + 0.5f) * escala
                val k = (j + 1) * ww + (i + 1)
                val u = mx / f - 0.5f; val v = my / f - 0.5f
                val fu = floor(u); val fv = floor(v)
                val iu = fu.toInt(); val iv = fv.toInt(); val tu = u - fu; val tv = v - fv
                val a = lec.alt(iu, iv); val b = lec.alt(iu + 1, iv); val c = lec.alt(iu, iv + 1); val d = lec.alt(iu + 1, iv + 1)
                s.alt[k] = (a + (b - a) * tu) * (1 - tv) + (c + (d - c) * tu) * tv
                val nu = floor(mx / f).toInt(); val nv = floor(my / f).toInt()
                s.reino[k] = lec.ids(nu, nv, 1)
                s.region[k] = lec.ids(nu, nv, 2)
                s.zona[k] = lec.ids(nu, nv, 3)
            }
        }
        // 2. Color de cada píxel
        val asp = aspecto
        val fund = max(asp.fundido, escala * 1.2f)
        for (j in 0 until h) {
            val my = y0 + (j + 0.5f) * escala
            for (i in 0 until w) {
                val mx = x0 + (i + 0.5f) * escala
                val o = j * w + i
                if (!dentro(mx, my)) { out[o] = MESA; continue }
                val k = (j + 1) * ww + (i + 1)
                val a = s.alt[k]
                val gx = (s.alt[k + 1] - s.alt[k - 1]) / (2f * escala)
                val gy = (s.alt[k + ww] - s.alt[k - ww]) / (2f * escala)
                val g = sqrt(gx * gx + gy * gy) + 1e-7f
                // Bioma con fundido: tres muestras desplazadas con ruido, mezcladas
                val jx = TJ.suave(mx / 23f, my / 23f) * fund; val jy = TJ.suave(mx / 23f + 71f, my / 23f + 13f) * fund
                val b1 = lec.ids(floor((mx + jx) / f).toInt(), floor((my + jy) / f).toInt(), 0)
                val b2 = lec.ids(floor((mx - jy * 0.7f) / f).toInt(), floor((my + jx * 0.7f) / f).toInt(), 0)
                val b0 = lec.ids(floor(mx / f).toInt(), floor(my / f).toInt(), 0)
                val agua = a < 0f || Catalogo.bioma(b0)?.esAgua == true
                var col: Int
                if (agua) {
                    col = colorAgua(a, b0, mx, my, g, escala)
                } else {
                    val c0 = colorTierra(b0, a, mx, my, escala)
                    val c1 = if (b1 == b0) c0 else colorTierra(b1, a, mx, my, escala)
                    val c2 = if (b2 == b0) c0 else colorTierra(b2, a, mx, my, escala)
                    col = mezcla3(c0, c1, c2)
                    col = conAltura(col, a, g, mx, my)
                    // Sombreado (luz del noroeste)
                    val ex = 70f * asp.relieve
                    val nx = -gx * ex; val ny = -gy * ex
                    val inv = 1f / sqrt(nx * nx + ny * ny + 1f)
                    val luz = (nx * -0.55f + ny * -0.55f + 0.63f) * inv / 0.63f
                    val som = (0.62f + 0.42f * luz).coerceIn(0.35f, 1.2f)
                    col = escalaColor(col, som)
                    if (asp.zonas) { val z = s.zona[k]; if (z != 0) col = zona(col, z, mx, my, escala, s, k, ww) }
                    // Reinos: un velo del color del reino y una franja junto a la frontera
                    val r = s.reino[k]
                    if (r != 0) {
                        val rc = colorReino(r)
                        if (asp.colorReinos > 0f) col = mezcla(col, rc, asp.colorReinos)
                        if (asp.bordesReinos) {
                            val o3 = (3.5f).toInt()
                            if (s.reino[k + 1] != r || s.reino[k - 1] != r || s.reino[k + ww] != r || s.reino[k - ww] != r) {
                                col = mezcla(col, oscuro(rc), 0.85f)
                            } else if (franja(s, k, ww, r, o3)) col = mezcla(col, rc, 0.32f)
                        }
                    }
                    if (asp.regiones) {
                        val rg = s.region[k]
                        if ((s.region[k + 1] != rg || s.region[k + ww] != rg) && ((floor(mx / (3f * max(1f, escala))) + floor(my / (3f * max(1f, escala)))).toInt() and 1) == 0) col = mezcla(col, TINTA, 0.45f)
                    }
                }
                // La costa: una línea de tinta de grosor fijo en pantalla
                val dCosta = abs(a) / (g * escala)
                if (dCosta < 1.6f && cruzaCero(s.alt, k, ww)) {
                    val t = 1f - Generador.suave(0.35f, 1.1f, dCosta)
                    col = mezcla(col, TINTA_COSTA, t * 0.9f)
                }
                // Grano de papel
                val pp = PAPEL_GRANO.en((mx / max(1f, escala * 0.8f)).toInt(), (my / max(1f, escala * 0.8f)).toInt())
                col = escalaColor(col, 1f + pp * 0.035f)
                out[o] = col
            }
        }
    }

    /** ¿Pasa el nivel del mar cerca de este píxel? (si no, una ladera empinada parecería costa) */
    private fun cruzaCero(a: FloatArray, k: Int, ww: Int): Boolean {
        var mn = a[k]; var mx = a[k]
        for (o in intArrayOf(1, -1, ww, -ww, ww + 1, ww - 1, -ww + 1, -ww - 1)) { val v = a[k + o]; if (v < mn) mn = v; if (v > mx) mx = v }
        return mn <= 0f && mx >= 0f
    }

    /** ¿Hay otro reino a [d] píxeles (en la pantalla)? Para la franja de color junto a la frontera. */
    private fun franja(s: Muestras, k: Int, ww: Int, r: Int, d: Int): Boolean {
        val ox = k % ww; val oy = k / ww
        for ((dx, dy) in DIRS) {
            val x = ox + dx * d; val y = oy + dy * d
            if (x < 0 || y < 0 || x >= ww || y >= s.h + 2) continue
            val o = s.reino[y * ww + x]
            if (o != r) return true
        }
        return false
    }

    // ---------------------------------------------------------------- colores

    private fun colorAgua(a: Float, bio: Int, x: Float, y: Float, g: Float, escala: Float): Int {
        if (a >= 0f) {
            // Agua dulce sobre tierra (lagos pintados)
            val c = Catalogo.bioma(Catalogo.B_AGUA)!!.color
            return escalaColor(c, 1f + TF.suave(x / 31f, y / 31f) * 0.03f)
        }
        val d = -a
        var c = mezcla(AGUA_SOMERA, AGUA_HONDA, Generador.suave(0f, 0.32f, d))
        c = mezcla(c, AGUA_ABISMO, Generador.suave(0.35f, 0.9f, d) * 0.6f)
        // Ondas a lo largo de la costa (a distancias fijas en el mapa)
        if (aspecto.ondas && escala < 3f) {
            val dist = d / g
            for (q in ONDAS) {
                val dd = abs(dist - q) / escala
                if (dd < 0.9f) c = mezcla(c, ONDA, (1f - dd / 0.9f) * 0.45f * (1f - Generador.suave(1.5f, 3f, escala)))
            }
        }
        when (bio) {
            Catalogo.B_ARRECIFE -> {
                val n = TF.suave(x / 2.2f, y / 2.2f) * 0.5f + 0.5f
                c = mezcla(c, Catalogo.bioma(bio)!!.color, 0.35f + n * 0.4f)
            }
            Catalogo.B_HIELO -> c = mezcla(c, Catalogo.bioma(bio)!!.color, 0.8f + TF.suave(x / 6f, y / 6f) * 0.15f)
        }
        return escalaColor(c, 1f + TF.suave(x / 47f, y / 47f) * 0.025f)
    }

    private fun biomaAuto(a: Float): Int = when {
        a < 0.012f -> Catalogo.B_PLAYA
        a < 0.4f -> Catalogo.B_PRADERA
        a < 0.62f -> Catalogo.B_ESTEPA
        a < 0.8f -> Catalogo.B_ROCA
        else -> Catalogo.B_NIEVE
    }

    private fun colorTierra(b: Int, a: Float, x: Float, y: Float, escala: Float): Int {
        val bio = Catalogo.bioma(if (b == 0) biomaAuto(a) else b) ?: Catalogo.bioma(Catalogo.B_PRADERA)!!
        val c = bio.color
        val k = aspecto.texturas * (1f - Generador.suave(2.5f, 8f, escala) * 0.7f)
        if (k <= 0.01f) return c
        val m: Float = when (bio.textura) {
            Catalogo.Textura.HIERBA -> TF.suave(x / 1.7f, y / 1.7f) * 0.05f + TJ.suave(x / 9f, y / 9f) * 0.05f
            Catalogo.Textura.BOSQUE -> {
                // Copas a pincel: manchas redondeadas más oscuras
                val v = TB.suave(x / 2.6f, y / 2.6f)
                (if (v > 0.15f) -0.12f * Generador.suave(0.15f, 0.5f, v) else 0.04f) + TF.suave(x / 1.3f, y / 1.3f) * 0.03f
            }
            Catalogo.Textura.SELVA -> { val v = TB.suave(x / 1.9f, y / 1.9f); (if (v > -0.05f) -0.14f * Generador.suave(-0.05f, 0.35f, v) else 0.05f) }
            Catalogo.Textura.CONIFERAS -> { val v = TF.suave(x / 1.1f, y / 1.1f); if (v > 0.35f) -0.16f else 0.02f + TJ.suave(x / 7f, y / 7f) * 0.04f }
            Catalogo.Textura.ARENA -> TF.suave(x / 0.9f, y / 0.9f) * 0.03f + TJ.suave(x / 13f, y / 13f) * 0.035f
            Catalogo.Textura.DUNAS -> sin((x * 0.8f + y * 0.35f) / 4.5f + TJ.suave(x / 30f, y / 30f) * 5f) * 0.07f
            Catalogo.Textura.ROCA -> { val v = abs(TB.suave(x / 3.5f, y / 3.5f)); -0.1f * (1f - Generador.suave(0f, 0.12f, v)) + TF.suave(x / 1.5f, y / 1.5f) * 0.05f }
            Catalogo.Textura.NIEVE -> TJ.suave(x / 11f, y / 11f) * 0.03f
            Catalogo.Textura.PANTANO -> { val v = TB.suave(x / 2.2f, y / 2.2f); if (v > 0.4f) -0.16f else TF.suave(x / 1.6f, y / 1.6f) * 0.04f }
            Catalogo.Textura.GRIETAS -> { val v = abs(TB.suave(x / 4f, y / 4f)); if (v < 0.04f) -0.22f else TF.suave(x / 1.3f, y / 1.3f) * 0.04f }
            Catalogo.Textura.CAMPOS -> campos(x, y)
            Catalogo.Textura.LAVA -> {
                val v = abs(TB.suave(x / 3f, y / 3f))
                return if (v < 0.07f) mezcla(0xFF3A2A25.toInt(), c, v / 0.07f * 0.4f) else mezcla(c, 0xFFF4C24B.toInt(), (TJ.suave(x / 5f, y / 5f) * 0.5f + 0.5f) * 0.5f)
            }
            Catalogo.Textura.SAL -> { val v = abs(TB.suave(x / 5f, y / 5f)); if (v < 0.03f) -0.08f else 0f }
            Catalogo.Textura.CORAL, Catalogo.Textura.AGUA, Catalogo.Textura.LISA -> 0f
        }
        return escalaColor(c, 1f + m * k)
    }

    /** Parcelas de cultivo: rectángulos de tonos distintos con surcos. */
    private fun campos(x: Float, y: Float): Float {
        val cs = 9f
        val cx = floor(x / cs).toInt(); val cy = floor(y / (cs * 0.7f)).toInt()
        val hsh = Ruido.hash01(cx, cy, 77)
        val surco = if (((if (hsh > 0.5f) x else y) / 1.1f).toInt() and 1 == 0) -0.03f else 0f
        return (hsh - 0.5f) * 0.16f + surco
    }

    private fun conAltura(c: Int, a: Float, g: Float, x: Float, y: Float): Int {
        var col = c
        val roca = Generador.suave(0.5f, 0.75f, a + TJ.suave(x / 17f, y / 17f) * 0.05f)
        if (roca > 0f) col = mezcla(col, ROCA, roca * 0.65f)
        val nieve = Generador.suave(0.8f, 0.9f, a + TJ.suave(x / 13f, y / 13f) * 0.04f - min(g * 40f, 0.06f))
        if (nieve > 0f) col = mezcla(col, NIEVE, nieve)
        return col
    }

    // ---------------------------------------------------------------- zonas: edificios hechos solos

    private fun zona(c: Int, z: Int, x: Float, y: Float, escala: Float, s: Muestras, k: Int, ww: Int): Int {
        val cs = when (z) { 1 -> 2.6f; 2 -> 5f; 3 -> 7f; 4 -> 6f; 5 -> 11f; 6 -> 4.5f; 7 -> 4f; else -> 4f }
        val borde = s.zona[k + 1] != z || s.zona[k - 1] != z || s.zona[k + ww] != z || s.zona[k - ww] != z
        // Muralla (fortaleza) y empalizada de la ciudad
        if (borde && (z == 6 || z == 1 || z == 3)) return mezcla(c, if (z == 6) 0xFF4A4642.toInt() else 0xFF6B5F52.toInt(), 0.9f)
        val px = cs / escala // píxeles de pantalla por casilla
        // Calles: cada cierto número de casillas, una calle (con un quiebro por manzana)
        val gx = floor(x / cs).toInt(); val gy = floor(y / cs).toInt()
        val fx = x / cs - gx; val fy = y / cs - gy
        val h = Ruido.hash01(gx, gy, z * 13)
        val calle = (z == 1 || z == 2) && (Math.floorMod(gx + (Ruido.hash(gy / 5, 1) and 3), 5) == 0 || Math.floorMod(gy + (Ruido.hash(gx / 5, 2) and 3), 5) == 0)
        if (calle) return mezcla(c, 0xFFD8C9A4.toInt(), 0.7f)
        val vacio = when (z) { 1 -> h < 0.08f; 2 -> h < 0.2f; 3 -> h < 0.35f; 4 -> h < 0.45f; 5 -> false; 6 -> h < 0.15f; 7 -> h < 0.2f; else -> h < 0.5f }
        if (z == 5) {
            // Granjas: parcelas grandes con alguna casa
            val casa = h > 0.82f && fx in 0.35f..0.55f && fy in 0.35f..0.55f
            if (casa) return if (px < 3f) mezcla(c, TEJA, 0.6f) else TEJA
            return escalaColor(mezcla(c, Catalogo.bioma(Catalogo.B_CULTIVOS)!!.color, 0.7f), 0.93f + h * 0.14f)
        }
        if (vacio) return mezcla(c, if (z == 3 || z == 2) 0xFF9DAF6E.toInt() else 0xFFCFC09A.toInt(), 0.45f)
        if (z == 4) {
            // Minas: bocas oscuras y escombreras grises
            val d = sqrt((fx - 0.5f) * (fx - 0.5f) + (fy - 0.5f) * (fy - 0.5f))
            return when { d < 0.16f -> 0xFF2B2522.toInt(); d < 0.38f -> mezcla(c, 0xFF8D867E.toInt(), 0.75f); else -> mezcla(c, 0xFFA59C8C.toInt(), 0.35f) }
        }
        // Casas (o templos, mansiones, almacenes): un rectángulo dentro de la casilla
        val m = when (z) { 3 -> 0.12f; 2 -> 0.18f; 8 -> 0.2f; else -> 0.12f + h * 0.12f }
        val m2 = m + Ruido.hash01(gx, gy, 5) * 0.1f
        val inX = fx > m && fx < 1f - m2; val inY = fy > m2 && fy < 1f - m
        val techo = when (z) {
            2 -> 0xFF6D6A7A.toInt()
            3 -> 0xFFE6DCC4.toInt()
            6 -> 0xFF5F5A55.toInt()
            7 -> 0xFF8A6A48.toInt()
            8 -> 0xFFB8AE9A.toInt()
            else -> if (h > 0.7f) 0xFF8E8780.toInt() else TEJA
        }
        if (px < 2.2f) return mezcla(c, techo, if (z == 8) 0.35f else 0.6f)
        if (!inX || !inY) return mezcla(c, 0xFFD8C9A4.toInt(), 0.55f)
        // Borde de tinta del edificio, de ~1 px de pantalla
        val e = min(min(fx - m, 1f - m2 - fx), min(fy - m2, 1f - m - fy)) * px
        if (z == 8) return if (e < 1f) mezcla(c, 0xFF6E655A.toInt(), 0.8f) else mezcla(c, 0xFFCFC3A8.toInt(), 0.5f)
        if (e < 0.9f) return 0xFF3A3029.toInt()
        // Patio de las mansiones y del templo
        if ((z == 2 || z == 3) && abs(fx - 0.5f) < 0.16f && abs(fy - 0.5f) < 0.16f) return if (z == 3) 0xFFB9A57A.toInt() else 0xFF9DAF6E.toInt()
        // La cumbrera: media casa más oscura (la sombra del tejado)
        val sombra = if (Ruido.hash(gx, gy, 9) and 1 == 0) fx > 0.5f else fy > 0.5f
        return if (sombra) escalaColor(techo, 0.82f) else techo
    }

    companion object {
        val MESA = 0xFFE4DABB.toInt()
        val TINTA = 0xFF1B1712.toInt()
        val TINTA_COSTA = 0xFF2B2620.toInt()
        val AGUA_SOMERA = 0xFFB4D3CD.toInt()
        val AGUA_HONDA = 0xFF7FA9B4.toInt()
        val AGUA_ABISMO = 0xFF56809A.toInt()
        val ONDA = 0xFF3F6474.toInt()
        val ROCA = 0xFF9C9384.toInt()
        val NIEVE = 0xFFF4F3EE.toInt()
        val TEJA = 0xFFB4664A.toInt()
        private val ONDAS = floatArrayOf(3f, 7f, 12.5f)
        private val DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        /** Tablas de ruido (fino, de manchas, de desplazamiento) y el grano del papel. */
        val TF by lazy { RuidoTabla(256, 11, 64f, 2) }
        val TB by lazy { RuidoTabla(256, 23, 24f, 3) }
        val TJ by lazy { RuidoTabla(256, 37, 12f, 3) }
        val PAPEL_GRANO by lazy { RuidoTabla(256, 51, 96f, 2) }

        fun colorReino(id: Int): Int = Catalogo.COLORES_REINO[(id - 1).mod(Catalogo.COLORES_REINO.size)]

        fun mezcla(a: Int, b: Int, t: Float): Int {
            val q = t.coerceIn(0f, 1f)
            val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * q).toInt()
            val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * q).toInt()
            val bl = ((a and 255) + ((b and 255) - (a and 255)) * q).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        fun mezcla3(a: Int, b: Int, c: Int): Int {
            if (a == b && b == c) return a
            val r = ((a shr 16 and 255) * 2 + (b shr 16 and 255) + (c shr 16 and 255)) / 4
            val g = ((a shr 8 and 255) * 2 + (b shr 8 and 255) + (c shr 8 and 255)) / 4
            val bl = ((a and 255) * 2 + (b and 255) + (c and 255)) / 4
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        fun escalaColor(c: Int, k: Float): Int {
            val r = ((c shr 16 and 255) * k).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 255) * k).toInt().coerceIn(0, 255)
            val b = ((c and 255) * k).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun oscuro(c: Int) = escalaColor(c, 0.55f)
    }
}
