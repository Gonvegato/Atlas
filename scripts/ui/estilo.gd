class_name Estilo
extends RefCounted

## Los tokens de la familia (dirección «C», noviembre de 2026), copiados de Familia-Revista
## (escritorio/familia/.../revista/Tema.kt y CaraC.kt; capturas de Seshat y Thot): papel #F8F0D4, tinta negra,
## cabecera negra con el nombre en papel y el punto del acento, pestañas sobre papel con la elegida en bloque.
## Letras: Bricolage Grotesque ExtraBold (tamaño óptico 48, ancho 100) para nombres, titulares y cifras; Inter para
## etiquetas y texto; Fraunces (la serif de la familia) para los nombres de las cosas en listas.

# --- Colores ---
const PAPEL := Color("F8F0D4")
const CRUDO := Color("FBF6E5")
const TINTA := Color("000000")
const TINTA_CABECERA := Color("0C0A09")
const GRIS := Color("57544A")
## Texto secundario sobre la cabecera negra (BUSCAR, la fecha…).
const SOBRE_CABECERA := Color("B9B2A2")
const GRIS_CLARO := Color("888475")
## La mesa bajo el mapa: papel más hondo (la familia es papel de arriba abajo; antes era casi negra).
const MESA := Color("E4DABB")
## El acento de ATLAS (azul pizarra). OJO: Thot/Nova usa #5A7896, casi el mismo.
const ACENTO := Color("5C7894")
const SOBRE_ACENTO := Color("FFFFFF")
## El azul de los bloques de cifras de la familia (el tercero de cada fila).
const BLOQUE2 := Color("1F3A5F")

# --- Letras ---
const F_GROTESCA: FontFile = preload("res://fonts/BricolageGrotesque.ttf")
const F_NEGRITA: FontFile = preload("res://fonts/Inter-Bold.ttf")
const F_SEMI: FontFile = preload("res://fonts/Inter-SemiBold.ttf")
const F_TEXTO: FontFile = preload("res://fonts/Inter-Regular.ttf")
const F_SERIF: FontFile = preload("res://fonts/Fraunces-Regular.ttf")
const F_SERIF_SEMI: FontFile = preload("res://fonts/Fraunces-SemiBold.ttf")
const F_SERIF_CURSIVA: FontFile = preload("res://fonts/Fraunces-Italic.ttf")

static var _versalitas: FontVariation = null
static var _grotesca_prieta: FontVariation = null
static var _grotesca: FontVariation = null

## Bricolage con el tamaño óptico de la familia: sin él se dibuja con el de cartel (96) y los remates de las
## mayúsculas salen a alturas distintas (criterio de Seshat 0.17.0).
static func _opsz(fv: FontVariation) -> void:
	var ts: TextServer = TextServerManager.get_primary_interface()
	fv.variation_opentype = {ts.name_to_tag("opsz"): 48.0, ts.name_to_tag("wdth"): 100.0, ts.name_to_tag("wght"): 800.0}

## La grotesca para titulares y cifras (sin espaciado negativo: los dígitos no se tocan).
static func grotesca() -> FontVariation:
	if _grotesca == null:
		_grotesca = FontVariation.new()
		_grotesca.base_font = F_GROTESCA
		_opsz(_grotesca)
	return _grotesca

## Acento legible sobre la cabecera negra (aclarado si hace falta, como acentoLegible() de la familia).
static func acento_sobre_negro() -> Color:
	var c: Color = ACENTO
	while c.get_luminance() < 0.32:
		c = c.lightened(0.08)
	return c

## Inter en negrita con el espaciado de las etiquetas de la familia («BUSCAR», «+ NUEVA»).
static func versalitas() -> FontVariation:
	if _versalitas == null:
		_versalitas = FontVariation.new()
		_versalitas.base_font = F_NEGRITA
		_versalitas.spacing_glyph = 2
	return _versalitas

## La grotesca con el interletraje cerrado de los nombres grandes («THOT.», «SESHAT.»).
static func grotesca_prieta() -> FontVariation:
	if _grotesca_prieta == null:
		_grotesca_prieta = FontVariation.new()
		_grotesca_prieta.base_font = F_GROTESCA
		# −0,015 em como mucho en los nombres con punto (a 64 px, −1).
		_grotesca_prieta.spacing_glyph = -1
		_opsz(_grotesca_prieta)
	return _grotesca_prieta

static func etiqueta(texto: String, tam: int, color: Color, fuente: Font = null) -> Label:
	var l := Label.new()
	l.text = texto
	l.add_theme_font_override("font", fuente if fuente != null else versalitas())
	l.add_theme_font_size_override("font_size", tam)
	l.add_theme_color_override("font_color", color)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

static func caja(fondo: Color, borde: Color = Color(0, 0, 0, 0), grosor: int = 0, margen_h: int = 0, margen_v: int = 0) -> StyleBoxFlat:
	var sb := StyleBoxFlat.new()
	sb.bg_color = fondo
	sb.border_color = borde
	sb.border_width_left = grosor
	sb.border_width_right = grosor
	sb.border_width_top = grosor
	sb.border_width_bottom = grosor
	sb.content_margin_left = margen_h
	sb.content_margin_right = margen_h
	sb.content_margin_top = margen_v
	sb.content_margin_bottom = margen_v
	sb.anti_aliasing = false
	return sb

## Un filete horizontal (la raya de la revista).
static func filete(grosor: int, color: Color = TINTA) -> ColorRect:
	var r := ColorRect.new()
	r.color = color
	r.custom_minimum_size = Vector2(0, grosor)
	r.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return r

## Un botón de texto plano (enlace de cabecera, pestaña, filtro): fondo y letra según el estado.
static func boton(texto: String, tam: int, letra: Color, fondo: Color, fondo_pulsado: Color, margen_h: int, margen_v: int, borde: Color = Color(0, 0, 0, 0), grosor: int = 0) -> Button:
	var b := Button.new()
	b.text = texto
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_font_override("font", versalitas())
	b.add_theme_font_size_override("font_size", tam)
	pintar_boton(b, letra, fondo, fondo_pulsado, margen_h, margen_v, borde, grosor)
	return b

static func pintar_boton(b: Button, letra: Color, fondo: Color, fondo_pulsado: Color, margen_h: int, margen_v: int, borde: Color = Color(0, 0, 0, 0), grosor: int = 0) -> void:
	var normal := caja(fondo, borde, grosor, margen_h, margen_v)
	var pulsado := caja(fondo_pulsado, borde, grosor, margen_h, margen_v)
	b.add_theme_stylebox_override("normal", normal)
	b.add_theme_stylebox_override("hover", normal)
	b.add_theme_stylebox_override("hover_pressed", pulsado)
	b.add_theme_stylebox_override("pressed", pulsado)
	b.add_theme_stylebox_override("disabled", normal)
	b.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	for k in ["font_color", "font_hover_color", "font_pressed_color", "font_hover_pressed_color", "font_focus_color"]:
		b.add_theme_color_override(k, letra)
	b.add_theme_color_override("font_disabled_color", Color(letra, 0.3))

## Un deslizador con la estética de la familia: raya de tinta, relleno del acento y tirador cuadrado.
static func deslizador(minimo: float, maximo: float, paso: float, valor: float) -> HSlider:
	var s := HSlider.new()
	s.min_value = minimo
	s.max_value = maximo
	s.step = paso
	s.value = valor
	s.focus_mode = Control.FOCUS_NONE
	s.custom_minimum_size = Vector2(0, 40)
	var pista := caja(Color(TINTA, 0.14))
	pista.content_margin_top = 3
	pista.content_margin_bottom = 3
	var lleno := caja(ACENTO)
	lleno.content_margin_top = 3
	lleno.content_margin_bottom = 3
	s.add_theme_stylebox_override("slider", pista)
	s.add_theme_stylebox_override("grabber_area", lleno)
	s.add_theme_stylebox_override("grabber_area_highlight", lleno)
	var tirador := _tirador()
	s.add_theme_icon_override("grabber", tirador)
	s.add_theme_icon_override("grabber_highlight", tirador)
	return s

static var _tirador_tex: ImageTexture = null

static func _tirador() -> ImageTexture:
	if _tirador_tex == null:
		var lado: int = 22
		var img := Image.create(lado, lado, false, Image.FORMAT_RGBA8)
		img.fill(TINTA)
		img.fill_rect(Rect2i(4, 4, lado - 8, lado - 8), PAPEL)
		_tirador_tex = ImageTexture.create_from_image(img)
	return _tirador_tex

## «23.11.26», como la fecha de la cabecera de las hermanas.
static func fecha_corta() -> String:
	var d: Dictionary = Time.get_date_dict_from_system()
	var dia: int = d["day"]
	var mes: int = d["month"]
	var anio: int = d["year"]
	return "%02d.%02d.%02d" % [dia, mes, anio % 100]

## Cifras con punto de millar («4.010»), como en Formato.kt de la familia.
static func miles(n: int) -> String:
	var s: String = str(absi(n))
	var out: String = ""
	var c: int = 0
	for i in range(s.length() - 1, -1, -1):
		out = s[i] + out
		c += 1
		if c % 3 == 0 and i > 0:
			out = "." + out
	return ("-" if n < 0 else "") + out

## Un rótulo con placa detrás (para que se lea sobre cualquier fondo): rectángulo duro de [fill] con filete de
## [border] (grosor [bw], 0 = sin filete) y el texto encima. [at] es la línea base a la izquierda del texto.
## Devuelve el rectángulo de la placa.
static func placa(ci: CanvasItem, font: Font, at: Vector2, text: String, fs: int, fill: Color, border: Color, bw: float, text_col: Color, pad: Vector2 = Vector2(5, 3)) -> Rect2:
	var tw: float = font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var asc: float = font.get_ascent(fs)
	var desc: float = font.get_descent(fs)
	var r := Rect2(at + Vector2(-pad.x, -asc - pad.y), Vector2(tw + pad.x * 2.0, asc + desc + pad.y * 2.0))
	ci.draw_rect(r, fill)
	if bw > 0.0:
		ci.draw_rect(r, border, false, bw)
	ci.draw_string(font, at, text, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, text_col)
	return r

## Pestaña de reino: bloque del color del reino con el nombre en papel o en tinta (lo que contraste más) y una
## franja más oscura a la izquierda, como una pestaña de archivador.
static func pestana(ci: CanvasItem, font: Font, at: Vector2, text: String, fs: int, col: Color) -> Rect2:
	var lum: float = col.r * 0.299 + col.g * 0.587 + col.b * 0.114
	var txt_col: Color = PAPEL if lum < 0.6 else TINTA
	var r: Rect2 = placa(ci, font, at + Vector2(6, 0), text, fs, Color(col, 0.94), TINTA, 1.5, txt_col, Vector2(8, 4))
	ci.draw_rect(Rect2(r.position, Vector2(6, r.size.y)), col.darkened(0.35))
	ci.draw_string(font, at + Vector2(6, 0), text, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, txt_col)
	return r
