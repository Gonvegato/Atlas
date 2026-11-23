class_name Nombres
extends RefCounted

## Nombres de fantasía con sonido castellano: sílabas que encajan y algún nombre compuesto («Puerto Valdrín»,
## «Torre de Olmar»). Se pueden cambiar a mano; esto solo da un primer nombre que no suene a relleno.

const INICIOS = ["Val", "Mor", "Ar", "Bel", "Cal", "Dor", "El", "Fen", "Gal", "Hal", "Ir", "Kar", "Lor", "Mar",
	"Nor", "Os", "Quel", "Ros", "Sar", "Tal", "Ul", "Ver", "Zar", "Al", "Bra", "Cor", "Dra", "Est", "Gor", "Lin",
	"Mir", "Ol", "Sil", "Tor", "Vin", "Ear", "Ith", "Aun", "Car", "Ner"]
const MEDIOS = ["a", "e", "i", "o", "an", "en", "ar", "or", "el", "il", "ad", "on", "ir", "es", "al"]
const FINALES = ["dor", "mar", "gard", "ria", "nia", "drin", "sur", "val", "thal", "mora", "fell", "lia", "ton",
	"rath", "vel", "dún", "ara", "oria", "mir", "dal", "garth", "lis", "brea", "nor", "quen", "zar", "ena", "ost"]

const PREFIJOS_LUGAR = {
	"puerto": ["Puerto ", "Bahía de ", "Cala "],
	"castillo": ["Castillo de ", "Fortaleza de ", "Alcázar de "],
	"templo": ["Templo de ", "Santuario de ", "Monasterio de "],
	"torre": ["Torre de ", "Atalaya de "],
	"ruinas": ["Ruinas de ", "Restos de "],
	"mina": ["Minas de ", "Pozos de "],
	"batalla": ["Batalla de ", "Campo de "],
}

static func raiz(rng: RandomNumberGenerator) -> String:
	var s: String = INICIOS[rng.randi() % INICIOS.size()]
	if rng.randf() < 0.55:
		s += MEDIOS[rng.randi() % MEDIOS.size()]
	var fin: String = FINALES[rng.randi() % FINALES.size()]
	# Que no se junten tres vocales ni dos iguales al empalmar.
	if s.right(1) == fin.left(1):
		fin = fin.substr(1)
	return s + fin

static func reino(rng: RandomNumberGenerator) -> String:
	return raiz(rng)

static func lugar(tipo: String, rng: RandomNumberGenerator) -> String:
	var base: String = raiz(rng)
	if PREFIJOS_LUGAR.has(tipo):
		var p: Array = PREFIJOS_LUGAR[tipo]
		return str(p[rng.randi() % p.size()]) + base
	return base
