extends Node

## El mundo: tres capas que se pintan en la GPU (relieve, biomas y reinos), los reinos y los lugares, y un
## historial único para todo (deshacer un trazo de bioma, un reino nuevo o un pueblo movido es lo mismo).

signal layers_ready(size: Vector2i)
signal history_changed(can_undo: bool, can_redo: bool)
## Fracción de tierra (0..1) y altura máxima (0..1).
signal stats_changed(land_frac: float, max_height: float)
signal realms_changed()
signal places_changed()
## Los caminos dibujados cambiaron (añadir, quitar, cambiar tipo, deshacer).
signal roads_changed()
## id de reino → {"uv": Vector2 (centro), "area": float (fracción del mapa)}
signal realm_centroids_changed(centroids: Dictionary)
signal areas_changed()
## id de región → {"uv": Vector2, "angle": float (rad), "length": float (fracción del lado mayor)}
signal area_shapes_changed(shapes: Dictionary)
## Una capa ha cambiado (trazo, deshacer, ampliar…): "height", "biome", "realm" o "area".
signal layer_changed(layer_name: String)
## Guardado terminado (ok) o fallido.
signal saved(ok: bool)
## El mundo ha cambiado desde el último guardado (para el autoguardado).
signal dirty_changed()

## Lado máximo: 8.192 px (Adreno 7xx admite texturas de 16.384). A ese tamaño son ~1 GB entre GPU y CPU.
const MAX_SIDE: int = 8192
const MAPS_DIR: String = "user://mapas"
const SAVE_VERSION: int = 1
const MAX_HISTORY: int = 40
const MAX_HISTORY_BYTES: int = 96 * 1024 * 1024
const STATS_SIDE: int = 128
const CONFIG_PATH: String = "user://atlas.cfg"

const SHAPE_MODES = {"smooth": 0, "cliff": 1, "plateau": 2, "valley": 3, "canyon": 4, "swamp": 5, "lake": 6}
## Rellenos de contorno del relieve: herramienta → modo de height_fill.gdshader.
const FILL_MODES = {"land": 0, "lake": 1, "sea": 2, "swamp": 3}

var map_size: Vector2i = Vector2i(2048, 2048)
var hdr: bool = true
## El arranque anterior con alta precisión no terminó: se ha vuelto a 8 bits por seguridad.
var hdr_fallback: bool = false
var sea_level: float = 0.5
var world_seed: int = 42
var world_mode: int = 0
var stroke_count: int = 0
var realms: Array = []
var places: Array = []
## Regiones con nombre (bosques, montes, mares…): {id, name, kind, biome (0 = no pinta bioma)}
var areas: Array = []
## Relaciones entre reinos: {"a": id, "b": id (a < b), "kind": "alianza" | "comercio" | "tregua" | "guerra" | "vasallaje"}
var relations: Array = []
## Calibrado de viajes: horas por píxel de mapa ponderado (0 = sin calibrar) y km por píxel (0 = sin escala).
var travel: Dictionary = {"hours_per_px": 0.0, "km_per_px": 0.0, "sample": ""}
## Rutas guardadas: {"id", "name", "points": [Vector2 uv...], "mode": "pie"|"caballo"|"carro"|"barco"}
var routes: Array = []
## Caminos dibujados: {id, kind (Catalogo.TIPOS_CAMINO), points: [uv]}.
var roads: Array = []
## Rejilla de caminos a 1/4 del mapa (índice del tipo + 1), para el coste de los viajes.
var _road_grid: Image
var _road_grid_dirty: bool = true
var world_ready: bool = false
## Mapa abierto: carpeta en user://mapas y nombre.
var map_id: String = ""
var map_name: String = "Mi mundo"
## La novela de Nova a la que pertenece el mapa (id de familia/obras; "" = ninguna) y su título.
var map_obra: String = ""
var map_obra_t: String = ""
## Todos los escritos (obras de Cobalto) con los que está vinculado el mapa; map_obra es el primero (el de siempre).
var map_obras: Array = []
var _saving: bool = false
var _save_again: bool = false

var height: PaintLayer
var biome: PaintLayer
var realm: PaintLayer
var area: PaintLayer

var mat_init: ShaderMaterial
var _mat_add: ShaderMaterial
var _mat_sub: ShaderMaterial
var _mat_shape: ShaderMaterial
var _mat_id: ShaderMaterial

var undo_stack: Array = []
var redo_stack: Array = []
var rng := RandomNumberGenerator.new()
var _next_place_id: int = 1

var _stroke_tool: String = ""
var _stroke_value: int = 0
var _stroke_layer: PaintLayer = null
## Capas que pinta el trazo: [capa, valor]. Una región con bioma pinta las dos a la vez.
var _stroke_targets: Array = []
var _stroke_dirty: Rect2i = Rect2i()
var _stroke_has: bool = false
var _last_px: Vector2 = Vector2(-1, -1)
var _target_h: float = 0.6
var _snap_tex: Texture2D = null
var _path: PackedVector2Array = PackedVector2Array()
const PATH_MAX: int = 48

func _ready() -> void:
	rng.randomize()
	_boot_guard_start()
	_mat_add = _mat("res://shaders/height_brush_add.gdshader")
	_mat_sub = _mat("res://shaders/height_brush_sub.gdshader")
	_mat_shape = _mat("res://shaders/height_brush_shape.gdshader")
	_mat_id = _mat("res://shaders/id_brush.gdshader")
	mat_init = _mat("res://shaders/height_init.gdshader")
	height = PaintLayer.new()
	height.name = "Height"
	biome = PaintLayer.new()
	biome.name = "Biome"
	realm = PaintLayer.new()
	realm.name = "Realm"
	area = PaintLayer.new()
	area.name = "Area"
	for l in [height, biome, realm, area]:
		add_child(l)
	var li: int = 0
	for l in [height, biome, realm, area]:
		var idx: int = li
		(l as PaintLayer).mirror_updated.connect(func(r: Rect2i):
			_layer_dirty[idx] = true
			if idx == 0:
				_stats_rects.append(r)
		)
		li += 1
	# Nada de leer texturas durante _ready(): el mundo se crea (o se abre el último) unos fotogramas después.
	await get_tree().create_timer(0.25).timeout
	var cfg := ConfigFile.new()
	cfg.load(CONFIG_PATH)
	var last: String = str(cfg.get_value("mapas", "ultimo", ""))
	var opened: bool = false
	if last != "" and FileAccess.file_exists(MAPS_DIR + "/" + last + "/mundo.bin"):
		opened = await load_map(last)
	if not opened:
		await create_world(map_size, world_seed, 0)
	get_tree().create_timer(3.0).timeout.connect(_boot_guard_done)

func _mat(path: String) -> ShaderMaterial:
	var m := ShaderMaterial.new()
	m.shader = load(path)
	return m

# ---------------------------------------------------------------- ajustes y protector de arranque

func _boot_guard_start() -> void:
	var cfg := ConfigFile.new()
	cfg.load(CONFIG_PATH)
	hdr = bool(cfg.get_value("calidad", "alta_precision", true))
	if bool(cfg.get_value("arranque", "pendiente_alta_precision", false)):
		# El último arranque con alta precisión no llegó a los 4 s: se vuelve a 8 bits y se recuerda.
		hdr = false
		hdr_fallback = true
		cfg.set_value("calidad", "alta_precision", false)
	cfg.set_value("arranque", "pendiente_alta_precision", hdr)
	cfg.save(CONFIG_PATH)

func _notification(what: int) -> void:
	# Cerrar la app o mandarla al fondo con el mundo ya creado es un arranque bueno (no un cuelgue): sin esto,
	# abrir y cerrar en menos de 4 s apagaba la alta precisión en el siguiente arranque.
	if world_ready and (what == NOTIFICATION_APPLICATION_PAUSED or what == NOTIFICATION_WM_CLOSE_REQUEST or what == NOTIFICATION_WM_GO_BACK_REQUEST or what == NOTIFICATION_APPLICATION_FOCUS_OUT):
		_boot_guard_done()

func _boot_guard_done() -> void:
	var cfg := ConfigFile.new()
	cfg.load(CONFIG_PATH)
	cfg.set_value("arranque", "pendiente_alta_precision", false)
	cfg.save(CONFIG_PATH)

func set_high_precision(on: bool) -> void:
	if on == hdr:
		return
	var cfg := ConfigFile.new()
	cfg.load(CONFIG_PATH)
	cfg.set_value("calidad", "alta_precision", on)
	cfg.save(CONFIG_PATH)
	hdr = on
	hdr_fallback = false
	var img: Image = height.mirror.duplicate()
	height.setup(map_size, on)
	await height.load_image(img)
	layers_ready.emit(map_size)

# ---------------------------------------------------------------- mundos

## mode: 0 = continente generado · 1 = océano vacío
func create_world(size: Vector2i, seed: int, mode: int, inf: bool = false) -> void:
	world_ready = false
	infinite = inf
	if inf:
		size = Vector2i(INF_WINDOW, INF_WINDOW)
		world_origin = -size / 2
	else:
		world_origin = Vector2i.ZERO
	map_size = Vector2i(clampi(size.x, 256, MAX_SIDE), clampi(size.y, 256, MAX_SIDE))
	world_seed = seed
	world_mode = mode
	_all_dirty()
	height.setup(map_size, hdr)
	biome.setup(map_size, false)
	realm.setup(map_size, false)
	area.setup(map_size, false)
	var short_side: float = float(mini(map_size.x, map_size.y))
	mat_init.set_shader_parameter("seed", float(seed))
	mat_init.set_shader_parameter("mode", mode)
	mat_init.set_shader_parameter("aspect_scale", Vector2(map_size) / short_side)
	_init_window_params()
	await height.fill(mat_init)
	await biome.fill(null, Color.BLACK)
	await realm.fill(null, Color.BLACK)
	await area.fill(null, Color.BLACK)
	# Una lectura completa para el espejo del relieve (las otras dos empiezan a 0, como su espejo).
	await height.read_region(Rect2i(Vector2i.ZERO, map_size))
	undo_stack.clear()
	redo_stack.clear()
	realms.clear()
	places.clear()
	areas.clear()
	relations.clear()
	routes.clear()
	roads.clear()
	_road_grid_dirty = true
	travel = {"hours_per_px": 0.0, "km_per_px": 0.0, "sample": ""}
	_next_place_id = 1
	stroke_count = 0
	map_id = _new_map_id()
	map_name = "Mundo nº %04d" % seed
	map_obra = ""
	map_obra_t = ""
	map_obras = []
	world_ready = true
	layers_ready.emit(map_size)
	_emit_history()
	realms_changed.emit()
	places_changed.emit()
	roads_changed.emit()
	_compute_stats()
	realm_centroids_changed.emit({})
	areas_changed.emit()
	area_shapes_changed.emit({})
	layer_changed.emit("height")
	layer_changed.emit("biome")

## Amplía el lienzo hacia un lado ("N", "S", "E", "O") en [frac] del tamaño actual. Lo nuevo es mar profundo.
## No se puede deshacer (el historial se vacía): cambian los tamaños de todo.
func extend(dir: String, frac: float = 0.5) -> bool:
	if infinite:
		return false
	var add := Vector2i.ZERO
	var offset := Vector2i.ZERO
	if dir == "N" or dir == "S":
		add.y = mini(int(map_size.y * frac), MAX_SIDE - map_size.y)
		if dir == "N":
			offset.y = add.y
	else:
		add.x = mini(int(map_size.x * frac), MAX_SIDE - map_size.x)
		if dir == "O":
			offset.x = add.x
	if add.x <= 0 and add.y <= 0:
		return false
	var old_size: Vector2i = map_size
	var new_size: Vector2i = map_size + add
	world_ready = false
	var fills: Array = [Color(sea_level - 0.12, 0, 0), Color(0, 0, 0), Color(0, 0, 0), Color(0, 0, 0)]
	var layers: Array[PaintLayer] = [height, biome, realm, area]
	for i in layers.size():
		var l: PaintLayer = layers[i]
		var img := Image.create(new_size.x, new_size.y, false, l.mirror_format)
		var bg: Color = fills[i]
		img.fill(bg)
		img.blit_rect(l.mirror, Rect2i(Vector2i.ZERO, old_size), offset)
		l.setup(new_size, l.hdr)
		await l.load_image(img)
	map_size = new_size
	_all_dirty()
	for p in places:
		var uv: Vector2 = p["uv"]
		p["uv"] = (uv * Vector2(old_size) + Vector2(offset)) / Vector2(new_size)
	for r in routes + roads:
		var pts: Array = r["points"]
		for i in pts.size():
			pts[i] = (Vector2(pts[i]) * Vector2(old_size) + Vector2(offset)) / Vector2(new_size)
	_road_grid_dirty = true
	# La escala (km y horas por píxel) no cambia: los píxeles viejos miden lo mismo.
	undo_stack.clear()
	redo_stack.clear()
	world_ready = true
	layers_ready.emit(map_size)
	_emit_history()
	places_changed.emit()
	roads_changed.emit()
	_compute_stats()
	_compute_centroids()
	_compute_area_shapes()
	layer_changed.emit("height")
	layer_changed.emit("biome")
	return true

# ---------------------------------------------------------------- trazos

func _layer_for(tool: String) -> PaintLayer:
	if tool == "biome":
		return biome
	if tool == "realm":
		return realm
	if tool == "area":
		return area
	return height

func _layer_name(l: PaintLayer) -> String:
	if l == height:
		return "height"
	if l == biome:
		return "biome"
	if l == realm:
		return "realm"
	return "area"

func _layer_by_name(n: String) -> PaintLayer:
	match n:
		"height":
			return height
		"biome":
			return biome
		"realm":
			return realm
	return area

## [value]: el id del bioma o del reino que se pinta (0 = borrar).
func begin_stroke(tool: String, value: int, start_uv: Vector2) -> void:
	_stroke_tool = tool
	_stroke_value = value
	_stroke_layer = _layer_for(tool)
	_stroke_targets = [[_stroke_layer, value]]
	if tool == "area":
		var a: Dictionary = area_by_id(value)
		if not a.is_empty() and int(a["biome"]) > 0:
			_stroke_targets.append([biome, int(a["biome"])])
	elif tool == "swamp":
		_stroke_targets.append([biome, Catalogo.bioma_id("PANTANO")])
	_stroke_dirty = Rect2i()
	_stroke_has = false
	_last_px = Vector2(-1, -1)
	_path.clear()
	if SHAPE_MODES.has(tool):
		_snap_tex = height.snapshot_texture()
		_target_h = height.sample_mirror(Vector2i(start_uv * Vector2(map_size)))

func paint_uv(uv: Vector2, radius_px: float, strength: float, steps: float = 7.0) -> void:
	if not world_ready or _stroke_layer == null:
		return
	if uv.x < -0.05 or uv.x > 1.05 or uv.y < -0.05 or uv.y > 1.05:
		return
	var curr_px: Vector2 = uv * Vector2(map_size)
	var prev_px: Vector2 = curr_px if _last_px.x < 0.0 else _last_px
	var reach: float = radius_px + 2.0
	var min_pt := Vector2(minf(prev_px.x, curr_px.x), minf(prev_px.y, curr_px.y)) - Vector2(reach, reach)
	var max_pt := Vector2(maxf(prev_px.x, curr_px.x), maxf(prev_px.y, curr_px.y)) + Vector2(reach, reach)
	var rect_sz: Vector2 = max_pt - min_pt

	var base: ShaderMaterial
	var tool: String = _stroke_tool
	if tool == "land" or tool == "mountain":
		base = _mat_add
	elif tool == "sea" or tool == "river":
		base = _mat_sub
	elif SHAPE_MODES.has(tool):
		base = _mat_shape
	else:
		base = _mat_id
	# Un material por pincelada: si caen varias en el mismo fotograma, cada una conserva sus parámetros.
	var m: ShaderMaterial = base.duplicate() as ShaderMaterial
	m.set_shader_parameter("rect_min", min_pt)
	m.set_shader_parameter("rect_size", rect_sz)
	m.set_shader_parameter("p0", prev_px)
	m.set_shader_parameter("p1", curr_px)
	m.set_shader_parameter("brush_radius", radius_px)
	# Las demás capas del trazo (el bioma de una región o de un pantano), con su propio valor.
	for i in range(1, _stroke_targets.size()):
		var t: Array = _stroke_targets[i]
		var m2: ShaderMaterial = _mat_id.duplicate() as ShaderMaterial
		for k in ["rect_min", "rect_size", "p0", "p1"]:
			m2.set_shader_parameter(k, m.get_shader_parameter(k))
		m2.set_shader_parameter("brush_radius", radius_px * 0.9)
		m2.set_shader_parameter("value", float(int(t[1])) / 255.0)
		(t[0] as PaintLayer).dab(m2, Rect2(min_pt, rect_sz))
	if base == _mat_id:
		m.set_shader_parameter("value", float(_stroke_value) / 255.0)
	elif base == _mat_shape:
		m.set_shader_parameter("mode", int(SHAPE_MODES[tool]))
		m.set_shader_parameter("snap", _snap_tex)
		m.set_shader_parameter("map_size", Vector2(map_size))
		m.set_shader_parameter("sea_level", sea_level)
		m.set_shader_parameter("target_h", _target_h)
		m.set_shader_parameter("steps", steps)
		m.set_shader_parameter("strength", strength)
		_path.append(curr_px)
		if _path.size() > PATH_MAX:
			_path = _path.slice(_path.size() - PATH_MAX)
		m.set_shader_parameter("path", _path)
		m.set_shader_parameter("path_count", _path.size())
	else:
		m.set_shader_parameter("strength", strength)
		m.set_shader_parameter("map_size", Vector2(map_size))
		m.set_shader_parameter("brush_mode", 1 if (tool == "mountain" or tool == "river") else 0)
	_stroke_layer.dab(m, Rect2(min_pt, rect_sz))
	var r := Rect2i(Vector2i(min_pt.floor()), Vector2i(rect_sz.ceil()) + Vector2i(2, 2))
	_stroke_dirty = r if not _stroke_has else _stroke_dirty.merge(r)
	_stroke_has = true
	_last_px = curr_px

func end_stroke() -> void:
	if not _stroke_has or _stroke_layer == null:
		_stroke_layer = null
		return
	var targets: Array = _stroke_targets
	var rect: Rect2i = _stroke_dirty.intersection(Rect2i(Vector2i.ZERO, map_size))
	_stroke_layer = null
	_stroke_targets = []
	_stroke_has = false
	_last_px = Vector2(-1, -1)
	if rect.size.x <= 0 or rect.size.y <= 0:
		return
	var befores: Array = []
	for t in targets:
		befores.append((t[0] as PaintLayer).crop_mirror(rect))
	var items: Array = []
	for i in targets.size():
		var layer: PaintLayer = targets[i][0]
		var after: Image = await layer.read_region(rect)
		if after == null:
			continue
		items.append({"t": "paint", "layer": _layer_name(layer), "rect": rect, "before": befores[i], "after": after})
	if items.is_empty():
		return
	stroke_count += 1
	if items.size() == 1:
		_push(items[0])
	else:
		_push({"t": "multi", "items": items, "stroke": true})
	for t in targets:
		_after_layer_change(t[0])

func _after_layer_change(layer: PaintLayer) -> void:
	if layer == height:
		_compute_stats()
	elif layer == realm:
		_compute_centroids()
	elif layer == area:
		_compute_area_shapes()
	layer_changed.emit(_layer_name(layer))

# ---------------------------------------------------------------- relleno de contornos

## Polígonos simples a partir de un contorno a mano (cerrado solo): sin puntos repetidos y, si se cruza consigo
## mismo, deshecho en piezas simples con Clipper (unión), para que el triangulado no falle.
static func _clean_outline(pts_px: PackedVector2Array) -> Array:
	var clean := PackedVector2Array()
	for p in pts_px:
		if clean.is_empty() or clean[clean.size() - 1].distance_to(p) >= 2.0:
			clean.append(p)
	if clean.size() < 3:
		return []
	var out: Array = []
	if not Geometry2D.triangulate_polygon(clean).is_empty():
		out.append(clean)
		return out
	for poly in Geometry2D.merge_polygons(clean, clean):
		var pp: PackedVector2Array = poly
		# Los agujeros (orientación contraria) también se rellenan: es un lazo, no una forma con huecos.
		if pp.size() >= 3 and not Geometry2D.triangulate_polygon(pp).is_empty():
			out.append(pp)
	return out

## Rellena el interior de un contorno dibujado (uv del mapa). [tool]: "land", "lake", "sea", "swamp" (relieve), "biome",
## "realm" o "area" (con [value], el id). Un paso del historial. Devuelve el rectángulo tocado (vacío si nada).
func fill_outline(tool: String, value: int, pts_uv: Array) -> Rect2i:
	if not world_ready:
		return Rect2i()
	var pts := PackedVector2Array()
	for u in pts_uv:
		pts.append(Vector2(u) * Vector2(map_size))
	var polys: Array = _clean_outline(pts)
	if polys.is_empty():
		return Rect2i()
	var bounds := Rect2(pts[0], Vector2.ZERO)
	for p in pts:
		bounds = bounds.expand(p)
	var margin: int = 24
	var rect := Rect2i(Vector2i(bounds.position.floor()) - Vector2i(margin, margin), Vector2i(bounds.size.ceil()) + Vector2i(margin * 2, margin * 2))
	rect = rect.intersection(Rect2i(Vector2i.ZERO, map_size))
	if rect.size.x <= 0 or rect.size.y <= 0:
		return Rect2i()
	# Capas y valores que pinta.
	var targets: Array = []
	if FILL_MODES.has(tool):
		targets.append([height, -1])
		if tool == "swamp":
			targets.append([biome, Catalogo.bioma_id("PANTANO")])
	elif tool == "biome":
		targets.append([biome, value])
	elif tool == "realm":
		targets.append([realm, value])
	elif tool == "area":
		targets.append([area, value])
		var a: Dictionary = area_by_id(value)
		if not a.is_empty() and int(a["biome"]) > 0:
			targets.append([biome, int(a["biome"])])
	var befores: Array = []
	for t in targets:
		befores.append((t[0] as PaintLayer).crop_mirror(rect))
	for t in targets:
		var layer: PaintLayer = t[0]
		var v: int = t[1]
		for poly in polys:
			var pg := Polygon2D.new()
			pg.polygon = poly
			pg.antialiased = false
			if v < 0:
				var mat := ShaderMaterial.new()
				mat.shader = preload("res://shaders/height_fill.gdshader")
				mat.set_shader_parameter("mode", int(FILL_MODES[tool]))
				mat.set_shader_parameter("sea_level", sea_level)
				mat.set_shader_parameter("seed", float(world_seed % 97))
				pg.material = mat
				pg.texture = height.snapshot_texture()
				var uvs := PackedVector2Array()
				for p in (poly as PackedVector2Array):
					uvs.append(p)
				pg.uv = uvs
			else:
				pg.color = Color(float(v) / 255.0, float(v) / 255.0, float(v) / 255.0, 1.0)
			await layer.draw_once(pg)
		await layer.read_region(rect)
		if v < 0:
			# La costa, suave pero siguiendo el trazo: se suaviza a lo largo del contorno.
			var r_px: float = clampf(float(maxi(map_size.x, map_size.y)) / 1024.0 * 7.0, 5.0, 40.0)
			for poly in polys:
				var pp: PackedVector2Array = poly
				begin_stroke("smooth", 0, pp[0] / Vector2(map_size))
				_snap_tex = height.snapshot_texture()
				for i in pp.size() + 1:
					paint_uv(pp[i % pp.size()] / Vector2(map_size), r_px, 0.55)
					if i % 24 == 23:
						await get_tree().process_frame
				_stroke_layer = null
				_stroke_has = false
			await height.read_region(rect)
	var items: Array = []
	for i in targets.size():
		var layer: PaintLayer = targets[i][0]
		items.append({"t": "paint", "layer": _layer_name(layer), "rect": rect, "before": befores[i], "after": layer.crop_mirror(rect)})
	stroke_count += 1
	_push(items[0] if items.size() == 1 else {"t": "multi", "items": items, "stroke": true})
	for t in targets:
		_after_layer_change(t[0])
	return rect

# ---------------------------------------------------------------- historial

func _entry_bytes(e: Dictionary) -> int:
	var t: String = e["t"]
	if t == "paint":
		var a: Image = e["after"]
		return a.get_data().size() * 2
	if t == "multi":
		var s: int = 0
		for it in e["items"]:
			s += _entry_bytes(it)
		return s
	return 256

func _push(e: Dictionary) -> void:
	_mark_dirty_entry(e)
	undo_stack.append(e)
	redo_stack.clear()
	var total: int = 0
	for x in undo_stack:
		total += _entry_bytes(x)
	while undo_stack.size() > MAX_HISTORY or (total > MAX_HISTORY_BYTES and undo_stack.size() > 1):
		total -= _entry_bytes(undo_stack[0])
		undo_stack.pop_front()
	_emit_history()

func _emit_history() -> void:
	history_changed.emit(not undo_stack.is_empty(), not redo_stack.is_empty())
	dirty_changed.emit()

func undo() -> void:
	if undo_stack.is_empty() or not world_ready:
		return
	var e: Dictionary = undo_stack.pop_back()
	redo_stack.append(e)
	await _apply(e, false)
	_emit_history()

func redo() -> void:
	if redo_stack.is_empty() or not world_ready:
		return
	var e: Dictionary = redo_stack.pop_back()
	undo_stack.append(e)
	await _apply(e, true)
	_emit_history()

## Mundo infinito: qué trozos de la ventana han cambiado (solo esos se escriben al moverse o guardar; los que no
## se han tocado los vuelve a hacer igual el generador).
var _dirty_chunks: Dictionary = {}

func _mark_dirty_entry(e: Dictionary) -> void:
	if not infinite:
		return
	if str(e.get("t", "")) == "paint":
		var r: Rect2i = e["rect"]
		var c0: Vector2i = (world_origin + r.position) / CHUNK
		var c1: Vector2i = (world_origin + r.end - Vector2i.ONE) / CHUNK
		for cy in range(mini(c0.y, c1.y), maxi(c0.y, c1.y) + 1):
			for cx in range(mini(c0.x, c1.x), maxi(c0.x, c1.x) + 1):
				_dirty_chunks[Vector2i(cx, cy)] = true
	elif str(e.get("t", "")) == "multi":
		for it in e["items"]:
			_mark_dirty_entry(it)

func _apply(e: Dictionary, forward: bool) -> void:
	_mark_dirty_entry(e)
	var t: String = e["t"]
	if t == "paint":
		var l: PaintLayer = _layer_by_name(e["layer"])
		var img: Image = e["after"] if forward else e["before"]
		if not bool(e.get("in_stroke", false)):
			stroke_count = maxi(stroke_count + (1 if forward else -1), 0)
		await l.restore_region(e["rect"], img)
		_after_layer_change(l)
	elif t == "meta":
		var snap: Dictionary = e["after"] if forward else e["before"]
		_set_meta(snap)
	elif t == "multi":
		var items: Array = e["items"]
		var order: Array = items if forward else _reversed(items)
		if bool(e.get("stroke", false)):
			stroke_count = maxi(stroke_count + (1 if forward else -1), 0)
		for it in order:
			var sub: Dictionary = it
			if bool(e.get("stroke", false)):
				sub["in_stroke"] = true
			await _apply(sub, forward)

func _reversed(a: Array) -> Array:
	var r: Array = a.duplicate()
	r.reverse()
	return r

func _meta_snapshot() -> Dictionary:
	return {"realms": realms.duplicate(true), "places": places.duplicate(true), "areas": areas.duplicate(true),
		"relations": relations.duplicate(true), "travel": travel.duplicate(true), "routes": routes.duplicate(true),
		"roads": roads.duplicate(true), "next_place": _next_place_id}

func _set_meta(snap: Dictionary) -> void:
	var rs: Array = snap["realms"]
	var ps: Array = snap["places"]
	realms = rs.duplicate(true)
	places = ps.duplicate(true)
	var ars: Array = snap.get("areas", [])
	areas = ars.duplicate(true)
	var rels: Array = snap.get("relations", [])
	relations = rels.duplicate(true)
	var tr: Dictionary = snap.get("travel", {"hours_per_px": 0.0, "km_per_px": 0.0, "sample": ""})
	travel = tr.duplicate(true)
	var rts: Array = snap.get("routes", [])
	routes = rts.duplicate(true)
	var rds: Array = snap.get("roads", [])
	roads = rds.duplicate(true)
	_road_grid_dirty = true
	_next_place_id = int(snap["next_place"])
	realms_changed.emit()
	places_changed.emit()
	areas_changed.emit()
	roads_changed.emit()

func _commit_meta(before: Dictionary) -> void:
	_push({"t": "meta", "before": before, "after": _meta_snapshot()})
	if before.get("roads", []) != roads:
		_road_grid_dirty = true
		roads_changed.emit()
	realms_changed.emit()
	places_changed.emit()
	areas_changed.emit()

# ---------------------------------------------------------------- reinos

func realm_by_id(id: int) -> Dictionary:
	for r in realms:
		if int(r["id"]) == id:
			return r
	return {}

func add_realm() -> int:
	var used: Dictionary = {}
	for r in realms:
		used[int(r["id"])] = true
	var id: int = 1
	while used.has(id) and id < 63:
		id += 1
	if used.has(id):
		return -1
	var before: Dictionary = _meta_snapshot()
	var col: Color = Catalogo.COLORES_REINO[(id - 1) % Catalogo.COLORES_REINO.size()]
	realms.append({"id": id, "name": Nombres.reino(rng), "kind": "REINO", "color": col, "parent": 0})
	_commit_meta(before)
	return id

func update_realm(id: int, key: String, value: Variant) -> void:
	var r: Dictionary = realm_by_id(id)
	if r.is_empty() or (typeof(r[key]) == typeof(value) and r[key] == value):
		return
	var before: Dictionary = _meta_snapshot()
	r[key] = value
	_commit_meta(before)

## Quita las rayas del relieve (escalones rectos del ruido antiguo en algunos móviles): una pasada del filtro
## height_seams por escala (de fina a gruesa) sobre todo el mapa. Un solo paso del historial. Devuelve cuántos píxeles cambiaron (aprox.).
func repair_seams(scales: Array = [1.0, 1.0, 2.0, 4.0]) -> int:
	if not world_ready:
		return 0
	var before_img: Image = height.mirror.duplicate()
	var after_img: Image = before_img
	for sc in scales:
		var m := _mat("res://shaders/height_seams.gdshader")
		m.set_shader_parameter("scale", float(sc))
		m.set_shader_parameter("snap", height.snapshot_texture())
		m.set_shader_parameter("map_size", Vector2(map_size))
		m.set_shader_parameter("rect_min", Vector2.ZERO)
		m.set_shader_parameter("rect_size", Vector2(map_size))
		after_img = await height.apply_full(m)
	var changed: int = 0
	var step: int = maxi(map_size.x / 256, 1)
	for y in range(0, map_size.y, step):
		for x in range(0, map_size.x, step):
			if absf(after_img.get_pixel(x, y).r - before_img.get_pixel(x, y).r) > 0.0005:
				changed += 1
	var full := Rect2i(Vector2i.ZERO, map_size)
	_push({"t": "paint", "layer": "height", "rect": full, "before": before_img, "after": after_img})
	layer_changed.emit("height")
	_compute_stats()
	return changed * step * step

## Borra el reino y su territorio (pasa a «sin reino»). Un solo paso del historial.
func delete_realm(id: int) -> void:
	var r: Dictionary = realm_by_id(id)
	if r.is_empty():
		return
	var before_meta: Dictionary = _meta_snapshot()
	var before_img: Image = realm.mirror.duplicate()
	var m := _mat("res://shaders/id_remap.gdshader")
	m.set_shader_parameter("snap", realm.snapshot_texture())
	m.set_shader_parameter("from_id", id)
	m.set_shader_parameter("to_id", 0)
	var after_img: Image = await realm.apply_full(m)
	realms.erase(r)
	relations = relations.filter(func(x): return int(x["a"]) != id and int(x["b"]) != id)
	# Sus reinos pasan a depender de quien dependía él.
	for o in realms:
		if int(o.get("parent", 0)) == id:
			o["parent"] = int(r.get("parent", 0))
	var full := Rect2i(Vector2i.ZERO, map_size)
	_push({"t": "multi", "items": [
		{"t": "paint", "layer": "realm", "rect": full, "before": before_img, "after": after_img},
		{"t": "meta", "before": before_meta, "after": _meta_snapshot()},
	]})
	realms_changed.emit()
	_compute_centroids()

# ---------------------------------------------------------------- imperios y reinos

func realm_parent(id: int) -> int:
	return int(realm_by_id(id).get("parent", 0))

## El de más arriba de la cadena (el imperio); él mismo si no pertenece a nadie.
func realm_top(id: int) -> int:
	var cur: int = id
	for i in 64:
		var p: int = realm_parent(cur)
		if p <= 0 or realm_by_id(p).is_empty():
			return cur
		cur = p
	return cur

func realm_children(id: int) -> Array:
	return realms.filter(func(o): return int(o.get("parent", 0)) == id)

## ¿Es [anc] antepasado de [id] (o el mismo)?
func realm_is_within(id: int, anc: int) -> bool:
	var cur: int = id
	for i in 64:
		if cur == anc:
			return true
		cur = realm_parent(cur)
		if cur <= 0:
			return false
	return false

## Hace a [id] parte de [parent] (0 = independiente). No deja ciclos.
func set_realm_parent(id: int, parent: int) -> void:
	if parent > 0 and realm_is_within(parent, id):
		return
	update_realm(id, "parent", parent)

# ---------------------------------------------------------------- relaciones entre reinos

const RELATION_KINDS = ["alianza", "comercio", "tregua", "guerra", "vasallaje"]

func relation(a: int, b: int) -> String:
	var lo: int = mini(a, b)
	var hi: int = maxi(a, b)
	for r in relations:
		if int(r["a"]) == lo and int(r["b"]) == hi:
			return str(r["kind"])
	return ""

## [kind] vacío quita la relación. Un paso del historial.
func set_relation(a: int, b: int, kind: String) -> void:
	if a == b or relation(a, b) == kind:
		return
	var before: Dictionary = _meta_snapshot()
	var lo: int = mini(a, b)
	var hi: int = maxi(a, b)
	relations = relations.filter(func(x): return not (int(x["a"]) == lo and int(x["b"]) == hi))
	if kind != "":
		relations.append({"a": lo, "b": hi, "kind": kind})
	_commit_meta(before)

# ---------------------------------------------------------------- viajes

## Kilómetros por píxel del mapa: la escala calibrada en Viajes o puesta en Mundo; sin ninguna, 1 km por píxel
## (un mapa de 2.048 px mide unos 2.000 km de lado, como un continente).
const DEFAULT_KM_PX := 1.0

func km_per_px() -> float:
	var k: float = float(travel.get("km_per_px", 0.0))
	return k if k > 0.0 else DEFAULT_KM_PX

func scale_is_set() -> bool:
	return float(travel.get("km_per_px", 0.0)) > 0.0

## Pone la escala (km por píxel). Un paso del historial; la usan también los viajes.
func set_km_per_px(k: float) -> void:
	if k <= 0.0:
		return
	var tr: Dictionary = travel.duplicate(true)
	tr["km_per_px"] = k
	set_travel(tr)

func set_travel(data: Dictionary) -> void:
	var before: Dictionary = _meta_snapshot()
	travel = data.duplicate(true)
	_commit_meta(before)

func add_route(points: Array, mode: String, name: String) -> int:
	var before: Dictionary = _meta_snapshot()
	var id: int = 1
	for r in routes:
		id = maxi(id, int(r["id"]) + 1)
	routes.append({"id": id, "name": name, "points": points.duplicate(), "mode": mode})
	_commit_meta(before)
	return id

func delete_route(id: int) -> void:
	var before: Dictionary = _meta_snapshot()
	routes = routes.filter(func(r): return int(r["id"]) != id)
	_commit_meta(before)

# ---------------------------------------------------------------- caminos

func road_by_id(id: int) -> Dictionary:
	for r in roads:
		if int(r["id"]) == id:
			return r
	return {}

func add_road(kind: String, points: Array) -> int:
	if points.size() < 2:
		return -1
	var before: Dictionary = _meta_snapshot()
	var id: int = 1
	for r in roads:
		id = maxi(id, int(r["id"]) + 1)
	roads.append({"id": id, "kind": kind, "points": points.duplicate()})
	_commit_meta(before)
	return id

func set_road_kind(id: int, kind: String) -> void:
	var r: Dictionary = road_by_id(id)
	if r.is_empty() or str(r["kind"]) == kind:
		return
	var before: Dictionary = _meta_snapshot()
	r["kind"] = kind
	_commit_meta(before)

func delete_road(id: int) -> void:
	if road_by_id(id).is_empty():
		return
	var before: Dictionary = _meta_snapshot()
	roads = roads.filter(func(r): return int(r["id"]) != id)
	_commit_meta(before)

## El camino más cercano a [uv] a menos de [max_px] píxeles del mapa (-1 si ninguno).
func road_at(uv: Vector2, max_px: float) -> int:
	var p: Vector2 = uv * Vector2(map_size)
	var best: int = -1
	var best_d: float = max_px
	for r in roads:
		var pts: Array = r["points"]
		for i in range(pts.size() - 1):
			var a: Vector2 = Vector2(pts[i]) * Vector2(map_size)
			var b: Vector2 = Vector2(pts[i + 1]) * Vector2(map_size)
			var ab: Vector2 = b - a
			var t: float = clampf((p - a).dot(ab) / maxf(ab.length_squared(), 1e-6), 0.0, 1.0)
			var d: float = p.distance_to(a + ab * t)
			if d < best_d:
				best_d = d
				best = int(r["id"])
	return best

## Ancho de un tipo de camino en píxeles de este mapa.
func road_width_px(kind: String) -> float:
	return float(Catalogo.tipo_camino(kind)[2]) * float(maxi(map_size.x, map_size.y)) / 2048.0

func _road_grid_update() -> void:
	if not _road_grid_dirty and _road_grid != null:
		return
	_road_grid_dirty = false
	var gs := Vector2i(maxi(map_size.x / 4, 1), maxi(map_size.y / 4, 1))
	_road_grid = Image.create(gs.x, gs.y, false, Image.FORMAT_L8)
	for r in roads:
		var t: Array = Catalogo.tipo_camino(str(r["kind"]))
		var v: float = float(Catalogo.TIPOS_CAMINO.find(t) + 1) / 255.0
		var half: int = maxi(int(road_width_px(str(r["kind"])) / 8.0), 1)
		var pts: Array = r["points"]
		for i in range(pts.size() - 1):
			var a: Vector2 = Vector2(pts[i]) * Vector2(gs)
			var b: Vector2 = Vector2(pts[i + 1]) * Vector2(gs)
			var n: int = maxi(int(a.distance_to(b)), 1)
			for k in n + 1:
				var q: Vector2i = Vector2i(a.lerp(b, float(k) / float(n)))
				_road_grid.fill_rect(Rect2i(q - Vector2i(half, half), Vector2i(half * 2 + 1, half * 2 + 1)).intersection(Rect2i(Vector2i.ZERO, gs)), Color(v, v, v))

## Lo que cuesta un paso en [px] (píxeles del mapa) por estar en un camino (1 si no hay camino).
func road_factor(px: Vector2) -> float:
	if roads.is_empty():
		return 1.0
	_road_grid_update()
	var q := Vector2i(clampi(int(px.x / 4.0), 0, _road_grid.get_width() - 1), clampi(int(px.y / 4.0), 0, _road_grid.get_height() - 1))
	var idx: int = int(round(_road_grid.get_pixelv(q).r * 255.0))
	if idx <= 0 or idx > Catalogo.TIPOS_CAMINO.size():
		return 1.0
	return float(Catalogo.TIPOS_CAMINO[idx - 1][4])

## Longitud ponderada de una ruta (en píxeles del mapa): cada tramo cuesta más si sube o baja mucho, si va por
## montaña o si cruza agua yendo por tierra (y al revés en barco). Muestreo cada ~4 px.
func route_cost(points: Array, mode: String) -> Dictionary:
	var total_px: float = 0.0
	var weighted: float = 0.0
	var water_px: float = 0.0
	var climb: float = 0.0
	var by_sea: bool = mode == "barco"
	for i in range(points.size() - 1):
		var a: Vector2 = Vector2(points[i]) * Vector2(map_size)
		var b: Vector2 = Vector2(points[i + 1]) * Vector2(map_size)
		var seg: float = a.distance_to(b)
		var n: int = maxi(1, int(seg / 4.0))
		var prev_h: float = height.sample_mirror(Vector2i(a))
		for k in n:
			var p: Vector2 = a.lerp(b, float(k + 1) / float(n))
			var h: float = height.sample_mirror(Vector2i(p))
			var step: float = seg / float(n)
			var cost: float = 1.0
			var wet: bool = h <= sea_level
			if wet:
				water_px += step
			if by_sea:
				cost = 1.0 if wet else 6.0
			else:
				# Pendiente: cada unidad de altura subida o bajada por píxel encarece el paso.
				var dh: float = absf(h - prev_h) * 255.0 / maxf(step, 0.001)
				cost = 1.0 + clampf(dh * 0.6, 0.0, 3.0)
				# Alta montaña, más lento; el agua (vado, barca) mucho más.
				cost *= 1.0 + smoothstep(0.25, 0.45, h - sea_level) * 0.8
				if wet:
					cost *= 4.0
				# Por un camino se va más deprisa (y la cuesta pesa menos: los caminos buscan el paso).
				cost *= road_factor(p)
				climb += maxf(h - prev_h, 0.0)
			total_px += step
			weighted += step * cost
			prev_h = h
	return {"px": total_px, "weighted": weighted, "water_px": water_px, "climb": climb}

# ---------------------------------------------------------------- guardar y abrir

func _new_map_id() -> String:
	var t: Dictionary = Time.get_datetime_dict_from_system()
	return "%04d%02d%02d-%02d%02d%02d-%03d" % [t["year"], t["month"], t["day"], t["hour"], t["minute"], t["second"], randi() % 1000]

func _remember_map() -> void:
	var cfg := ConfigFile.new()
	cfg.load(CONFIG_PATH)
	cfg.set_value("mapas", "ultimo", map_id)
	cfg.save(CONFIG_PATH)

## Guarda el mapa abierto en user://mapas/<id>/. Las copias de las capas se hacen aquí (rápido) y la compresión y
## la escritura, en otro hilo. [extra]: lo de la vista (estilo, sol…), que pone main.
func save_map(extra: Dictionary = {}) -> void:
	if not world_ready or map_id == "":
		return
	if _saving:
		_save_again = true
		return
	_saving = true
	var dir: String = MAPS_DIR + "/" + map_id
	DirAccess.make_dir_recursive_absolute(dir)
	var meta: Dictionary = _meta_snapshot()
	meta["version"] = SAVE_VERSION
	meta["name"] = map_name
	meta["obra"] = map_obra
	meta["obra_t"] = map_obra_t
	meta["obras"] = map_obras.duplicate()
	meta["size"] = map_size
	meta["seed"] = world_seed
	meta["sea_level"] = sea_level
	meta["stroke_count"] = stroke_count
	meta["hdr"] = height.mirror_format == Image.FORMAT_RH
	meta["saved_at"] = Time.get_unix_time_from_system()
	meta["extra"] = extra
	meta["mode"] = world_mode
	meta["infinite"] = infinite
	meta["origin"] = world_origin
	# Solo se copian (en el hilo principal) las capas que han cambiado desde el último guardado; en un mundo infinito,
	# solo los trozos tocados. Lo demás ya está en disco.
	var inf: bool = infinite
	var all_layers: Array = [["relieve", height], ["biomas", biome], ["reinos", realm], ["regiones", area]]
	var layers: Array = []
	var was_dirty: Array = _layer_dirty.duplicate()
	if not inf:
		for i in 4:
			if bool(_layer_dirty[i]) or not FileAccess.file_exists(dir + "/" + str(all_layers[i][0]) + ".bin"):
				layers.append([all_layers[i][0], (all_layers[i][1] as PaintLayer).mirror.duplicate()])
	_layer_dirty = [false, false, false, false]
	var small: Image = _height_small().duplicate()
	var sl: float = sea_level
	var chunk_regs: Array = []
	if inf:
		chunk_regs = _dirty_chunk_regions(_dirty_chunks)
	var dirty_backup: Dictionary = _dirty_chunks.duplicate()
	_dirty_chunks.clear()
	var task := func() -> bool:
		var ok: bool = true
		var thumb: Image = _thumbnail_from(small, sl)
		if inf and not chunk_regs.is_empty():
			# Mundo infinito: los trozos tocados de la ventana (el resto del mundo ya está en los suyos o lo hace el
			# generador igual).
			ok = _write_chunk_regions(dir, chunk_regs)
		for l in layers:
			var img: Image = l[1]
			var f := FileAccess.open_compressed(dir + "/" + str(l[0]) + ".bin.tmp", FileAccess.WRITE, FileAccess.COMPRESSION_ZSTD)
			if f == null:
				ok = false
				continue
			f.store_32(img.get_width())
			f.store_32(img.get_height())
			f.store_32(img.get_format())
			f.store_buffer(img.get_data())
			f.close()
		var mf := FileAccess.open(dir + "/mundo.bin.tmp", FileAccess.WRITE)
		if mf == null:
			return false
		mf.store_var(meta, false)
		mf.close()
		thumb.save_png(dir + "/miniatura.png")
		if ok:
			# Se cambia de golpe al terminar: un cierre a medias nunca deja el mapa roto.
			var names: Array = ["mundo"]
			for l in layers:
				names.append(str(l[0]))
			for n in names:
				DirAccess.rename_absolute(dir + "/" + n + ".bin.tmp", dir + "/" + n + ".bin")
		return ok
	var result: Array = [false]
	var tid: int = WorkerThreadPool.add_task(func(): result[0] = task.call())
	while not WorkerThreadPool.is_task_completed(tid):
		await get_tree().process_frame
	WorkerThreadPool.wait_for_task_completion(tid)
	var ok: bool = bool(result[0])
	if not ok:
		# Que se vuelva a intentar todo lo que no se pudo escribir.
		for i in 4:
			if bool(was_dirty[i]):
				_layer_dirty[i] = true
		for k in dirty_backup.keys():
			_dirty_chunks[k] = true
	_remember_map()
	_saving = false
	saved.emit(ok)
	if _save_again:
		_save_again = false
		save_map(extra)

## Miniatura de 256 px del relieve (mar azul, tierra en tonos de altura) para la lista de mapas.
func _thumbnail() -> Image:
	return _thumbnail_from(_height_small().duplicate(), sea_level)

## Lo mismo a partir del relieve pequeño (se puede hacer en otro hilo).
static func _thumbnail_from(small: Image, sea_level: float) -> Image:
	small.resize(maxi(small.get_width() * 2, 1), maxi(small.get_height() * 2, 1), Image.INTERPOLATE_BILINEAR)
	var out := Image.create(small.get_width(), small.get_height(), false, Image.FORMAT_RGB8)
	var sea_col := Color(0.24, 0.36, 0.50)
	for y in out.get_height():
		for x in out.get_width():
			var h: float = small.get_pixel(x, y).r
			if h <= sea_level:
				out.set_pixel(x, y, sea_col.lerp(Color(0.40, 0.55, 0.68), clampf((h - sea_level + 0.15) / 0.15, 0.0, 1.0)))
			else:
				var e: float = clampf((h - sea_level) * 2.6, 0.0, 1.0)
				out.set_pixel(x, y, Color(0.93, 0.89, 0.78).lerp(Color(0.55, 0.50, 0.42), e))
	return out

static func _read_layer(path: String) -> Image:
	var f := FileAccess.open_compressed(path, FileAccess.READ, FileAccess.COMPRESSION_ZSTD)
	if f == null:
		return null
	var w: int = f.get_32()
	var h: int = f.get_32()
	var fmt: int = f.get_32()
	var data: PackedByteArray = f.get_buffer(f.get_length())
	f.close()
	return Image.create_from_data(w, h, false, fmt, data)

## Abre un mapa guardado. Devuelve false si no se pudo (y deja el mundo como estaba).
func load_map(id: String) -> bool:
	var dir: String = MAPS_DIR + "/" + id
	var mf := FileAccess.open(dir + "/mundo.bin", FileAccess.READ)
	if mf == null:
		return false
	var meta: Variant = mf.get_var(false)
	mf.close()
	if typeof(meta) != TYPE_DICTIONARY:
		return false
	var m: Dictionary = meta
	var inf: bool = bool(m.get("infinite", false))
	var imgs: Array = []
	if not inf:
		var task := func():
			for n in ["relieve", "biomas", "reinos", "regiones"]:
				imgs.append(_read_layer(dir + "/" + n + ".bin"))
		var tid: int = WorkerThreadPool.add_task(task)
		while not WorkerThreadPool.is_task_completed(tid):
			await get_tree().process_frame
		WorkerThreadPool.wait_for_task_completion(tid)
		for im in imgs:
			if im == null:
				return false
	world_ready = false
	_all_dirty()
	infinite = inf
	world_origin = m.get("origin", Vector2i.ZERO)
	world_mode = int(m.get("mode", 0))
	map_size = m["size"]
	map_id = id
	map_name = str(m.get("name", "Mi mundo"))
	map_obra = str(m.get("obra", ""))
	map_obra_t = str(m.get("obra_t", ""))
	var obs: Array = m.get("obras", [map_obra] if map_obra != "" else [])
	map_obras = obs.duplicate()
	world_seed = int(m.get("seed", 42))
	sea_level = float(m.get("sea_level", 0.5))
	stroke_count = int(m.get("stroke_count", 0))
	var layers: Array[PaintLayer] = [height, biome, realm, area]
	for i in 4:
		var l: PaintLayer = layers[i]
		l.setup(map_size, hdr if i == 0 else false)
		if not inf:
			await l.load_image(imgs[i])
	if inf:
		await _compose_window(dir, [], Vector2i.ZERO, {})
	undo_stack.clear()
	redo_stack.clear()
	_set_meta(m)
	loaded_extra = m.get("extra", {})
	world_ready = true
	_remember_map()
	layers_ready.emit(map_size)
	_emit_history()
	_compute_stats()
	_compute_centroids()
	_compute_area_shapes()
	layer_changed.emit("height")
	layer_changed.emit("biome")
	map_loaded.emit()
	return true

## Lo que la vista guardó con el mapa (estilo, sol…), para que main lo aplique al abrirlo.
var loaded_extra: Dictionary = {}
signal map_loaded()

## Los mapas guardados, el más reciente primero: [{id, name, size, saved_at}].
static func list_maps() -> Array:
	var out: Array = []
	var d := DirAccess.open(MAPS_DIR)
	if d == null:
		return out
	for id in d.get_directories():
		var f := FileAccess.open(MAPS_DIR + "/" + id + "/mundo.bin", FileAccess.READ)
		if f == null:
			continue
		var meta: Variant = f.get_var(false)
		f.close()
		if typeof(meta) != TYPE_DICTIONARY:
			continue
		var m: Dictionary = meta
		out.append({"id": id, "name": str(m.get("name", id)), "size": m.get("size", Vector2i.ZERO), "saved_at": float(m.get("saved_at", 0.0)),
			"obra": str(m.get("obra", "")), "obra_t": str(m.get("obra_t", "")), "infinite": bool(m.get("infinite", false)),
			"obras": m.get("obras", [])})
	out.sort_custom(func(a, b): return float(a["saved_at"]) > float(b["saved_at"]))
	return out

static func delete_map_files(id: String) -> void:
	var dir: String = MAPS_DIR + "/" + id
	var d := DirAccess.open(dir)
	if d == null:
		return
	for f in d.get_files():
		d.remove(f)
	DirAccess.remove_absolute(dir)

## Copia del mapa abierto con otro id y nombre (y pasa a ser el abierto).
func duplicate_map(new_name: String, extra: Dictionary) -> void:
	var old_dir: String = MAPS_DIR + "/" + map_id
	map_id = _new_map_id()
	map_name = new_name
	_layer_dirty = [true, true, true, true]
	if infinite:
		# Los trozos de fuera de la ventana también.
		var new_dir: String = MAPS_DIR + "/" + map_id
		DirAccess.make_dir_recursive_absolute(new_dir)
		for f in DirAccess.get_files_at(old_dir):
			if f.begins_with("trozo_"):
				DirAccess.copy_absolute(old_dir + "/" + f, new_dir + "/" + f)
	await save_map(extra)

# ---------------------------------------------------------------- mundo infinito
#
# Un mundo infinito es una ventana de INF_WINDOW px (la capa de siempre, con todos sus pinceles, el 2D y el 3D) sobre
# un mundo sin bordes guardado en trozos de CHUNK px (un archivo por trozo y capa en la carpeta del mapa). Al
# acercarse al borde, la ventana se recoloca: escribe sus trozos, se desplaza trozos enteros y compone la nueva con lo
# que ya tenía, los trozos guardados y, donde no hay nada, el generador con las coordenadas del mundo. Lugares,
# caminos y rutas se guardan en uv de la ventana y se desplazan con ella.

const CHUNK := 1024
const INF_WINDOW := 4096
const LAYER_FILES := ["relieve", "biomas", "reinos", "regiones"]
var infinite: bool = false
## Píxel del mundo de la esquina de arriba a la izquierda de la ventana (múltiplo de CHUNK).
var world_origin: Vector2i = Vector2i.ZERO
signal window_moved(shift_px: Vector2i)

static func _chunk_file(dir: String, li: int, c: Vector2i) -> String:
	return "%s/trozo_%s_%d_%d.bin" % [dir, LAYER_FILES[li], c.x, c.y]

func _init_window_params() -> void:
	mat_init.set_shader_parameter("infinite", infinite)
	mat_init.set_shader_parameter("world_origin", Vector2(world_origin))
	mat_init.set_shader_parameter("window_size", Vector2(map_size))

## Escribe los trozos de la ventana. Las capas de identificadores vacías no se guardan (y se borra su archivo si
## lo había). Seguro en otro hilo (trabaja con copias).
static func _write_chunks(dir: String, imgs: Array, origin: Vector2i, wsize: Vector2i, only: Dictionary = {}) -> bool:
	var ok: bool = true
	var n: Vector2i = wsize / CHUNK
	var oc: Vector2i = Vector2i(floori(float(origin.x) / CHUNK), floori(float(origin.y) / CHUNK))
	for li in 4:
		var img: Image = imgs[li]
		for cy in n.y:
			for cx in n.x:
				if not only.is_empty() and not only.has(oc + Vector2i(cx, cy)):
					continue
				var reg: Image = img.get_region(Rect2i(cx * CHUNK, cy * CHUNK, CHUNK, CHUNK))
				var path: String = _chunk_file(dir, li, oc + Vector2i(cx, cy))
				var data: PackedByteArray = reg.get_data()
				if li > 0 and data.count(0) == data.size():
					if FileAccess.file_exists(path):
						DirAccess.remove_absolute(path)
					continue
				var f := FileAccess.open_compressed(path + ".tmp", FileAccess.WRITE, FileAccess.COMPRESSION_ZSTD)
				if f == null:
					ok = false
					continue
				f.store_32(reg.get_width())
				f.store_32(reg.get_height())
				f.store_32(reg.get_format())
				f.store_buffer(data)
				f.close()
				DirAccess.rename_absolute(path + ".tmp", path)
	return ok

## Los trozos tocados de la ventana como imágenes sueltas [capa, trozo del mundo, imagen] (copias pequeñas).
func _dirty_chunk_regions(dirty: Dictionary) -> Array:
	var out: Array = []
	var layers: Array[PaintLayer] = [height, biome, realm, area]
	var oc: Vector2i = world_origin / CHUNK
	var n: Vector2i = map_size / CHUNK
	for wc in dirty.keys():
		var c: Vector2i = Vector2i(wc) - oc
		if c.x < 0 or c.y < 0 or c.x >= n.x or c.y >= n.y:
			continue
		for li in 4:
			out.append([li, Vector2i(wc), layers[li].mirror.get_region(Rect2i(c * CHUNK, Vector2i(CHUNK, CHUNK)))])
	return out

static func _write_chunk_regions(dir: String, regs: Array) -> bool:
	var ok: bool = true
	for e in regs:
		var li: int = e[0]
		var wc: Vector2i = e[1]
		var reg: Image = e[2]
		var path: String = _chunk_file(dir, li, wc)
		var data: PackedByteArray = reg.get_data()
		if li > 0 and data.count(0) == data.size():
			if FileAccess.file_exists(path):
				DirAccess.remove_absolute(path)
			continue
		var f := FileAccess.open_compressed(path + ".tmp", FileAccess.WRITE, FileAccess.COMPRESSION_ZSTD)
		if f == null:
			ok = false
			continue
		f.store_32(reg.get_width())
		f.store_32(reg.get_height())
		f.store_32(reg.get_format())
		f.store_buffer(data)
		f.close()
		DirAccess.rename_absolute(path + ".tmp", path)
	return ok

## Compone la ventana en world_origin: el generador (con las coordenadas del mundo), encima lo que se conserva de la
## ventana anterior ([keep] desplazado [keep_offset]) y los trozos guardados que no estaban en ella.
func _compose_window(dir: String, keep: Array, keep_offset: Vector2i, keep_chunks: Dictionary) -> void:
	var short_side: float = float(mini(map_size.x, map_size.y))
	mat_init.set_shader_parameter("seed", float(world_seed))
	mat_init.set_shader_parameter("mode", world_mode)
	mat_init.set_shader_parameter("aspect_scale", Vector2(map_size) / short_side)
	_init_window_params()
	await height.fill(mat_init)
	var gen: Image = await height.read_region(Rect2i(Vector2i.ZERO, map_size))
	var layers: Array[PaintLayer] = [height, biome, realm, area]
	var imgs: Array = [gen]
	for li in range(1, 4):
		var im := Image.create(map_size.x, map_size.y, false, layers[li].mirror_format)
		im.fill(Color.BLACK)
		imgs.append(im)
	if not keep.is_empty():
		for li in 4:
			var k: Image = keep[li]
			if k.get_format() != (imgs[li] as Image).get_format():
				k.convert((imgs[li] as Image).get_format())
			(imgs[li] as Image).blit_rect(k, Rect2i(Vector2i.ZERO, k.get_size()), keep_offset)
	var n: Vector2i = map_size / CHUNK
	var oc: Vector2i = world_origin / CHUNK
	var loaded: Array = []
	var task := func():
		for cy in n.y:
			for cx in n.x:
				var wc: Vector2i = oc + Vector2i(cx, cy)
				if keep_chunks.has(wc):
					continue
				for li in 4:
					var path: String = _chunk_file(dir, li, wc)
					if FileAccess.file_exists(path):
						var ci: Image = _read_layer(path)
						if ci != null:
							loaded.append([li, Vector2i(cx, cy), ci])
	var tid: int = WorkerThreadPool.add_task(task)
	while not WorkerThreadPool.is_task_completed(tid):
		await get_tree().process_frame
	WorkerThreadPool.wait_for_task_completion(tid)
	for e in loaded:
		var li: int = e[0]
		var cpos: Vector2i = e[1]
		var ci: Image = e[2]
		if ci.get_format() != (imgs[li] as Image).get_format():
			ci.convert((imgs[li] as Image).get_format())
		(imgs[li] as Image).blit_rect(ci, Rect2i(Vector2i.ZERO, ci.get_size()), cpos * CHUNK)
	for li in 4:
		await layers[li].load_image(imgs[li])

## Desplaza la ventana [dc] trozos (con signo). El historial de deshacer empieza de nuevo.
func recenter(dc: Vector2i) -> void:
	if not infinite or dc == Vector2i.ZERO or not world_ready:
		return
	world_ready = false
	var dir: String = MAPS_DIR + "/" + map_id
	DirAccess.make_dir_recursive_absolute(dir)
	# El relieve se copia (al componer se lee de la GPU encima de su espejo); las demás capas no cambian hasta el final.
	var old: Array = [height.mirror.duplicate(), biome.mirror, realm.mirror, area.mirror]
	var regs: Array = _dirty_chunk_regions(_dirty_chunks)
	_dirty_chunks.clear()
	if not regs.is_empty():
		var tid: int = WorkerThreadPool.add_task(func(): _write_chunk_regions(dir, regs))
		while not WorkerThreadPool.is_task_completed(tid):
			await get_tree().process_frame
		WorkerThreadPool.wait_for_task_completion(tid)
	var shift_px: Vector2i = dc * CHUNK
	var old_oc: Vector2i = world_origin / CHUNK
	var n: Vector2i = map_size / CHUNK
	world_origin += shift_px
	var keep_chunks: Dictionary = {}
	var new_oc: Vector2i = world_origin / CHUNK
	for cy in n.y:
		for cx in n.x:
			var wc: Vector2i = new_oc + Vector2i(cx, cy)
			if wc.x >= old_oc.x and wc.y >= old_oc.y and wc.x < old_oc.x + n.x and wc.y < old_oc.y + n.y:
				keep_chunks[wc] = true
	await _compose_window(dir, old, -shift_px, keep_chunks)
	_stats_small = null
	_stats_rects.clear()
	var d: Vector2 = Vector2(shift_px) / Vector2(map_size)
	for p in places:
		p["uv"] = Vector2(p["uv"]) - d
	for r in routes + roads:
		var pts: Array = r["points"]
		for i in pts.size():
			pts[i] = Vector2(pts[i]) - d
	_road_grid_dirty = true
	undo_stack.clear()
	redo_stack.clear()
	world_ready = true
	_emit_history()
	places_changed.emit()
	roads_changed.emit()
	realms_changed.emit()
	_compute_stats()
	_compute_centroids()
	_compute_area_shapes()
	layer_changed.emit("height")
	layer_changed.emit("biome")
	window_moved.emit(shift_px)

# ---------------------------------------------------------------- regiones con nombre

func area_by_id(id: int) -> Dictionary:
	for a in areas:
		if int(a["id"]) == id:
			return a
	return {}

func add_area(kind: String, name: String = "") -> int:
	var used: Dictionary = {}
	for a in areas:
		used[int(a["id"])] = true
	var id: int = 1
	while used.has(id) and id < 255:
		id += 1
	if used.has(id):
		return -1
	var before: Dictionary = _meta_snapshot()
	var t: Array = Catalogo.tipo_region(kind)
	var nm: String = name if name.strip_edges() != "" else Nombres.region(kind, rng)
	areas.append({"id": id, "name": nm, "kind": kind, "biome": int(t[3])})
	_commit_meta(before)
	return id

func update_area(id: int, key: String, value: Variant) -> void:
	var a: Dictionary = area_by_id(id)
	if a.is_empty() or (typeof(a[key]) == typeof(value) and a[key] == value):
		return
	var before: Dictionary = _meta_snapshot()
	a[key] = value
	_commit_meta(before)

## Borra la región y su contorno (el bioma que pintó se queda). Un solo paso del historial.
func delete_area(id: int) -> void:
	var a: Dictionary = area_by_id(id)
	if a.is_empty():
		return
	var before_meta: Dictionary = _meta_snapshot()
	var before_img: Image = area.mirror.duplicate()
	var m := _mat("res://shaders/id_remap.gdshader")
	m.set_shader_parameter("snap", area.snapshot_texture())
	m.set_shader_parameter("from_id", id)
	m.set_shader_parameter("to_id", 0)
	var after_img: Image = await area.apply_full(m)
	areas.erase(a)
	var full := Rect2i(Vector2i.ZERO, map_size)
	_push({"t": "multi", "items": [
		{"t": "paint", "layer": "area", "rect": full, "before": before_img, "after": after_img},
		{"t": "meta", "before": before_meta, "after": _meta_snapshot()},
	]})
	areas_changed.emit()
	_compute_area_shapes()

# ---------------------------------------------------------------- lugares

func place_by_id(id: int) -> Dictionary:
	for p in places:
		if int(p["id"]) == id:
			return p
	return {}

func add_place(kind: String, uv: Vector2) -> int:
	var before: Dictionary = _meta_snapshot()
	var id: int = _next_place_id
	_next_place_id += 1
	var np: Dictionary = {"id": id, "kind": kind, "name": Nombres.lugar(kind, rng), "uv": uv}
	if Asentamiento.has_size(kind):
		np["size"] = int(Asentamiento.DEFAULT_SIZE[kind])
	places.append(np)
	_commit_meta(before)
	return id

func update_place(id: int, key: String, value: Variant) -> void:
	var p: Dictionary = place_by_id(id)
	if p.is_empty() or (p.has(key) and typeof(p[key]) == typeof(value) and p[key] == value):
		return
	var before: Dictionary = _meta_snapshot()
	p[key] = value
	_commit_meta(before)

## Mover arrastrando: se cambia sin historial mientras se arrastra y se guarda un paso al soltar.
func move_place_live(id: int, uv: Vector2) -> void:
	var p: Dictionary = place_by_id(id)
	if not p.is_empty():
		p["uv"] = uv
		places_changed.emit()

func commit_place_move(before: Dictionary) -> void:
	_commit_meta(before)

func meta_snapshot() -> Dictionary:
	return _meta_snapshot()

func delete_place(id: int) -> void:
	var p: Dictionary = place_by_id(id)
	if p.is_empty():
		return
	var before: Dictionary = _meta_snapshot()
	places.erase(p)
	_commit_meta(before)

# ---------------------------------------------------------------- cifras

func set_sea_level(level: float) -> void:
	sea_level = level
	_compute_stats()

func _small(layer: PaintLayer, interp: int) -> Image:
	var side_w: int = STATS_SIDE if map_size.x >= map_size.y else int(round(STATS_SIDE * float(map_size.x) / float(map_size.y)))
	var side_h: int = STATS_SIDE if map_size.y >= map_size.x else int(round(STATS_SIDE * float(map_size.y) / float(map_size.x)))
	var small: Image = layer.mirror.duplicate()
	if small.get_format() != Image.FORMAT_R8:
		small.convert(Image.FORMAT_RF)
	small.resize(maxi(side_w, 1), maxi(side_h, 1), interp)
	if small.get_format() != Image.FORMAT_R8:
		small.convert(Image.FORMAT_R8)
	return small

## El relieve en pequeño (STATS_SIDE px), para las cifras y la miniatura. Se mantiene al día por zonas: antes se
## reducía el relieve entero tras cada trazo y cada guardado (100 ms de tirón en un mapa de 4.096).
var _stats_small: Image
var _stats_rects: Array = []
## Qué capas han cambiado desde el último guardado (solo esas se copian y se escriben).
var _layer_dirty: Array = [true, true, true, true]

func _all_dirty() -> void:
	_layer_dirty = [true, true, true, true]
	_stats_small = null
	_stats_rects.clear()

func _height_small() -> Image:
	if height == null or height.mirror == null:
		return null
	var side_w: int = STATS_SIDE if map_size.x >= map_size.y else int(round(STATS_SIDE * float(map_size.x) / float(map_size.y)))
	var side_h: int = STATS_SIDE if map_size.y >= map_size.x else int(round(STATS_SIDE * float(map_size.y) / float(map_size.x)))
	side_w = maxi(side_w, 1)
	side_h = maxi(side_h, 1)
	var full: bool = _stats_small == null or _stats_small.get_size() != Vector2i(side_w, side_h)
	var area_px: int = 0
	for r in _stats_rects:
		area_px += (r as Rect2i).get_area()
	if full or area_px > map_size.x * map_size.y / 3:
		_stats_small = _small(height, Image.INTERPOLATE_BILINEAR)
		_stats_rects.clear()
		return _stats_small
	var fx: float = float(map_size.x) / float(side_w)
	var fy: float = float(map_size.y) / float(side_h)
	for r0 in _stats_rects:
		var r: Rect2i = r0
		var sx0: int = clampi(int(floor(r.position.x / fx)), 0, side_w - 1)
		var sy0: int = clampi(int(floor(r.position.y / fy)), 0, side_h - 1)
		var sx1: int = clampi(int(ceil(r.end.x / fx)), sx0 + 1, side_w)
		var sy1: int = clampi(int(ceil(r.end.y / fy)), sy0 + 1, side_h)
		var src := Rect2i(int(sx0 * fx), int(sy0 * fy), 0, 0)
		src.end = Vector2i(mini(int(ceil(sx1 * fx)), map_size.x), mini(int(ceil(sy1 * fy)), map_size.y))
		if src.size.x <= 0 or src.size.y <= 0:
			continue
		var reg: Image = height.mirror.get_region(src)
		if reg.get_format() != Image.FORMAT_R8:
			reg.convert(Image.FORMAT_RF)
		reg.resize(sx1 - sx0, sy1 - sy0, Image.INTERPOLATE_BILINEAR)
		if reg.get_format() != Image.FORMAT_R8:
			reg.convert(Image.FORMAT_R8)
		_stats_small.blit_rect(reg, Rect2i(Vector2i.ZERO, reg.get_size()), Vector2i(sx0, sy0))
	_stats_rects.clear()
	return _stats_small

func _compute_stats() -> void:
	if height == null or height.mirror == null:
		return
	var sm: Image = _height_small()
	if sm == null:
		return
	var data: PackedByteArray = sm.get_data()
	var threshold: int = int(round(sea_level * 255.0))
	var land: int = 0
	var top: int = 0
	for v in data:
		if v > threshold:
			land += 1
		if v > top:
			top = v
	stats_changed.emit(float(land) / float(maxi(data.size(), 1)), float(top) / 255.0)

## Centro, orientación (eje mayor) y largo de cada región, para rotular su nombre a lo largo.
func _compute_area_shapes() -> void:
	var small: Image = _small(area, Image.INTERPOLATE_NEAREST)
	var w: int = small.get_width()
	var h: int = small.get_height()
	var data: PackedByteArray = small.get_data()
	var acc: Dictionary = {}
	for i in data.size():
		var id: int = data[i]
		if id == 0:
			continue
		var x: float = float(i % w) + 0.5
		var y: float = float(i / w) + 0.5
		if not acc.has(id):
			acc[id] = PackedFloat64Array([0, 0, 0, 0, 0, 0])
		var a: PackedFloat64Array = acc[id]
		a[0] += 1.0
		a[1] += x
		a[2] += y
		a[3] += x * x
		a[4] += y * y
		a[5] += x * y
		acc[id] = a
	var out: Dictionary = {}
	var long_side: float = float(maxi(w, h))
	for id in acc.keys():
		var a: PackedFloat64Array = acc[id]
		var n: float = a[0]
		var mx: float = a[1] / n
		var my: float = a[2] / n
		var cxx: float = a[3] / n - mx * mx
		var cyy: float = a[4] / n - my * my
		var cxy: float = a[5] / n - mx * my
		var angle: float = 0.5 * atan2(2.0 * cxy, cxx - cyy)
		var tr: float = cxx + cyy
		var det: float = cxx * cyy - cxy * cxy
		var l1: float = tr * 0.5 + sqrt(maxf(tr * tr * 0.25 - det, 0.0))
		# Largo de una banda uniforme con esa varianza: sqrt(12 λ).
		var length: float = sqrt(12.0 * maxf(l1, 0.0)) / long_side
		# Texto siempre legible (nunca boca abajo).
		if angle > PI * 0.5:
			angle -= PI
		elif angle < -PI * 0.5:
			angle += PI
		out[id] = {"uv": Vector2(mx / float(w), my / float(h)), "angle": angle, "length": length}
	area_shapes_changed.emit(out)

## Centro y extensión de cada reino. Se cuenta sobre el territorio reducido a 512 px (precisión de una parte en
## 262.144 del mapa) en otro hilo; el resultado llega con realm_centroids_changed.
var _centroid_gen: int = 0

func _compute_centroids() -> void:
	if realm == null or realm.mirror == null:
		return
	var side: int = 512
	var w: int = side if map_size.x >= map_size.y else maxi(int(round(side * float(map_size.x) / float(map_size.y))), 1)
	var h: int = side if map_size.y >= map_size.x else maxi(int(round(side * float(map_size.y) / float(map_size.x))), 1)
	var small: Image = realm.mirror.duplicate()
	small.resize(w, h, Image.INTERPOLATE_NEAREST)
	_centroid_gen += 1
	var gen: int = _centroid_gen
	WorkerThreadPool.add_task(func():
		var data: PackedByteArray = small.get_data()
		var acc: Dictionary = {}
		for i in data.size():
			var id: int = data[i]
			if id == 0:
				continue
			if not acc.has(id):
				acc[id] = Vector3.ZERO
			var a: Vector3 = acc[id]
			acc[id] = a + Vector3(float(i % w) + 0.5, float(i / w) + 0.5, 1.0)
		var out: Dictionary = {}
		var total: float = float(maxi(data.size(), 1))
		for id in acc.keys():
			var a: Vector3 = acc[id]
			out[id] = {"uv": Vector2(a.x / a.z / float(w), a.y / a.z / float(h)), "area": a.z / total}
		_centroids_done.call_deferred(gen, out)
	)

func _centroids_done(gen: int, out: Dictionary) -> void:
	if gen != _centroid_gen:
		return
	realm_centroids_changed.emit(out)
