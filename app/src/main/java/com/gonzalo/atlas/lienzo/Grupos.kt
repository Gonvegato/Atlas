package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.motor.GrupoCapas

/**
 * Los grupos y las máscaras de capa. Todo se hace dentro de [Lienzo.cambiaCapas] (en su hilo, con deshacer).
 *
 * - Una **unidad** es una capa y, si tiene, su máscara (la capa de justo encima con [CapaInfo.mascara]): van siempre juntas.
 * - Las capas de un **grupo** van seguidas en la lista ([CapaInfo.grupo]); el grupo se ve o se esconde entero y se ordena junto.
 */
object Grupos {
    fun nuevoId(p: String) = p + java.lang.Long.toString(System.nanoTime(), 36)

    /** La capa y su máscara (si la tiene). */
    fun unidad(l: List<CapaPx>, c: CapaPx): List<CapaPx> {
        val i = l.indexOf(c); if (i < 0) return emptyList()
        val m = l.getOrNull(i + 1)?.takeIf { it.info.mascara }
        return listOfNotNull(c, m)
    }

    /** Dónde va una capa nueva puesta justo encima de [c] (encima de su máscara, si tiene). */
    fun trasUnidad(l: List<CapaPx>, c: CapaPx?): Int {
        if (c == null) return l.size
        val d = if (c.info.mascara) l.getOrNull(l.indexOf(c) - 1) ?: c else c
        return l.indexOf(d) + unidad(l, d).size
    }

    /** Las unidades de la lista, de abajo arriba. */
    private fun unidades(l: List<CapaPx>): List<List<CapaPx>> {
        val r = ArrayList<List<CapaPx>>(); var i = 0
        while (i < l.size) { val m = l.getOrNull(i + 1)?.takeIf { it.info.mascara }; r += listOfNotNull(l[i], m); i += if (m != null) 2 else 1 }
        return r
    }

    /** Los bloques de la lista, de abajo arriba: una unidad suelta o un grupo entero. */
    private fun bloques(l: List<CapaPx>): List<List<CapaPx>> {
        val r = ArrayList<MutableList<CapaPx>>()
        for (u in unidades(l)) {
            val g = u[0].info.grupo
            val ultimo = r.lastOrNull()
            if (g.isNotEmpty() && ultimo != null && ultimo[0].info.grupo == g) ultimo.addAll(u) else r += u.toMutableList()
        }
        return r
    }

    private fun ponLista(lz: Lienzo, l: List<CapaPx>) { lz.capas.clear(); lz.capas.addAll(l) }

    /** Sube ([arriba]) o baja la capa [c] un puesto: dentro de su grupo, o entre los bloques si está suelta. false si no puede. */
    fun mueve(lz: Lienzo, c: CapaPx, arriba: Boolean): Boolean {
        val l = lz.capas.toList()
        val g = c.info.grupo
        if (g.isNotEmpty()) {
            // Dentro del grupo: cambia el sitio con la unidad vecina del mismo grupo
            val us = unidades(l).toMutableList()
            val i = us.indexOfFirst { it[0] === c }; val j = if (arriba) i + 1 else i - 1
            if (i < 0 || j !in us.indices || us[j][0].info.grupo != g) return false
            val t = us[i]; us[i] = us[j]; us[j] = t
            ponLista(lz, us.flatten()); return true
        }
        val bs = bloques(l).toMutableList()
        val i = bs.indexOfFirst { it[0] === c }; val j = if (arriba) i + 1 else i - 1
        if (i < 0 || j !in bs.indices) return false
        val t = bs[i]; bs[i] = bs[j]; bs[j] = t
        ponLista(lz, bs.flatten()); return true
    }

    /** Sube o baja el grupo [g] entero un puesto (entre los bloques). */
    fun mueveGrupo(lz: Lienzo, g: String, arriba: Boolean): Boolean {
        val bs = bloques(lz.capas.toList()).toMutableList()
        val i = bs.indexOfFirst { it[0].info.grupo == g }; val j = if (arriba) i + 1 else i - 1
        if (i < 0 || j !in bs.indices) return false
        val t = bs[i]; bs[i] = bs[j]; bs[j] = t
        ponLista(lz, bs.flatten()); return true
    }

    /** Mete [c] (con su máscara) en el grupo [g] (o en uno nuevo si es null), arriba del todo del grupo. Devuelve el grupo. */
    fun agrupa(lz: Lienzo, c: CapaPx, g: GrupoCapas?): GrupoCapas {
        val grupo = g ?: GrupoCapas(nuevoId("g"), nombreLibre(lz)).also { lz.mapa.grupos += it }
        val l = lz.capas.toMutableList()
        val u = unidad(l, c); l.removeAll(u)
        u.forEach { it.info.grupo = grupo.id }
        val ultimo = l.indexOfLast { it.info.grupo == grupo.id }
        if (ultimo >= 0) l.addAll(ultimo + 1, u) else {
            // Grupo nuevo: se queda donde estaba la capa
            val i = lz.capas.indexOf(c).coerceAtMost(l.size); l.addAll(i, u)
        }
        ponLista(lz, l)
        return grupo
    }

    /** Saca [c] de su grupo: queda justo encima del grupo. */
    fun desagrupa(lz: Lienzo, c: CapaPx) {
        val g = c.info.grupo; if (g.isEmpty()) return
        val l = lz.capas.toMutableList()
        val u = unidad(l, c); l.removeAll(u)
        u.forEach { it.info.grupo = "" }
        val ultimo = l.indexOfLast { it.info.grupo == g }
        l.addAll(if (ultimo >= 0) ultimo + 1 else lz.capas.indexOf(c).coerceAtMost(l.size), u)
        ponLista(lz, l)
    }

    /** Deshace el grupo: sus capas quedan sueltas, en su sitio. */
    fun deshazGrupo(lz: Lienzo, g: String) { lz.capas.forEach { if (it.info.grupo == g) it.info.grupo = "" } }

    /** Borra el grupo con sus capas (si no son todas las que hay). false si no se puede. */
    fun borraGrupo(lz: Lienzo, g: String): Boolean {
        val quedan = lz.capas.filter { it.info.grupo != g }
        if (quedan.none { !it.info.mascara }) return false
        ponLista(lz, quedan)
        if (lz.capa(lz.activa) == null) lz.activa = quedan.last { !it.info.mascara }.info.id
        return true
    }

    private fun nombreLibre(lz: Lienzo): String {
        var n = lz.mapa.grupos.size + 1
        while (lz.mapa.grupos.any { it.nombre == "Grupo $n" }) n++
        return "Grupo $n"
    }

    // ---------------------------------------------------------------- máscaras

    fun mascara(lz: Lienzo, c: CapaPx): CapaPx? { val l = lz.capas.toList(); return l.getOrNull(l.indexOf(c) + 1)?.takeIf { it.info.mascara } }

    /** Le pone una máscara vacía (no esconde nada) a [c] y la deja elegida para pintar en ella. */
    fun nuevaMascara(lz: Lienzo, c: CapaPx): CapaPx {
        mascara(lz, c)?.let { return it }
        val m = CapaPx(CapaInfo(nuevoId("m"), "Máscara", grupo = c.info.grupo, mascara = true))
        lz.capas.add(lz.capas.indexOf(c) + 1, m)
        lz.activa = m.info.id
        return m
    }

    /** Quita la máscara de [c] sin aplicarla (la capa vuelve a verse entera). */
    fun quitaMascara(lz: Lienzo, c: CapaPx) {
        val m = mascara(lz, c) ?: return
        lz.capas.remove(m)
        if (lz.activa == m.info.id) lz.activa = c.info.id
    }

    /** Aplica la máscara: borra de [c] lo que esconde (queda como píxeles) y la quita. */
    fun aplicaMascara(lz: Lienzo, c: CapaPx) {
        val m = mascara(lz, c) ?: return
        if (m.info.visible && m.teselas.isNotEmpty()) {
            c.soloPixeles()
            val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            for ((k, mb) in m.teselas) {
                val b = c.teselas[k] ?: continue
                val n = b.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(n).drawBitmap(mb, 0f, 0f, p)
                c.teselas[k] = n
            }
        }
        lz.capas.remove(m)
        if (lz.activa == m.info.id) lz.activa = c.info.id
    }

    /** Invierte la máscara de [c] donde la capa tiene algo: lo escondido se ve y lo visible se esconde. */
    fun invierteMascara(lz: Lienzo, c: CapaPx) {
        val m = mascara(lz, c) ?: return
        m.soloPixeles()
        val claves = c.teselas.keys + m.teselas.keys
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val nuevas = HashMap<Long, Bitmap>()
        for (k in claves) {
            val n = Bitmap.createBitmap(Lienzo.L, Lienzo.L, Bitmap.Config.ARGB_8888)
            n.eraseColor(0xFF000000.toInt())
            m.teselas[k]?.let { Canvas(n).drawBitmap(it, 0f, 0f, p) }
            nuevas[k] = n
        }
        m.teselas.clear(); m.teselas.putAll(nuevas)
    }

    /** Antes de juntar o transformar píxeles: la máscara de [c], aplicada. */
    fun hornea(lz: Lienzo, c: CapaPx) { if (mascara(lz, c) != null) aplicaMascara(lz, c) }
}
