package com.gonzalo.atlas.motor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * El mundo que sale solo de una semilla. Se evalúa punto a punto en coordenadas del mundo, así que vale igual para un
 * mapa finito que para uno infinito, y para ver de lejos se puede calcular directamente a menos resolución.
 *
 * OJO: lo que no has tocado de un mapa no se guarda; se vuelve a generar con esto. Si algún día cambia el algoritmo,
 * tiene que ser con otra [VERSION] (y conservar esta para los mapas que ya existen).
 */
class Generador(val semilla: Long, val estilo: Estilo, val ancho: Int, val alto: Int, val tierra: Float) {
    enum class Estilo(val nombre: String, val desc: String) {
        CONTINENTES("Continentes", "Dos o tres masas de tierra con mares entre ellas"),
        PANGEA("Un continente", "Una gran masa de tierra rodeada de océano"),
        ARCHIPIELAGO("Archipiélago", "Muchas islas de todos los tamaños"),
        ISLA("Una isla", "Una isla grande en el centro"),
        LLANO("Tierra lisa", "Todo tierra llana, para dibujarlo tú"),
        MAR("Mar vacío", "Todo mar, para levantar tú la tierra"),
    }

    private val r = Ruido(semilla)
    private val r2 = Ruido(semilla * 31 + 7)
    private val r3 = Ruido(semilla * 131 + 17)
    private val finito = ancho > 0
    /** Escala de los rasgos grandes (px del mapa por ciclo). */
    private val escala: Float = when {
        !finito -> 1400f
        else -> (max(ancho, alto) / when (estilo) { Estilo.ARCHIPIELAGO -> 4.5f; Estilo.CONTINENTES -> 2.4f; else -> 1.9f }).coerceIn(300f, 2400f)
    }
    /** Lo que se resta para que salga la proporción de tierra pedida (se calcula mirando el mapa a ojo). */
    private val umbral: Float = calibra()

    private fun crudo(x: Float, y: Float): Float {
        val sx = x / escala; val sy = y / escala
        val wx = r2.fbm(sx * 0.6f + 5.2f, sy * 0.6f + 1.3f, 4) * 0.55f
        val wy = r2.fbm(sx * 0.6f - 3.1f, sy * 0.6f + 7.7f, 4) * 0.55f
        var e = r.fbm(sx + wx, sy + wy, 6, 0.5f)
        if (estilo == Estilo.ARCHIPIELAGO) e = e * 0.8f + r3.fbm(sx * 2.3f, sy * 2.3f, 4) * 0.35f
        if (finito) {
            val nx = x / ancho * 2f - 1f; val ny = y / alto * 2f - 1f
            val borde = min(1f - abs(nx), 1f - abs(ny))
            val caida = suave(0f, 0.28f, borde)
            when (estilo) {
                Estilo.PANGEA, Estilo.ISLA -> {
                    val d = sqrt(nx * nx + ny * ny)
                    e = e * (if (estilo == Estilo.ISLA) 0.55f else 0.75f) + (0.55f - d * 0.9f)
                }
                else -> {}
            }
            e -= (1f - caida) * 0.9f
        }
        return e
    }

    private fun calibra(): Float {
        if (estilo == Estilo.LLANO || estilo == Estilo.MAR) return 0f
        val n = 64
        val w = if (finito) ancho.toFloat() else 12000f; val h = if (finito) alto.toFloat() else 9000f
        val ox = if (finito) 0f else -6000f; val oy = if (finito) 0f else -4500f
        val v = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) v[j * n + i] = crudo(ox + (i + 0.5f) * w / n, oy + (j + 0.5f) * h / n)
        v.sort()
        return v[((1f - tierra.coerceIn(0.05f, 0.95f)) * (v.size - 1)).toInt()]
    }

    /** La altura en un punto del mundo (px). */
    fun alt(x: Float, y: Float): Float {
        when (estilo) {
            Estilo.LLANO -> return 0.06f + r.fbm(x / 600f, y / 600f, 3) * 0.03f
            Estilo.MAR -> return -0.35f + r.fbm(x / 900f, y / 900f, 3) * 0.1f
            else -> {}
        }
        val e = crudo(x, y) - umbral
        if (e < 0f) {
            // Mar: plataforma somera junto a la costa y luego fondo
            val d = -e
            return -(d * 1.6f).pow(0.8f).coerceAtMost(1f) * 0.9f - 0.004f
        }
        val sx = x / escala; val sy = y / escala
        // Tierra: llanuras que suben despacio, colinas y cordilleras donde el ruido de crestas lo dice
        var h = e.pow(1.3f) * 0.32f + 0.008f
        val zona = suave(0.02f, 0.3f, e) * suave(0.25f, 0.7f, r3.fbm(sx * 0.9f + 11f, sy * 0.9f - 4f, 3) * 0.5f + 0.5f)
        val cr = r.crestas(sx * 3.1f + 40f, sy * 3.1f - 13f, 6)
        h += cr * cr * zona * 0.85f
        // Colinas y lomas por todas partes (más donde la tierra es más alta)
        val col = r3.fbm(x / 260f + 3f, y / 260f - 8f, 4)
        h += (col * 0.5f + 0.5f) * (col * 0.5f + 0.5f) * 0.09f * suave(0f, 0.08f, e)
        h += r2.fbm(x / 90f, y / 90f, 4) * 0.03f * suave(0f, 0.05f, e)
        return h.coerceAtMost(1f)
    }

    /** El bioma de un punto, por su clima (latitud, altura, humedad). */
    fun bioma(x: Float, y: Float, a: Float): Int {
        if (estilo == Estilo.MAR) return 0
        if (estilo == Estilo.LLANO) return Catalogo.B_PRADERA
        // Un mapa finito va de las tierras frías del norte a las cálidas del sur (no de polo a polo)
        val lat = if (finito) (0.12f + (y / alto) * 0.72f) else abs(((y / 9000f) % 2f + 2f) % 2f - 1f)
        var t = 1f - lat * 1.05f - max(0f, a) * 0.9f + r3.fbm(x / 1500f, y / 1500f, 3) * 0.18f
        val m = (r2.fbm(x / 1100f + 50f, y / 1100f - 20f, 4) * 0.5f + 0.5f + r.fbm(x / 300f, y / 300f, 2) * 0.08f).coerceIn(0f, 1f)
        if (a < 0f) {
            if (t < 0.03f) return Catalogo.B_HIELO
            if (a > -0.06f && t > 0.72f && Ruido.hash01((x / 23f).toInt(), (y / 23f).toInt(), 5) > 0.72f && r.fbm(x / 160f, y / 160f, 2) > 0.15f) return Catalogo.B_ARRECIFE
            return 0
        }
        t = t.coerceIn(0f, 1f)
        if (a > 0.82f || t < 0.04f) return Catalogo.B_NIEVE
        if (a > 0.6f) return Catalogo.B_ROCA
        if (a < 0.012f && t > 0.2f && m < 0.8f) return Catalogo.B_PLAYA
        return when {
            t < 0.25f -> if (m < 0.45f) Catalogo.B_TUNDRA else Catalogo.B_TAIGA
            t < 0.62f -> when {
                m < 0.25f -> Catalogo.B_ESTEPA
                m < 0.52f -> Catalogo.B_PRADERA
                m < 0.8f -> Catalogo.B_BOSQUE
                a < 0.05f -> Catalogo.B_PANTANO
                else -> Catalogo.B_BOSQUE
            }
            else -> when {
                m < 0.2f -> Catalogo.B_DUNAS
                m < 0.36f -> Catalogo.B_DESIERTO
                m < 0.58f -> Catalogo.B_SABANA
                m < 0.68f -> Catalogo.B_PRADERA
                else -> Catalogo.B_SELVA
            }
        }
    }

    /** Un trozo entero, a toda resolución. */
    fun genera(cx: Int, cy: Int): Trozo {
        val t = Trozo(cx, cy)
        val x0 = cx * Trozo.LADO; val y0 = cy * Trozo.LADO
        for (j in 0 until Trozo.LADO) for (i in 0 until Trozo.LADO) {
            val x = x0 + i + 0.5f; val y = y0 + j + 0.5f
            val a = alt(x, y)
            val k = j * Trozo.LADO + i
            t.alt[k] = a
            t.bio[k] = bioma(x, y, a).toByte()
        }
        return t
    }

    /** Un trozo visto de lejos (sin generar el trozo entero). */
    fun mip(cx: Int, cy: Int, nivel: Int): Mip {
        val m = Mip(nivel, -1)
        val f = (1 shl nivel).toFloat()
        val x0 = cx * Trozo.LADO; val y0 = cy * Trozo.LADO
        for (j in 0 until m.lado) for (i in 0 until m.lado) {
            val x = x0 + (i + 0.5f) * f; val y = y0 + (j + 0.5f) * f
            val a = alt(x, y)
            m.alt[j * m.lado + i] = a
            m.bio[j * m.lado + i] = bioma(x, y, a).toByte()
        }
        return m
    }

    companion object {
        const val VERSION = 1
        fun suave(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    }
}
