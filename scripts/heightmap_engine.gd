extends Node

signal heightmap_ready(tex: Texture2D)
## Se puede deshacer / rehacer (para atenuar los enlaces de la cabecera).
signal history_changed(can_undo: bool, can_redo: bool)
## Cifras del mundo: fracción de tierra (0..1) y altura máxima (0..1 del heightmap).
signal stats_changed(land_frac: float, max_height: float)

@export var resolution: int = 1024
@export var max_undo_steps: int = 12
## Lado de la copia reducida con la que se cuentan tierra y mar (128² = 16.384 muestras).
const STATS_SIDE: int = 128

var viewport: SubViewport
var init_rect: ColorRect
var brush_rect: ColorRect
var snapshot_rect: TextureRect

var mat_init: ShaderMaterial
var mat_add: ShaderMaterial
var mat_sub: ShaderMaterial

var undo_stack: Array[Image] = []
var redo_stack: Array[Image] = []
var last_px: Vector2 = Vector2(-1, -1)
var has_initial_snapshot: bool = false
var sea_level: float = 0.5
var world_seed: int = 42
var stroke_count: int = 0
var _last_stats_img: Image = null

func _ready() -> void:
	_setup_viewport()
	await get_tree().process_frame
	await get_tree().process_frame
	init_rect.visible = false
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER
	heightmap_ready.emit(viewport.get_texture())

func _setup_viewport() -> void:
	viewport = SubViewport.new()
	viewport.size = Vector2i(resolution, resolution)
	viewport.disable_3d = true
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_ALWAYS
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	add_child(viewport)

	mat_init = ShaderMaterial.new()
	mat_init.shader = load("res://shaders/height_init.gdshader")
	mat_init.set_shader_parameter("seed", float(world_seed))

	mat_add = ShaderMaterial.new()
	mat_add.shader = load("res://shaders/height_brush_add.gdshader")
	mat_add.set_shader_parameter("map_size", Vector2(resolution, resolution))

	mat_sub = ShaderMaterial.new()
	mat_sub.shader = load("res://shaders/height_brush_sub.gdshader")

	var full_size := Vector2(resolution, resolution)

	snapshot_rect = TextureRect.new()
	snapshot_rect.position = Vector2.ZERO
	snapshot_rect.size = full_size
	snapshot_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	snapshot_rect.visible = false
	viewport.add_child(snapshot_rect)

	init_rect = ColorRect.new()
	init_rect.position = Vector2.ZERO
	init_rect.size = full_size
	init_rect.material = mat_init
	viewport.add_child(init_rect)

	brush_rect = ColorRect.new()
	brush_rect.visible = false
	viewport.add_child(brush_rect)

func get_height_texture() -> ViewportTexture:
	return viewport.get_texture()

func regenerate_world(new_seed: int) -> void:
	world_seed = new_seed
	mat_init.set_shader_parameter("seed", float(new_seed))
	brush_rect.visible = false
	snapshot_rect.visible = false
	init_rect.visible = true
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_ALWAYS
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	await get_tree().process_frame
	init_rect.visible = false
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER
	undo_stack.clear()
	redo_stack.clear()
	has_initial_snapshot = false
	stroke_count = 0
	history_changed.emit(false, false)
	# Un fotograma más para que el render del mundo nuevo haya terminado antes de leerlo.
	await get_tree().process_frame
	refresh_stats()

func begin_stroke() -> void:
	if not has_initial_snapshot:
		push_undo_snapshot()
		has_initial_snapshot = true
	last_px = Vector2(-1, -1)

func end_stroke() -> void:
	brush_rect.visible = false
	var painted: bool = last_px.x >= 0.0
	last_px = Vector2(-1, -1)
	if not painted:
		return
	stroke_count += 1
	redo_stack.clear()
	push_undo_snapshot()

func paint_uv(uv: Vector2, tool: String, radius_px: float, strength: float) -> void:
	if uv.x < 0.0 or uv.x > 1.0 or uv.y < 0.0 or uv.y > 1.0:
		return

	var curr_px := uv * float(resolution)
	var prev_px := curr_px if last_px.x < 0.0 else last_px

	var min_pt := Vector2(minf(prev_px.x, curr_px.x), minf(prev_px.y, curr_px.y)) - Vector2(radius_px + 2.0, radius_px + 2.0)
	var max_pt := Vector2(maxf(prev_px.x, curr_px.x), maxf(prev_px.y, curr_px.y)) + Vector2(radius_px + 2.0, radius_px + 2.0)
	var rect_sz := max_pt - min_pt

	var is_sub: bool = (tool == "sea" or tool == "river")
	var mat: ShaderMaterial = mat_sub if is_sub else mat_add
	mat.set_shader_parameter("rect_min", min_pt)
	mat.set_shader_parameter("rect_size", rect_sz)
	mat.set_shader_parameter("p0", prev_px)
	mat.set_shader_parameter("p1", curr_px)
	mat.set_shader_parameter("brush_radius", radius_px)
	mat.set_shader_parameter("strength", strength)
	mat.set_shader_parameter("brush_mode", 1 if (tool == "mountain" or tool == "river") else 0)

	brush_rect.material = mat
	brush_rect.position = min_pt
	brush_rect.size = rect_sz
	brush_rect.visible = true
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	last_px = curr_px

func push_undo_snapshot() -> void:
	var tex := viewport.get_texture()
	if tex == null:
		return
	var img := tex.get_image()
	if img == null:
		return
	undo_stack.append(img)
	if undo_stack.size() > max_undo_steps:
		undo_stack.pop_front()
	_emit_history()
	_compute_stats(img)

func undo() -> void:
	if undo_stack.size() <= 1:
		return
	redo_stack.append(undo_stack.pop_back())
	stroke_count = maxi(stroke_count - 1, 0)
	_restore(undo_stack.back())

func redo() -> void:
	if redo_stack.is_empty():
		return
	var img: Image = redo_stack.pop_back()
	undo_stack.append(img)
	stroke_count += 1
	_restore(img)

func _restore(img: Image) -> void:
	snapshot_rect.texture = ImageTexture.create_from_image(img)
	brush_rect.visible = false
	snapshot_rect.visible = true
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	_emit_history()
	_compute_stats(img)
	await get_tree().process_frame
	snapshot_rect.visible = false

func _emit_history() -> void:
	history_changed.emit(undo_stack.size() > 1, not redo_stack.is_empty())

func set_sea_level(level: float) -> void:
	sea_level = level
	if _last_stats_img != null:
		_compute_stats(null)

## Lee el heightmap ya pintado. No llamar desde _ready(): solo tras un trazo o con el mundo ya en pantalla.
func refresh_stats() -> void:
	var tex := viewport.get_texture()
	if tex == null:
		return
	var img := tex.get_image()
	if img != null:
		_compute_stats(img)

func _compute_stats(img: Image) -> void:
	if img != null:
		var small: Image = img.duplicate()
		small.convert(Image.FORMAT_R8)
		small.resize(STATS_SIDE, STATS_SIDE, Image.INTERPOLATE_BILINEAR)
		_last_stats_img = small
	if _last_stats_img == null:
		return
	var data: PackedByteArray = _last_stats_img.get_data()
	var threshold: int = int(round(sea_level * 255.0))
	var land: int = 0
	var top: int = 0
	for v in data:
		if v > threshold:
			land += 1
		if v > top:
			top = v
	var total: int = maxi(data.size(), 1)
	stats_changed.emit(float(land) / float(total), float(top) / 255.0)
