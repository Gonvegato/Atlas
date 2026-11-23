extends Node

## El mundo: tres capas que se pintan en la GPU (relieve, biomas y reinos), los reinos y los lugares, y un
## historial único para todo (deshacer un trazo de bioma, un reino nuevo o un pueblo movido es lo mismo).

signal layers_ready(size: Vector2i)
signal history_changed(can_undo: bool, can_redo: bool)
## Fracción de tierra (0..1) y altura máxima (0..1).
signal stats_changed(land_frac: float, max_height: float)
signal realms_changed()
signal places_changed()
## id de reino → {"uv": Vector2 (centro), "area": float (fracción del mapa)}
signal realm_centroids_changed(centroids: Dictionary)
signal areas_changed()
## id de región → {"uv": Vector2, "angle": float (rad), "length": float (fracción del lado mayor)}
signal area_shapes_changed(shapes: Dictionary)
## Una capa ha cambiado (trazo, deshacer, ampliar…): "height", "biome", "realm" o "area".
signal layer_changed(layer_name: String)

const MAX_SIDE: int = 4096
const MAX_HISTORY: int = 40
const MAX_HISTORY_BYTES: int = 96 * 1024 * 1024
const STATS_SIDE: int = 128
const CONFIG_PATH: String = "user://atlas.cfg"

const SHAPE_MODES = {"smooth": 0, "cliff": 1, "plateau": 2, "valley": 3, "canyon": 4}

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
var world_ready: bool = false

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
	# Nada de leer texturas durante _ready(): el mundo se crea y se lee unos fotogramas después.
	await get_tree().create_timer(0.25).timeout
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
func create_world(size: Vector2i, seed: int, mode: int) -> void:
	world_ready = false
	map_size = Vector2i(clampi(size.x, 256, MAX_SIDE), clampi(size.y, 256, MAX_SIDE))
	world_seed = seed
	world_mode = mode
	height.setup(map_size, hdr)
	biome.setup(map_size, false)
	realm.setup(map_size, false)
	area.setup(map_size, false)
	var short_side: float = float(mini(map_size.x, map_size.y))
	mat_init.set_shader_parameter("seed", float(seed))
	mat_init.set_shader_parameter("mode", mode)
	mat_init.set_shader_parameter("aspect_scale", Vector2(map_size) / short_side)
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
	_next_place_id = 1
	stroke_count = 0
	world_ready = true
	layers_ready.emit(map_size)
	_emit_history()
	realms_changed.emit()
	places_changed.emit()
	_compute_stats()
	realm_centroids_changed.emit({})
	areas_changed.emit()
	area_shapes_changed.emit({})
	layer_changed.emit("height")
	layer_changed.emit("biome")

## Amplía el lienzo hacia un lado ("N", "S", "E", "O") en [frac] del tamaño actual. Lo nuevo es mar profundo.
## No se puede deshacer (el historial se vacía): cambian los tamaños de todo.
func extend(dir: String, frac: float = 0.5) -> bool:
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
	for p in places:
		var uv: Vector2 = p["uv"]
		p["uv"] = (uv * Vector2(old_size) + Vector2(offset)) / Vector2(new_size)
	undo_stack.clear()
	redo_stack.clear()
	world_ready = true
	layers_ready.emit(map_size)
	_emit_history()
	places_changed.emit()
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
	_stroke_dirty = Rect2i()
	_stroke_has = false
	_last_px = Vector2(-1, -1)
	_path.clear()
	if SHAPE_MODES.has(tool):
		_snap_tex = height.snapshot_texture()
		_target_h = height.sample_mirror(Vector2i(start_uv * Vector2(map_size)))

func paint_uv(uv: Vector2, radius_px: float, strength: float, steps: float = 10.0) -> void:
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
	if base == _mat_id:
		m.set_shader_parameter("value", float(_stroke_value) / 255.0)
		# Las demás capas del trazo (el bioma de una región), con su propio valor.
		for i in range(1, _stroke_targets.size()):
			var t: Array = _stroke_targets[i]
			var m2: ShaderMaterial = base.duplicate() as ShaderMaterial
			for k in ["rect_min", "rect_size", "p0", "p1", "brush_radius"]:
				m2.set_shader_parameter(k, m.get_shader_parameter(k))
			m2.set_shader_parameter("value", float(int(t[1])) / 255.0)
			(t[0] as PaintLayer).dab(m2, Rect2(min_pt, rect_sz))
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

func _apply(e: Dictionary, forward: bool) -> void:
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
	return {"realms": realms.duplicate(true), "places": places.duplicate(true), "areas": areas.duplicate(true), "next_place": _next_place_id}

func _set_meta(snap: Dictionary) -> void:
	var rs: Array = snap["realms"]
	var ps: Array = snap["places"]
	realms = rs.duplicate(true)
	places = ps.duplicate(true)
	var ars: Array = snap.get("areas", [])
	areas = ars.duplicate(true)
	_next_place_id = int(snap["next_place"])
	realms_changed.emit()
	places_changed.emit()
	areas_changed.emit()

func _commit_meta(before: Dictionary) -> void:
	_push({"t": "meta", "before": before, "after": _meta_snapshot()})
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
	realms.append({"id": id, "name": Nombres.reino(rng), "kind": "REINO", "color": col})
	_commit_meta(before)
	return id

func update_realm(id: int, key: String, value: Variant) -> void:
	var r: Dictionary = realm_by_id(id)
	if r.is_empty() or (typeof(r[key]) == typeof(value) and r[key] == value):
		return
	var before: Dictionary = _meta_snapshot()
	r[key] = value
	_commit_meta(before)

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
	var full := Rect2i(Vector2i.ZERO, map_size)
	_push({"t": "multi", "items": [
		{"t": "paint", "layer": "realm", "rect": full, "before": before_img, "after": after_img},
		{"t": "meta", "before": before_meta, "after": _meta_snapshot()},
	]})
	realms_changed.emit()
	_compute_centroids()

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
	places.append({"id": id, "kind": kind, "name": Nombres.lugar(kind, rng), "uv": uv})
	_commit_meta(before)
	return id

func update_place(id: int, key: String, value: Variant) -> void:
	var p: Dictionary = place_by_id(id)
	if p.is_empty() or (typeof(p[key]) == typeof(value) and p[key] == value):
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

func _compute_stats() -> void:
	if height == null or height.mirror == null:
		return
	var data: PackedByteArray = _small(height, Image.INTERPOLATE_BILINEAR).get_data()
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

func _compute_centroids() -> void:
	var small: Image = _small(realm, Image.INTERPOLATE_NEAREST)
	var w: int = small.get_width()
	var data: PackedByteArray = small.get_data()
	var acc: Dictionary = {}
	for i in data.size():
		var id: int = data[i]
		if id == 0:
			continue
		var x: int = i % w
		var y: int = i / w
		if not acc.has(id):
			acc[id] = Vector3.ZERO
		var a: Vector3 = acc[id]
		acc[id] = a + Vector3(x + 0.5, y + 0.5, 1.0)
	var out: Dictionary = {}
	var total: float = float(maxi(data.size(), 1))
	var h: int = small.get_height()
	for id in acc.keys():
		var a: Vector3 = acc[id]
		out[id] = {"uv": Vector2(a.x / a.z / float(w), a.y / a.z / float(h)), "area": a.z / total}
	realm_centroids_changed.emit(out)
