extends Control

## Los nombres en la vista 3D: cada etiqueta tiene un punto del mundo 3D; la cámara lo proyecta a la pantalla
## (unproject_position) y aquí se pinta, con el mismo halo de papel que en 2D. Lo de detrás de la cámara no se pinta.

var camera: Camera3D
## [texto, posición 3D, tipo] con tipo "lugar", "capital", "reino" o "region" / "agua".
var labels: Array = []
var ink: Color = Estilo.TINTA
var paper: Color = Estilo.PAPEL
var water_ink: Color = Color(0.20, 0.32, 0.46)

var _realm_font: FontVariation
var _area_font: FontVariation

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_realm_font = FontVariation.new()
	_realm_font.base_font = Estilo.F_NEGRITA
	_realm_font.spacing_glyph = 5
	_area_font = FontVariation.new()
	_area_font.base_font = preload("res://fonts/Inter-Italic.ttf")
	_area_font.spacing_glyph = 3

func _draw() -> void:
	if camera == null or not is_visible_in_tree():
		return
	for l in labels:
		var txt: String = l[0]
		var pos: Vector3 = l[1]
		var kind: String = l[2]
		if camera.is_position_behind(pos):
			continue
		var sp: Vector2 = camera.unproject_position(pos)
		if sp.x < -100 or sp.y < -40 or sp.x > size.x + 100 or sp.y > size.y + 40:
			continue
		var f: Font = Estilo.F_SEMI
		var fs: int = 12
		var col: Color = ink
		match kind:
			"capital":
				f = Estilo.F_GROTESCA
				fs = 16
			"reino":
				f = _realm_font
				fs = 15
				col = l[3] if l.size() > 3 else ink
			"region":
				f = _area_font
				fs = 14
			"agua":
				f = _area_font
				fs = 14
				col = water_ink
		var tw: float = f.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
		var at: Vector2 = sp + Vector2(-tw * 0.5, 0.0)
		match kind:
			"capital", "lugar":
				# Línea guía desde el sitio hasta la placa, que flota un poco por encima.
				var top: Vector2 = sp + Vector2(0, -18)
				draw_line(sp, top, ink, 1.5)
				draw_circle(sp, 3.0, ink)
				at = top + Vector2(-tw * 0.5, -4.0)
				if kind == "capital":
					Estilo.placa(self, f, at, txt, fs, ink, ink, 0.0, paper, Vector2(6, 3))
				else:
					Estilo.placa(self, f, at, txt, fs, Color(paper, 0.93), ink, 1.0, ink, Vector2(4, 2))
			"reino":
				Estilo.placa(self, f, at, txt, fs, Color(paper, 0.72), col, 0.0, col, Vector2(8, 4))
			_:
				Estilo.placa(self, f, at, txt, fs, Color(paper, 0.7), col, 0.0, col, Vector2(6, 3))
