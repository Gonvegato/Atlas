package com.gonzalo.atlas.motor

import kotlin.random.Random

/** Nombres de fantasía con sonido castellano (solo un primer nombre: se cambian a mano). */
object Nombres {
    private val INICIOS = listOf("Val", "Mor", "Ar", "Bel", "Cal", "Dor", "El", "Fen", "Gal", "Hal", "Ir", "Kar", "Lor", "Mar",
        "Nor", "Os", "Quel", "Ros", "Sar", "Tal", "Ul", "Ver", "Zar", "Al", "Bra", "Cor", "Dra", "Est", "Gor", "Lin",
        "Mir", "Ol", "Sil", "Tor", "Vin", "Ear", "Ith", "Aun", "Car", "Ner")
    private val MEDIOS = listOf("a", "e", "i", "o", "an", "en", "ar", "or", "el", "il", "ad", "on", "ir", "es", "al")
    private val FINALES = listOf("dor", "mar", "gard", "ria", "nia", "drin", "sur", "val", "thal", "mora", "fell", "lia", "ton",
        "rath", "vel", "dún", "ara", "oria", "mir", "dal", "garth", "lis", "brea", "nor", "quen", "zar", "ena", "ost")

    private val PREFIJOS_LUGAR = mapOf(
        "puerto" to listOf("Puerto ", "Bahía de ", "Cala "),
        "castillo" to listOf("Castillo de ", "Alcázar de "),
        "fortaleza" to listOf("Fortaleza de ", "Bastión de "),
        "templo" to listOf("Templo de ", "Santuario de "),
        "monasterio" to listOf("Monasterio de ", "Abadía de "),
        "torre" to listOf("Torre de ", "Atalaya de "),
        "ruina" to listOf("Ruinas de ", "Restos de "),
        "mina" to listOf("Minas de ", "Pozos de "),
        "cueva" to listOf("Cueva de ", "Gruta de "),
        "faro" to listOf("Faro de "),
        "campamento" to listOf("Campamento de ", "Real de "),
    )

    val TIPOS_REGION = listOf("Comarca", "Bosque", "Selva", "Montes", "Sierra", "Colinas", "Desierto", "Llanura", "Pantano", "Valle",
        "Meseta", "Península", "Isla", "Cabo", "Mar", "Océano", "Lago", "Bahía", "Golfo", "Estrecho", "Tierras")
    private val PREFIJOS_REGION = mapOf(
        "Bosque" to listOf("Bosque de ", "Floresta de "), "Selva" to listOf("Selva de ", "Junglas de "),
        "Montes" to listOf("Montes de ", "Picos de "), "Sierra" to listOf("Sierra de ", "Sierra "), "Colinas" to listOf("Colinas de ", "Lomas de "),
        "Desierto" to listOf("Desierto de ", "Arenas de "), "Llanura" to listOf("Llanuras de ", "Campos de "),
        "Pantano" to listOf("Pantanos de ", "Ciénagas de "), "Valle" to listOf("Valle de ", "Valle "), "Meseta" to listOf("Meseta de "),
        "Península" to listOf("Península de "), "Isla" to listOf("Isla de ", "Isla "), "Cabo" to listOf("Cabo ", "Punta "),
        "Mar" to listOf("Mar de ", "Mar "), "Océano" to listOf("Océano ", "Mar Exterior de "), "Lago" to listOf("Lago ", "Lago de "),
        "Bahía" to listOf("Bahía de ", "Ensenada de "), "Golfo" to listOf("Golfo de "), "Estrecho" to listOf("Estrecho de "),
        "Tierras" to listOf("Tierras de ", "Marca de "), "Comarca" to listOf("", "Tierra de "),
    )

    fun raiz(r: Random = Random.Default): String {
        var s = INICIOS.random(r)
        if (r.nextFloat() < 0.55f) s += MEDIOS.random(r)
        var fin = FINALES.random(r)
        if (s.last().lowercaseChar() == fin.first()) fin = fin.drop(1)
        return s + fin
    }

    fun reino(r: Random = Random.Default) = raiz(r)
    fun lugar(tipo: String, r: Random = Random.Default) = (PREFIJOS_LUGAR[tipo]?.random(r) ?: "") + raiz(r)
    fun region(tipo: String, r: Random = Random.Default) = (PREFIJOS_REGION[tipo]?.random(r) ?: "Tierras de ") + raiz(r)
    fun rio(r: Random = Random.Default) = "Río " + raiz(r)
    fun mundo(r: Random = Random.Default) = raiz(r)
}
