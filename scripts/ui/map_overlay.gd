extends Control

## Lo que va encima del mapa en 2D, en coordenadas de pantalla: nombres de reinos, lugares con su símbolo y su
## nombre (que aparecen según el zoom, como en un atlas), el anillo del pincel, el norte y el zoom.
## No recibe toques (MOUSE_FILTER_IGNORE).

var ring_visible: bool = false
var ring_center: Vector2 = Vector2.ZERO
var ring_radius: float = 20.0
var ring_subtracts: bool = false
var zoom_pct: int = 100

## Dónde está el mapa en pantalla: esquina, tamaño en pantalla (ya con zoom) y zoom.
var map_pos: Vector2 = Vector2.ZERO
var map_screen_size: Vector2 = Vector2(100, 100)
var map_zoom: float = 1.0

var places: Array = []
var realms: Array = []
var centroids: Dictionary = {}
## Quién contiene a quién (lo pone main): id → id del que depende (0 = nadie).
var realm_parent: Callable = func(_id: int) -> int: return 0
var areas: Array = []
var area_shapes: Dictionary = {}
var show_area_names: bool = true
var water_ink: Color = Color(0.20, 0.32, 0.46)
var war_ink: Color = Color(0.70, 0.08, 0.10)
## Ruta que se está midiendo (uv) y su texto; rutas guardadas.
var route: Array = []
var route_label: String = ""
var routes: Array = []
var show_route: bool = false
## Contorno que se está dibujando (uv).
var outline: Array = []
## Caminos dibujados (del motor), el elegido y el que se está dibujando.
var roads: Array = []
var selected_road: int = -1
var road_draft: Array = []
var road_draft_kind: String = "camino"
var map_px: Vector2 = Vector2(2048, 2048)
var selected_place: int = -1
var show_place_names: bool = true
var show_realm_names: bool = true
var ink: Color = Estilo.TINTA
var paper: Color = Estilo.PAPEL

var _realm_font: FontVariation
## Rectángulos ya ocupados por rótulos en este dibujo (los que chocan no se pintan).
var _taken: Array[Rect2] = []
## Trazados de los asentamientos ("id:tipo:tamaño" → plan de Asentamiento), para no regenerarlos en cada dibujo.
var _plans: Dictionary = {}
## Unidades de edificio por lado largo del mapa (la misma escala que el 3D: 0,07 de 12).
const TOWN_UNIT := 0.07 / 12.0
const PLACE_ORDER = {"capital": 0, "ciudad": 1, "castillo": 2, "puerto": 3, "templo": 4, "pueblo": 5, "torre": 6, "batalla": 7, "ruinas": 8, "mina": 9, "aldea": 10}
var _area_font: FontVariation

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_realm_font = FontVariation.new()
	_realm_font.base_font = Estilo.F_NEGRITA
	_realm_font.spacing_glyph = 6
	_area_font = FontVariation.new()
	_area_font.base_font = preload("res://fonts/Inter-Italic.ttf")
	_area_font.spacing_glyph = 3

func set_view(pos: Vector2, screen_size: Vector2, zoom: float) -> void:
	map_pos = pos
	map_screen_size = screen_size
	map_zoom = zoom
	zoom_pct = int(round(zoom * 100.0))
	queue_redraw()

func uv_to_screen(uv: Vector2) -> Vector2:
	return map_pos + uv * map_screen_size

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

func _draw() -> void:
	_taken.clear()
	# Primero se decide qué rótulos de lugares caben (son lo más importante); luego reinos y regiones buscan hueco
	# sin pisarlos y se pintan debajo.
	var layout: Array = _layout_places()
	_draw_roads()
	_draw_towns()
	_draw_routes()
	if show_area_names:
		_draw_area_names()
	if show_realm_names:
		_draw_realm_names()
	_draw_places(layout)
	_draw_route_label()
	_draw_outline()
	if ring_visible:
		draw_arc(ring_center, ring_radius + 1.5, 0.0, TAU, 72, Estilo.PAPEL, 2.0, true)
		draw_arc(ring_center, ring_radius, 0.0, TAU, 72, Estilo.TINTA, 1.5, true)
		var c: Color = Estilo.ACENTO
		draw_line(ring_center - Vector2(5, 0), ring_center + Vector2(5, 0), c, 2.0)
		if not ring_subtracts:
			draw_line(ring_center - Vector2(0, 5), ring_center + Vector2(0, 5), c, 2.0)
	_draw_north(Vector2(40, size.y - 46))
	_draw_zoom_tag()

# ---------------------------------------------------------------- regiones con nombre

## Cada región, rotulada a lo largo de su eje mayor: cursiva espaciada, del tamaño que quepa en su largo.
func _draw_area_names() -> void:
	var long_screen: float = maxf(map_screen_size.x, map_screen_size.y)
	for a in areas:
		var id: int = int(a["id"])
		if not area_shapes.has(id):
			continue
		var sh: Dictionary = area_shapes[id]
		var uv: Vector2 = sh["uv"]
		var angle: float = sh["angle"]
		var length_px: float = float(sh["length"]) * long_screen
		var txt: String = str(a["name"]).to_upper()
		var w10: float = _area_font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, 10).x
		var fs: int = int(clampf(length_px * 0.8 / maxf(w10, 1.0) * 10.0, 10.0, 30.0))
		var t: Array = Catalogo.tipo_region(str(a["kind"]))
		var water: bool = t[2]
		var conflict: bool = Catalogo.es_conflicto(str(a["kind"]))
		var col: Color = war_ink if conflict else (water_ink if water else ink)
		var tw: float = _area_font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
		# Si aun así no cabe, se dobla en recto (casi horizontal) para que se lea.
		var p: Vector2 = uv_to_screen(uv)
		draw_set_transform(p, angle if absf(angle) < 1.2 else 0.0, Vector2.ONE)
		var at := Vector2(-tw * 0.5, fs * 0.35)
		if conflict:
			Estilo.placa(self, _area_font, at, txt, fs, Color(paper, 0.9), col, 1.5, col, Vector2(7, 3))
			_draw_swords(at + Vector2(-fs * 0.9, -fs * 0.35), fs * 0.5, col)
		else:
			Estilo.placa(self, _area_font, at, txt, fs, Color(paper, 0.68), col, 0.0, col, Vector2(7, 3))
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)

func _draw_swords(c: Vector2, r: float, col: Color) -> void:
	draw_line(c + Vector2(-r, -r), c + Vector2(r, r), col, 2.0, true)
	draw_line(c + Vector2(r, -r), c + Vector2(-r, r), col, 2.0, true)
	draw_line(c + Vector2(-r * 0.9, r * 0.4), c + Vector2(-r * 0.4, r * 0.9), col, 2.2, true)
	draw_line(c + Vector2(r * 0.9, r * 0.4), c + Vector2(r * 0.4, r * 0.9), col, 2.2, true)

# ---------------------------------------------------------------- rutas

func _dashed(a: Vector2, b: Vector2, col: Color, w: float, dash: float) -> void:
	var len: float = a.distance_to(b)
	if len < 0.5:
		return
	var n: int = int(len / dash)
	for i in range(0, n + 1, 2):
		var t0: float = float(i) * dash / len
		var t1: float = minf(float(i + 1) * dash / len, 1.0)
		if t0 >= 1.0:
			break
		draw_line(a.lerp(b, t0), a.lerp(b, t1), col, w, true)

func _draw_routes() -> void:
	# Las guardadas, finas y siempre; la que se mide, gruesa y solo en Viajes.
	for r in routes:
		var pts: Array = r["points"]
		for i in range(pts.size() - 1):
			_dashed(uv_to_screen(pts[i]), uv_to_screen(pts[i + 1]), Color(ink, 0.65), 1.6, 5.0)
	if not show_route or route.is_empty():
		return
	for i in range(route.size() - 1):
		var a: Vector2 = uv_to_screen(route[i])
		var b: Vector2 = uv_to_screen(route[i + 1])
		draw_line(a, b, Color(paper, 0.85), 5.0, true)
		_dashed(a, b, Estilo.ACENTO, 3.0, 9.0)
	for i in route.size():
		var p: Vector2 = uv_to_screen(route[i])
		draw_circle(p, 6.0, paper)
		draw_arc(p, 6.0, 0.0, TAU, 20, Estilo.ACENTO, 2.5, true)

## El contorno a mano: tinta sobre papel y, a trazos, el tramo que lo cerrará al soltar.
func _draw_outline() -> void:
	if outline.size() < 2:
		return
	var pts := PackedVector2Array()
	for u in outline:
		pts.append(uv_to_screen(u))
	draw_polyline(pts, Color(paper, 0.9), 5.0, true)
	draw_polyline(pts, Estilo.ACENTO, 2.5, true)
	_dashed(pts[pts.size() - 1], pts[0], Estilo.ACENTO, 2.0, 7.0)
	draw_circle(pts[0], 5.0, Estilo.ACENTO)

## El tiempo de la ruta, encima de todo (se pinta al final), junto al punto medio del último tramo.
func _draw_route_label() -> void:
	if not show_route or route.size() < 2 or route_label == "":
		return
	var a: Vector2 = uv_to_screen(route[route.size() - 2])
	var b: Vector2 = uv_to_screen(route[route.size() - 1])
	var f: Font = Estilo.versalitas()
	var m: Vector2 = (a + b) * 0.5
	Estilo.placa(self, f, m + Vector2(10, 22), route_label, 13, Estilo.TINTA, Estilo.ACENTO, 2.0, Estilo.PAPEL, Vector2(6, 4))

# ---------------------------------------------------------------- reinos

func _draw_realm_names() -> void:
	# Primero los imperios (los de arriba), luego los reinos que contienen: así los grandes eligen sitio antes.
	var order: Array = realms.duplicate()
	var depth := func(r: Dictionary) -> int:
		var d: int = 0
		var cur: int = int(r["id"])
		for i in 8:
			cur = int(realm_parent.call(cur))
			if cur <= 0:
				break
			d += 1
		return d
	order.sort_custom(func(a, b): return int(depth.call(a)) < int(depth.call(b)))
	for r in order:
		var id: int = int(r["id"])
		if not centroids.has(id):
			continue
		var c: Dictionary = centroids[id]
		var area: float = c["area"]
		var uv: Vector2 = c["uv"]
		var p: Vector2 = uv_to_screen(uv)
		var extent: float = sqrt(area) * minf(map_screen_size.x, map_screen_size.y)
		var has_kids: bool = false
		for o in realms:
			if int(realm_parent.call(int(o["id"]))) == id:
				has_kids = true
				break
		var d: int = int(depth.call(r))
		# Los que contienen reinos, más grandes; los de dentro, más pequeños.
		extent *= 1.25 if has_kids else (0.8 if d > 0 else 1.0)
		var kind: String = r["kind"]
		var nm: String = r["name"]
		var txt: String = (kind + " DE " + nm).to_upper()
		var col: Color = r["color"]
		# Ni más ancha que el territorio (×1,4) ni ilegible: si no cabe a 9 px, solo el nombre del reino.
		var w10: float = _realm_font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, 10).x
		var fs: int = int(clampf(minf(extent * 0.085, extent * 1.4 / maxf(w10, 1.0) * 10.0), 8.0, 30.0))
		if fs < 9:
			txt = nm.to_upper()
			w10 = _realm_font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, 10).x
			fs = int(clampf(extent * 1.4 / maxf(w10, 1.0) * 10.0, 9.0, 30.0))
		var tw: float = _realm_font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
		var at: Vector2 = _free_spot(p, tw, fs)
		var r2: Rect2 = Estilo.pestana(self, _realm_font, at, txt, fs, col)
		_taken.append(r2)

## Donde poner el nombre de un reino tapando los menos lugares posibles: prueba alturas alrededor del centro
## (el centro gana si empata).
func _free_spot(center: Vector2, tw: float, fs: int) -> Vector2:
	var offsets: Array[float] = [0.0, 1.3, -1.3, 2.5, -2.5, 3.7, -3.7, 5.0, -5.0]
	var best: Vector2 = center + Vector2(-tw * 0.5, fs * 0.35)
	var best_hits: int = 1 << 20
	for o in offsets:
		var at: Vector2 = center + Vector2(-tw * 0.5, fs * 0.35 + o * fs)
		var box := Rect2(at + Vector2(-6, -fs - 6), Vector2(tw + 12, fs + 14))
		var hits: int = 0
		for r in _taken:
			if r.intersects(box):
				hits += 1
		if hits < best_hits:
			best_hits = hits
			best = at
		if hits == 0:
			break
	return best

# ---------------------------------------------------------------- lugares

## Tamaño de símbolos y rótulos según el zoom: de lejos más pequeños (si no, tapan el mapa), de cerca algo más.
func ui_scale() -> float:
	return clampf(0.5 + 0.5 * map_zoom, 0.6, 1.25)

## [lugar, posición en pantalla, ¿lleva nombre?]; deja en _taken los símbolos y nombres que se pintarán.
## Caminos como en un mapa grabado: sendero a trazos, camino de una línea, calzada de dos líneas con el papel
## dentro, calzada imperial ancha con su piedra y una línea de puntos en medio.
func _draw_roads() -> void:
	var spp: float = map_screen_size.x / maxf(map_px.x, 1.0)
	for r in roads:
		_draw_road(r["points"], str(r["kind"]), spp, int(r["id"]) == selected_road)
	if road_draft.size() >= 2:
		_draw_road(road_draft, road_draft_kind, spp, false)

func _draw_road(pts_uv: Array, kind: String, spp: float, selected: bool) -> void:
	var line := PackedVector2Array()
	for q in pts_uv:
		line.append(uv_to_screen(Vector2(q)))
	if line.size() < 2:
		return
	var w: float = float(Catalogo.tipo_camino(kind)[2]) * map_px.x / 2048.0 * spp
	if selected:
		draw_polyline(line, Color(Estilo.ACENTO, 0.35), maxf(w, 3.0) + 10.0, true)
	var brown := Color(0.42, 0.31, 0.20)
	match kind:
		"sendero":
			_dashed_poly(line, Color(brown, 0.9), maxf(w * 0.6, 1.3), maxf(w * 2.2, 5.0))
		"camino":
			draw_polyline(line, Color(brown, 0.85), maxf(w * 0.7, 1.6), true)
		"calzada":
			draw_polyline(line, Color(ink, 0.85), maxf(w, 3.2), true)
			draw_polyline(line, paper, maxf(w - 2.0, 1.4), true)
		_:
			draw_polyline(line, ink, maxf(w + 1.0, 4.5), true)
			draw_polyline(line, Color(0.80, 0.72, 0.58), maxf(w - 1.5, 2.6), true)
			_dashed_poly(line, Color(ink, 0.7), 1.0, maxf(w * 0.8, 3.0))

## Una polilínea a trazos con el trazo continuo de un tramo al siguiente.
func _dashed_poly(line: PackedVector2Array, col: Color, w: float, dash: float) -> void:
	var on: bool = true
	var left: float = dash
	for i in range(line.size() - 1):
		var a: Vector2 = line[i]
		var b: Vector2 = line[i + 1]
		var seg: float = a.distance_to(b)
		var t: float = 0.0
		while t < seg:
			var step: float = minf(left, seg - t)
			if on:
				draw_line(a.lerp(b, t / seg), a.lerp(b, (t + step) / seg), col, w, true)
			t += step
			left -= step
			if left <= 0.0:
				on = not on
				left = dash if on else dash * 0.8

## Px de pantalla que mide una casa con el zoom actual.
func town_unit_px() -> float:
	return TOWN_UNIT * maxf(map_screen_size.x, map_screen_size.y)

func _plan_for(p: Dictionary) -> Dictionary:
	var kind: String = str(p["kind"])
	var sz: int = Asentamiento.size_of(p)
	var key: String = "%d:%s:%d" % [int(p["id"]), kind, sz]
	if not _plans.has(key):
		if _plans.size() > 200:
			_plans.clear()
		_plans[key] = Asentamiento.generate(kind, sz, int(p["id"]))
	return _plans[key]

## Al acercarse, cada asentamiento muestra su planta: calles, plaza, manzanas y muralla (como en un plano antiguo).
func _draw_towns() -> void:
	var up: float = town_unit_px()
	if up < 2.2:
		return
	var a: float = clampf((up - 2.2) / 1.5, 0.0, 1.0)
	for p in places:
		var kind: String = str(p["kind"])
		if not Asentamiento.has_size(kind):
			continue
		var plan: Dictionary = _plan_for(p)
		var c: Vector2 = uv_to_screen(p["uv"])
		var rad: float = float(plan["radius"]) * up
		if c.x < -rad or c.y < -rad or c.x > size.x + rad or c.y > size.y + rad:
			continue
		var road_w: float = maxf(Asentamiento.ROAD_HW * 2.0 * up, 1.0)
		for pts in plan["roads"]:
			var line := PackedVector2Array()
			for q in pts:
				line.append(c + (q as Vector2) * up)
			draw_polyline(line, Color(ink, 0.30 * a), road_w + 2.0, true)
		for pts in plan["roads"]:
			var line := PackedVector2Array()
			for q in pts:
				line.append(c + (q as Vector2) * up)
			draw_polyline(line, Color(paper.darkened(0.06), a), road_w, true)
		var pr: float = float(plan["plaza"]) * up
		if pr > 1.0:
			draw_circle(c, pr, Color(paper.darkened(0.08), a))
			draw_arc(c, pr, 0.0, TAU, 32, Color(ink, 0.35 * a), 1.0, true)
		for b in plan["buildings"]:
			var nm: String = b[0]
			var q: Vector2 = c + (b[1] as Vector2) * up
			var hs: float = up * float(b[3]) * (0.42 if nm.begins_with("home") else 0.5)
			if nm == "castle":
				hs = up * float(b[3]) * 0.9
			var ang: float = float(b[2])
			var ax := Vector2(cos(ang), sin(ang)) * hs
			var ay := Vector2(-ax.y, ax.x)
			var poly := PackedVector2Array([q - ax - ay, q + ax - ay, q + ax + ay, q - ax + ay])
			var fill: Color = ink if nm.begins_with("home") else (Estilo.ACENTO.darkened(0.35) if nm == "castle" or nm == "church" else ink.lerp(Estilo.ACENTO, 0.45))
			draw_colored_polygon(poly, Color(fill, 0.82 * a))
		var walls: Dictionary = plan["walls"]
		if not walls.is_empty():
			var ring := PackedVector2Array()
			var verts: Array = Asentamiento.wall_ring(walls)
			for q in verts:
				ring.append(c + (q as Vector2) * up)
			ring.append(ring[0])
			draw_polyline(ring, Color(ink, 0.9 * a), maxf(up * 0.38, 1.5), true)
			for i in range(1, verts.size(), 3):
				draw_circle(c + (verts[(i + 1) % verts.size()] as Vector2) * up, maxf(up * 0.36, 1.4), Color(ink, 0.9 * a))

func _layout_places() -> Array:
	var k: float = ui_scale()
	var order: Array = places.duplicate()
	order.sort_custom(func(a, b): return int(PLACE_ORDER.get(a["kind"], 20)) < int(PLACE_ORDER.get(b["kind"], 20)))
	var out: Array = []
	for p in order:
		var uv: Vector2 = p["uv"]
		var at: Vector2 = uv_to_screen(uv)
		if at.x < -40 or at.y < -40 or at.x > size.x + 40 or at.y > size.y + 40:
			continue
		out.append([p, at, false])
		_taken.append(Rect2(at - Vector2(11, 11) * k, Vector2(22, 22) * k))
	if not show_place_names:
		return out
	for e in out:
		var p: Dictionary = e[0]
		var at: Vector2 = e[1]
		var id: int = int(p["id"])
		var kind: String = p["kind"]
		var t: Array = Catalogo.tipo_lugar(kind)
		var min_zoom: float = t[2]
		if map_zoom < min_zoom and id != selected_place:
			continue
		# Derecha, izquierda, arriba, abajo: la primera que no pise nada.
		for dir in 4:
			var box: Rect2 = _place_name_box(kind, str(p["name"]), at, k, dir)
			var clash: bool = false
			if id != selected_place or dir < 3:
				for r in _taken:
					if r.intersects(box) and not r.has_point(at):
						clash = true
						break
			if not clash:
				_taken.append(box)
				e[2] = true
				e.append(dir)
				break
	return out

func _draw_places(layout: Array) -> void:
	var k: float = ui_scale()
	for i in range(layout.size() - 1, -1, -1):
		var p: Dictionary = layout[i][0]
		var at: Vector2 = layout[i][1]
		draw_set_transform(at, 0.0, Vector2(k, k))
		if int(p["id"]) == selected_place:
			draw_circle(Vector2.ZERO, 15.0, Color(Estilo.ACENTO, 0.25))
			draw_arc(Vector2.ZERO, 15.0, 0.0, TAU, 40, Estilo.ACENTO, 2.0, true)
		# Con la planta a la vista, el símbolo se queda en un punto (no tapa la plaza).
		if Asentamiento.has_size(str(p["kind"])) and town_unit_px() >= 3.0 and int(p["id"]) != selected_place:
			draw_circle(Vector2.ZERO, 3.5, ink)
			draw_circle(Vector2.ZERO, 2.0, paper)
		else:
			_draw_symbol(str(p["kind"]), Vector2.ZERO)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	for e in layout:
		if not bool(e[2]):
			continue
		var p: Dictionary = e[0]
		draw_set_transform(e[1], 0.0, Vector2(k, k))
		_draw_place_name(str(p["kind"]), str(p["name"]), Vector2.ZERO, int(e[3]))
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)

func _place_name_style(kind: String, nm: String) -> Array:
	if kind == "capital":
		return [Estilo.F_GROTESCA, 17, nm.to_upper()]
	if kind == "ciudad":
		return [Estilo.versalitas(), 13, nm.to_upper()]
	if kind == "aldea" or kind == "ruinas" or kind == "mina":
		return [Estilo.F_TEXTO, 11, nm]
	return [Estilo.F_SEMI, 12, nm]

## Línea base del nombre (en unidades sin escalar, desde el símbolo) según el lado: 0 dcha · 1 izda · 2 arriba · 3 abajo.
func _name_origin(tw: float, fs: int, dir: int) -> Vector2:
	match dir:
		1:
			return Vector2(-14.0 - tw, fs * 0.36)
		2:
			return Vector2(-tw * 0.5, -15.0)
		3:
			return Vector2(-tw * 0.5, 15.0 + fs * 0.8)
	return Vector2(14.0, fs * 0.36)

func _place_name_box(kind: String, nm: String, at: Vector2, k: float, dir: int = 0) -> Rect2:
	var st: Array = _place_name_style(kind, nm)
	var f: Font = st[0]
	var fs: int = st[1]
	var tw: float = f.get_string_size(str(st[2]), HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var o: Vector2 = _name_origin(tw, fs, dir)
	return Rect2(at + (o + Vector2(-5, -fs * 1.0 - 3)) * k, Vector2(tw + 10, fs + 8) * k)

func _draw_place_name(kind: String, nm: String, at: Vector2, dir: int = 0) -> void:
	var st: Array = _place_name_style(kind, nm)
	var f: Font = st[0]
	var fs: int = st[1]
	var txt: String = st[2]
	var tw: float = f.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var pos: Vector2 = at + _name_origin(tw, fs, dir)
	if kind == "capital":
		Estilo.placa(self, f, pos, txt, fs, ink, ink, 0.0, paper, Vector2(6, 3))
	elif kind == "ciudad":
		Estilo.placa(self, f, pos, txt, fs, Color(paper, 0.95), ink, 1.5, ink, Vector2(5, 3))
	else:
		Estilo.placa(self, f, pos, txt, fs, Color(paper, 0.9), Color(ink, 0.85), 1.0, ink, Vector2(4, 2))

func _draw_symbol(kind: String, c: Vector2) -> void:
	var k: Color = ink
	var w: Color = paper
	draw_circle(c, 10.5, Color(paper, 0.88))
	draw_arc(c, 10.5, 0.0, TAU, 28, Color(ink, 0.35), 1.0, true)
	match kind:
		"capital":
			draw_rect(Rect2(c - Vector2(7, 7), Vector2(14, 14)), k)
			draw_rect(Rect2(c - Vector2(4, 4), Vector2(8, 8)), w)
			draw_rect(Rect2(c - Vector2(2, 2), Vector2(4, 4)), k)
		"ciudad":
			draw_circle(c, 6.5, k)
			draw_circle(c, 3.2, w)
		"pueblo":
			draw_circle(c, 4.5, k)
		"aldea":
			draw_circle(c, 3.6, w)
			draw_arc(c, 3.6, 0.0, TAU, 20, k, 1.6, true)
		"castillo":
			draw_rect(Rect2(c + Vector2(-6, -4), Vector2(12, 10)), k)
			for i in 3:
				draw_rect(Rect2(c + Vector2(-6 + i * 4.5, -8), Vector2(3, 4)), k)
			draw_rect(Rect2(c + Vector2(-1.5, 1), Vector2(3, 5)), w)
		"puerto":
			draw_circle(c + Vector2(0, -6), 2.4, w)
			draw_arc(c + Vector2(0, -6), 2.4, 0.0, TAU, 16, k, 1.5, true)
			draw_line(c + Vector2(0, -3.5), c + Vector2(0, 6), k, 1.8)
			draw_arc(c + Vector2(0, 1), 5.5, 0.2, PI - 0.2, 14, k, 1.8, true)
			draw_line(c + Vector2(-3, -1), c + Vector2(3, -1), k, 1.6)
		"templo":
			draw_colored_polygon(PackedVector2Array([c + Vector2(-7, -2), c + Vector2(0, -8), c + Vector2(7, -2)]), k)
			draw_rect(Rect2(c + Vector2(-6, -1), Vector2(12, 7)), k)
			for i in 3:
				draw_rect(Rect2(c + Vector2(-4 + i * 3.2, 0.5), Vector2(1.6, 4.5)), w)
		"torre":
			draw_rect(Rect2(c + Vector2(-3, -7), Vector2(6, 14)), k)
			draw_rect(Rect2(c + Vector2(-4.5, -9), Vector2(9, 3)), k)
		"ruinas":
			draw_polyline(PackedVector2Array([c + Vector2(-6, -2), c + Vector2(-6, 6), c + Vector2(1, 6)]), k, 1.8)
			draw_polyline(PackedVector2Array([c + Vector2(6, 2), c + Vector2(6, -6), c + Vector2(-1, -6)]), k, 1.8)
		"mina":
			draw_line(c + Vector2(-6, -6), c + Vector2(6, 6), k, 2.0)
			draw_line(c + Vector2(6, -6), c + Vector2(-6, 6), k, 2.0)
			draw_line(c + Vector2(-8, -4), c + Vector2(-4, -8), k, 2.4)
			draw_line(c + Vector2(4, -8), c + Vector2(8, -4), k, 2.4)
		"batalla":
			draw_line(c + Vector2(-7, -7), c + Vector2(7, 7), k, 1.8)
			draw_line(c + Vector2(7, -7), c + Vector2(-7, 7), k, 1.8)
			draw_line(c + Vector2(-6, 2), c + Vector2(-2, 6), k, 1.8)
			draw_line(c + Vector2(6, 2), c + Vector2(2, 6), k, 1.8)
		_:
			draw_circle(c, 4.0, k)

# ---------------------------------------------------------------- norte y zoom

func _draw_north(at: Vector2) -> void:
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
	draw_string(f, top + Vector2(-n_w * 0.5, -6), "N", HORIZONTAL_ALIGNMENT_LEFT, -1, n_size, Estilo.TINTA)

func _draw_zoom_tag() -> void:
	var f: Font = Estilo.versalitas()
	var fs: int = 12
	var txt: String = "ZOOM %d %%" % zoom_pct
	var tw: float = f.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var pad := Vector2(10, 7)
	var box := Rect2(Vector2(size.x - tw - pad.x * 2.0 - 16.0, size.y - 16.0 - fs - pad.y * 2.0), Vector2(tw + pad.x * 2.0, fs + pad.y * 2.0))
	draw_rect(box, Estilo.TINTA)
	draw_string(f, box.position + Vector2(pad.x, pad.y + fs - 1), txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, Estilo.PAPEL)
