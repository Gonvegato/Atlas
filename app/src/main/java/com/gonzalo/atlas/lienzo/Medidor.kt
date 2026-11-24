package com.gonzalo.atlas.lienzo

import android.os.Build
import android.os.SystemClock

/**
 * Medir el trazo en la tableta (Acciones → Medir el trazo): mientras está encendido, cada evento del lápiz, cada estampado del
 * motor y cada imagen que se pinta apuntan su tiempo. Todo en el mismo reloj (SystemClock.uptimeMillis, el de los eventos).
 *
 * - Del lápiz al dibujo: desde la hora del evento del lápiz (la que pone Android al leerlo de la pantalla) hasta que la imagen
 *   que ya lo lleva se termina de pintar en la vista. No cuenta lo que tarda la pantalla en enseñar esa imagen (eso solo se ve
 *   grabando a cámara lenta).
 * - Imágenes por segundo mientras se dibuja, y lo que tarda cada una en pintarse.
 * - Estampar: lo que tarda el motor en poner los sellos de cada tanda de puntos.
 * - Puntos del lápiz por segundo (los que da la pantalla, contando los intermedios de cada evento).
 */
object Medidor {
    @Volatile var activo = false
        private set
    private val lock = Any()
    private val latencias = ArrayList<Long>()
    private val pintado = ArrayList<Double>()
    private val estampado = ArrayList<Double>()
    private val marcas = ArrayList<Long>()
    @Volatile private var pendiente = -1L      // la hora del último evento de lápiz que aún no se ha visto pintado
    @Volatile private var estampadoHecho = true // el motor ya lo ha estampado
    private var puntos = 0L
    private var desde = 0L; private var hasta = 0L
    @Volatile var version = 0; private set

    fun empieza() = synchronized(lock) { latencias.clear(); pintado.clear(); estampado.clear(); marcas.clear(); puntos = 0; desde = 0; hasta = 0; pendiente = -1; activo = true; version++ }
    fun para() { activo = false; version++ }

    /** Un evento del lápiz con [n] puntos (los intermedios y el último), leído por Android a la hora [hora]. */
    fun evento(hora: Long, n: Int) {
        if (!activo) return
        synchronized(lock) { puntos += n; if (desde == 0L) desde = hora; hasta = hora; if (pendiente < 0) pendiente = hora; estampadoHecho = false }
    }

    /** El motor estampó una tanda en [ns] nanosegundos. */
    fun estampa(ns: Long) { if (!activo) return; synchronized(lock) { estampado += ns / 1e6; estampadoHecho = true } }

    /** Se terminó de pintar una imagen de la vista, que tardó [ns]. */
    fun imagen(ns: Long) {
        if (!activo) return
        val ahora = SystemClock.uptimeMillis()
        synchronized(lock) {
            pintado += ns / 1e6; marcas += ahora
            if (pendiente >= 0 && estampadoHecho) { latencias += ahora - pendiente; pendiente = -1 }
            version++
        }
    }

    class Resultado(val muestras: Int, val latenciaMediana: Double, val latenciaP95: Double, val fps: Double, val pintarMedia: Double, val pintarP95: Double,
                    val estamparMedia: Double, val estamparP95: Double, val puntosPorSegundo: Double, val segundos: Double)

    private fun cuantil(l: List<Double>, q: Double): Double { if (l.isEmpty()) return 0.0; val s = l.sorted(); return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] }

    fun resultado(): Resultado = synchronized(lock) {
        val seg = if (hasta > desde) (hasta - desde) / 1000.0 else 0.0
        // Imágenes por segundo: las pintadas mientras había lápiz (en tramos de menos de 120 ms entre una y otra)
        var tramo = 0.0; var n = 0
        for (i in 1 until marcas.size) { val d = marcas[i] - marcas[i - 1]; if (d in 1..120) { tramo += d; n++ } }
        val lat = latencias.map { it.toDouble() }
        Resultado(lat.size, cuantil(lat, 0.5), cuantil(lat, 0.95), if (tramo > 0) n * 1000.0 / tramo else 0.0, pintado.average().takeIf { !it.isNaN() } ?: 0.0, cuantil(pintado, 0.95),
            estampado.average().takeIf { !it.isNaN() } ?: 0.0, cuantil(estampado, 0.95), if (seg > 0) puntos / seg else 0.0, seg)
    }

    /** El informe en texto, para compartirlo. */
    fun informe(version: String, pincel: String, tam: Float, zoom: Float, capas: Int, hz: Float): String {
        val r = resultado()
        fun f(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
        return buildString {
            appendLine("ATLAS $version · medir el trazo")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · pantalla a ${f(hz.toDouble())} Hz")
            appendLine("Pincel: $pincel · tamaño ${f(tam.toDouble())} · acercamiento ×${f(zoom.toDouble())} · $capas capas")
            appendLine("Tiempo dibujando: ${f(r.segundos)} s · ${r.muestras} medidas")
            appendLine("Del lápiz al dibujo: mediana ${f(r.latenciaMediana)} ms · peor 5 % ${f(r.latenciaP95)} ms (sin el retraso propio de la pantalla)")
            appendLine("Imágenes por segundo dibujando: ${f(r.fps)}")
            appendLine("Pintar cada imagen: media ${f(r.pintarMedia)} ms · peor 5 % ${f(r.pintarP95)} ms")
            appendLine("Estampar cada tanda: media ${f(r.estamparMedia)} ms · peor 5 % ${f(r.estamparP95)} ms")
            appendLine("Puntos del lápiz por segundo: ${f(r.puntosPorSegundo)}")
        }
    }
}
