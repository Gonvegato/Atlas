extends Control

## Lo que va encima del mapa en 2D, en coordenadas de pantalla: el anillo del pincel mientras dibujas,
## la flecha del norte y la escala de zoom. No recibe toques (MOUSE_FILTER_IGNORE).

var ring_visible: bool = false
var ring_center: Vector2 = Vector2.ZERO
var ring_radius: float = 20.0
var ring_subtracts: bool = false
var zoom_pct: int = 100

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE

func show_ring(center: Vector2, radius: float, subtracts: bool) -> void:
	ring_visible = true
	ring_center = center
	ring_radius = radius
	ring_subtracts = subtracts
	queue_redraw()

func hide_ring() -> void:
	if ring_visible:
		ring_visible = false
		queue_redraw()

func set_zoom(pct: int) -> void:
	if pct != zoom_pct:
		zoom_pct = pct
		queue_redraw()

func _draw() -> void:
	if ring_visible:
		# Doble anillo (papel por fuera, tinta por dentro) para que se vea sobre mar y sobre tierra.
		draw_arc(ring_center, ring_radius + 1.5, 0.0, TAU, 72, Estilo.PAPEL, 2.0, true)
		draw_arc(ring_center, ring_radius, 0.0, TAU, 72, Estilo.TINTA, 1.5, true)
		var c: Color = Estilo.ACENTO
		draw_line(ring_center - Vector2(5, 0), ring_center + Vector2(5, 0), c, 2.0)
		if not ring_subtracts:
			draw_line(ring_center - Vector2(0, 5), ring_center + Vector2(0, 5), c, 2.0)

	_draw_north(Vector2(40, size.y - 46))
	_draw_zoom_tag()

func _draw_north(at: Vector2) -> void:
	# Rosa mínima: una aguja partida (mitad tinta, mitad papel) y una N en grotesca.
	var h: float = 22.0
	var w: float = 8.0
	var top: Vector2 = at + Vector2(0, -h)
	var bottom: Vector2 = at + Vector2(0, h * 0.55)
	draw_colored_polygon(PackedVector2Array([top, at + Vector2(w, 0), bottom]), Estilo.PAPEL)
	draw_colored_polygon(PackedVector2Array([top, at + Vector2(-w, 0), bottom]), Estilo.TINTA)
	draw_polyline(PackedVector2Array([top, at + Vector2(w, 0), bottom, at + Vector2(-w, 0), top]), Estilo.TINTA, 1.5, true)
	var f: Font = Estilo.F_GROTESCA
	var n_size: int = 18
	var n_w: float = f.get_string_size("N", HORIZONTAL_ALIGNMENT_LEFT, -1, n_size).x
	draw_string(f, top + Vector2(-n_w * 0.5, -6), "N", HORIZONTAL_ALIGNMENT_LEFT, -1, n_size, Estilo.PAPEL)

func _draw_zoom_tag() -> void:
	var f: Font = Estilo.versalitas()
	var fs: int = 12
	var txt: String = "ZOOM %d %%" % zoom_pct
	var tw: float = f.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var pad := Vector2(10, 7)
	var box := Rect2(Vector2(size.x - tw - pad.x * 2.0 - 16.0, size.y - 16.0 - fs - pad.y * 2.0), Vector2(tw + pad.x * 2.0, fs + pad.y * 2.0))
	draw_rect(box, Estilo.TINTA)
	draw_string(f, box.position + Vector2(pad.x, pad.y + fs - 1), txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, Estilo.PAPEL)
