extends Control

## ATLAS. — la pantalla principal, con la cara «C» de la familia: cabecera de papel con el nombre en grotesca y su
## punto del acento, banda de tinta con la herramienta elegida en un bloque del acento, la mesa con el mapa y, a la
## derecha, el panel con las cifras en bloques de borde grueso.

const HEADER_H: int = 84
const TABS_H: int = 52
const PANEL_W: int = 332
const MAP_MAT: float = 14.0

const MapOverlay = preload("res://scripts/ui/map_overlay.gd")

@onready var engine: Node = $HeightmapEngine
@onready var input_ctrl: Node = $TouchInputController
@onready var workspace: Control = $Workspace
@onready var view_2d_root: Control = $Workspace/View2D
@onready var map_canvas: ColorRect = $Workspace/View2D/MapCanvas
@onready var view_3d_container: SubViewportContainer = $Workspace/View3DContainer
@onready var subviewport_3d: SubViewport = $Workspace/View3DContainer/SubViewport3D
@onready var terrain_mesh: MeshInstance3D = $Workspace/View3DContainer/SubViewport3D/TerrainMesh
@onready var sun_light: DirectionalLight3D = $Workspace/View3DContainer/SubViewport3D/DirectionalLight3D
@onready var cam_pivot: Node3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot
@onready var cam_3d: Camera3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot/Camera3D

var current_tool: String = "mountain"
var brush_radius: float = 38.0
var brush_strength: float = 0.022
var sea_level: float = 0.5
var sun_azimuth: float = 315.0
var relief_mult: float = 1.0
var map_zoom: float = 1.0
var map_offset: Vector2 = Vector2.ZERO
var yaw: float = 0.45
var pitch: float = -0.62
var cam_dist: float = 13.5
var current_theme_idx: int = 0
var is_painting: bool = false

var mat_2d: ShaderMaterial
var mat_3d: ShaderMaterial
var overlay: Control

# Interfaz
var btn_mode_3d: Button
var btn_undo: Button
var btn_redo: Button
var lbl_world: Label
var tool_bar: HBoxContainer
var lbl_hint: Label
var tool_buttons: Dictionary = {}
var theme_buttons: Array[Button] = []
var lbl_tool_name: Label
var val_size: Label
var val_strength: Label
var val_sea: Label
var val_sun: Label
var val_relief: Label
var val_land: Label
var val_water: Label
var val_strokes: Label
var sec_brush: Control
var sec_relief: Control

const TOOLS = [
	["TIERRA", "land"],
	["CORDILLERA", "mountain"],
	["MAR", "sea"],
	["RÍO", "river"],
]

const THEMES = [
	{
		"name": "EDITORIAL",
		"deep": Color(0.24, 0.33, 0.43),
		"shallow": Color(0.42, 0.53, 0.64),
		"low": Color(0.95, 0.92, 0.84),
		"mid": Color(0.86, 0.82, 0.72),
		"high": Color(0.66, 0.63, 0.57),
		"snow": Color(0.98, 0.97, 0.94),
		"ink": Color(0.10, 0.11, 0.13)
	},
	{
		"name": "PERGAMINO",
		"deep": Color(0.55, 0.48, 0.38),
		"shallow": Color(0.73, 0.66, 0.53),
		"low": Color(0.93, 0.87, 0.73),
		"mid": Color(0.82, 0.74, 0.58),
		"high": Color(0.60, 0.50, 0.38),
		"snow": Color(0.96, 0.93, 0.86),
		"ink": Color(0.22, 0.15, 0.10)
	},
	{
		"name": "FANTASÍA",
		"deep": Color(0.09, 0.22, 0.38),
		"shallow": Color(0.22, 0.52, 0.66),
		"low": Color(0.45, 0.62, 0.38),
		"mid": Color(0.62, 0.60, 0.38),
		"high": Color(0.48, 0.44, 0.42),
		"snow": Color(0.96, 0.97, 0.99),
		"ink": Color(0.08, 0.10, 0.12)
	}
]

func _ready() -> void:
	workspace.offset_top = HEADER_H + TABS_H
	workspace.offset_right = -PANEL_W
	_build_ui()
	_setup_materials()
	_connect_signals()
	view_3d_container.visible = false
	subviewport_3d.render_target_update_mode = SubViewport.UPDATE_DISABLED
	_apply_theme(0)
	_apply_sun()
	_update_3d_camera()
	_select_tool(current_tool)
	await get_tree().process_frame
	_center_map()
	view_2d_root.resized.connect(_center_map)
	# Las cifras del mundo inicial se leen cuando ya está en pantalla (nunca durante _ready).
	get_tree().create_timer(0.6).timeout.connect(func(): engine.refresh_stats())

# ---------------------------------------------------------------- mapa y 3D

func _setup_materials() -> void:
	mat_2d = ShaderMaterial.new()
	mat_2d.shader = load("res://shaders/map_2d.gdshader")
	map_canvas.material = mat_2d

	var plane := PlaneMesh.new()
	plane.size = Vector2(12.0, 12.0)
	plane.subdivide_width = 140
	plane.subdivide_depth = 140
	terrain_mesh.mesh = plane

	mat_3d = ShaderMaterial.new()
	mat_3d.shader = load("res://shaders/terrain_3d.gdshader")
	terrain_mesh.material_override = mat_3d

	engine.heightmap_ready.connect(func(tex: Texture2D):
		mat_2d.set_shader_parameter("height_tex", tex)
		mat_3d.set_shader_parameter("height_tex", tex)
	)

func _connect_signals() -> void:
	input_ctrl.stroke_started.connect(func(pos: Vector2, pressure: float):
		is_painting = true
		engine.begin_stroke()
		_on_paint(pos, pressure)
	)
	input_ctrl.stroke_moved.connect(_on_paint)
	input_ctrl.stroke_ended.connect(func():
		is_painting = false
		overlay.hide_ring()
		engine.end_stroke()
	)
	input_ctrl.pan_changed.connect(func(d: Vector2):
		map_offset += d
		_apply_2d_transform()
	)
	input_ctrl.zoom_changed.connect(func(factor: float, center: Vector2):
		if input_ctrl.is_3d_mode:
			cam_dist = clampf(cam_dist / factor, 4.5, 24.0)
			_update_3d_camera()
		else:
			# center ya viene en coordenadas locales del Workspace (las de gui_input).
			var old_zoom: float = map_zoom
			map_zoom = clampf(map_zoom * factor, 0.5, 10.0)
			map_offset = center - (center - map_offset) * (map_zoom / old_zoom)
			_apply_2d_transform()
	)
	input_ctrl.orbit_changed.connect(func(d: Vector2):
		yaw -= d.x * 0.007
		pitch = clampf(pitch - d.y * 0.007, -1.45, -0.15)
		_update_3d_camera()
	)
	engine.history_changed.connect(func(can_undo: bool, can_redo: bool):
		btn_undo.disabled = not can_undo
		btn_redo.disabled = not can_redo
		val_strokes.text = Estilo.miles(engine.stroke_count)
	)
	engine.stats_changed.connect(func(land_frac: float, _max_h: float):
		var land_pct: int = int(round(land_frac * 100.0))
		val_land.text = "%d %%" % land_pct
		val_water.text = "%d %%" % (100 - land_pct)
		val_strokes.text = Estilo.miles(engine.stroke_count)
	)

func _on_workspace_gui_input(event: InputEvent) -> void:
	input_ctrl.handle_input(event)

## pos llega en coordenadas locales del Workspace. View2D ocupa el Workspace entero desde (0, 0),
## así que el mapa está en map_offset con escala map_zoom.
func _on_paint(pos: Vector2, pressure: float) -> void:
	var uv: Vector2 = (pos - map_offset) / (map_canvas.size * map_zoom)
	var effective_radius: float = brush_radius * (0.6 if current_tool == "river" else 1.0)
	var effective_strength: float = brush_strength * pressure * (1.35 if current_tool == "river" else 1.0)
	engine.paint_uv(uv, current_tool, effective_radius, effective_strength)
	var screen_r: float = effective_radius / float(engine.resolution) * map_canvas.size.x * map_zoom
	overlay.show_ring(pos, screen_r, current_tool == "sea" or current_tool == "river")

func _center_map() -> void:
	var ws_size: Vector2 = view_2d_root.size
	if ws_size.x <= 0.0 or ws_size.y <= 0.0:
		return
	var target_dim: float = minf(ws_size.x, ws_size.y) - (MAP_MAT + 3.0) * 2.0 - 48.0
	map_canvas.size = Vector2(target_dim, target_dim)
	map_zoom = 1.0
	map_offset = (ws_size - map_canvas.size) * 0.5
	_apply_2d_transform()

func _apply_2d_transform() -> void:
	map_canvas.scale = Vector2.ONE * map_zoom
	map_canvas.position = map_offset
	overlay.set_zoom(int(round(map_zoom * 100.0)))

func _update_3d_camera() -> void:
	cam_pivot.rotation = Vector3(pitch, yaw, 0.0)
	cam_3d.position = Vector3(0.0, 0.0, cam_dist)

func _toggle_3d() -> void:
	input_ctrl.is_3d_mode = not input_ctrl.is_3d_mode
	var in_3d: bool = input_ctrl.is_3d_mode
	view_3d_container.visible = in_3d
	view_2d_root.visible = not in_3d
	subviewport_3d.render_target_update_mode = (
		SubViewport.UPDATE_ALWAYS if in_3d else SubViewport.UPDATE_DISABLED
	)
	btn_mode_3d.text = "VOLVER AL MAPA  ←" if in_3d else "VER RELIEVE 3D  →"
	tool_bar.visible = not in_3d
	lbl_hint.text = "UN DEDO GIRA · DOS DEDOS ACERCAN" if in_3d else "LÁPIZ DIBUJA · DOS DEDOS MUEVEN Y ACERCAN"
	sec_brush.visible = not in_3d
	sec_relief.visible = in_3d

func _apply_theme(idx: int) -> void:
	current_theme_idx = idx
	var t: Dictionary = THEMES[idx]
	for k in [["col_deep_sea", "deep"], ["col_shallow_sea", "shallow"], ["col_land_low", "low"], ["col_land_mid", "mid"], ["col_snow", "snow"]]:
		var c: Color = t[k[1]]
		mat_2d.set_shader_parameter(k[0], c)
		mat_3d.set_shader_parameter(k[0], c)
	var high: Color = t["high"]
	var ink: Color = t["ink"]
	mat_2d.set_shader_parameter("col_land_high", high)
	mat_2d.set_shader_parameter("col_ink", ink)
	for i in theme_buttons.size():
		_paint_filter(theme_buttons[i], i == idx)

func _apply_sun() -> void:
	var az: float = deg_to_rad(sun_azimuth)
	# En el mapa el norte está arriba (-Y en pantalla); la luz viene DESDE el acimut.
	mat_2d.set_shader_parameter("sun_dir", Vector3(sin(az) * 0.78, -cos(az) * 0.78, 0.62))
	var elev: float = deg_to_rad(42.0)
	# En 3D el norte es -Z (la V de la textura crece hacia +Z). La luz viaja hacia el lado opuesto.
	var travel := Vector3(-sin(az) * cos(elev), -sin(elev), cos(az) * cos(elev))
	sun_light.basis = Basis.looking_at(travel, Vector3.UP)
	val_sun.text = "%s · %d°" % [_cardinal(sun_azimuth), int(round(sun_azimuth)) % 360]

func _cardinal(deg: float) -> String:
	var names: Array[String] = ["N", "NE", "E", "SE", "S", "SO", "O", "NO"]
	var i: int = int(round(fposmod(deg, 360.0) / 45.0)) % 8
	return names[i]

func _new_world() -> void:
	var s: int = randi_range(1, 9999)
	lbl_world.text = "MUNDO Nº %04d · %s" % [s, Estilo.fecha_corta()]
	engine.regenerate_world(s)

# ---------------------------------------------------------------- interfaz

func _select_tool(tool_id: String) -> void:
	current_tool = tool_id
	for k in tool_buttons.keys():
		var b: Button = tool_buttons[k]
		var active: bool = (str(k) == tool_id)
		if active:
			Estilo.pintar_boton(b, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 22, 0)
		else:
			Estilo.pintar_boton(b, Estilo.PAPEL, Estilo.TINTA, Color("1C1B19"), 22, 0)
	for item in TOOLS:
		if item[1] == tool_id:
			lbl_tool_name.text = item[0]

func _paint_filter(b: Button, on: bool) -> void:
	if on:
		Estilo.pintar_boton(b, Estilo.PAPEL, Estilo.TINTA, Estilo.TINTA, 14, 10, Estilo.TINTA, 2)
	else:
		Estilo.pintar_boton(b, Estilo.TINTA, Color(0, 0, 0, 0), Color(Estilo.TINTA, 0.08), 14, 10, Estilo.TINTA, 2)

func _build_ui() -> void:
	var bg := ColorRect.new()
	bg.color = Estilo.MESA
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	move_child(bg, 0)

	_build_header()
	_build_tabs()
	_build_panel()
	_build_map_frame()

	overlay = MapOverlay.new()
	overlay.set_anchors_preset(Control.PRESET_FULL_RECT)
	view_2d_root.add_child(overlay)

func _build_header() -> void:
	var header := Panel.new()
	header.add_theme_stylebox_override("panel", Estilo.caja(Estilo.PAPEL))
	header.set_anchors_preset(Control.PRESET_TOP_WIDE)
	header.offset_bottom = HEADER_H
	add_child(header)

	var row := HBoxContainer.new()
	row.set_anchors_preset(Control.PRESET_FULL_RECT)
	row.offset_left = 28
	row.offset_right = -20
	row.add_theme_constant_override("separation", 0)
	header.add_child(row)

	# «ATLAS.»: el nombre en grotesca, con el punto del acento.
	var name_box := HBoxContainer.new()
	name_box.add_theme_constant_override("separation", 0)
	name_box.alignment = BoxContainer.ALIGNMENT_CENTER
	name_box.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	name_box.add_child(Estilo.etiqueta("ATLAS", 54, Estilo.TINTA, Estilo.grotesca_prieta()))
	name_box.add_child(Estilo.etiqueta(".", 54, Estilo.ACENTO, Estilo.F_GROTESCA))
	row.add_child(name_box)

	var gap := Control.new()
	gap.custom_minimum_size = Vector2(34, 0)
	row.add_child(gap)

	var links := HBoxContainer.new()
	links.add_theme_constant_override("separation", 6)
	links.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(links)

	var transparent := Color(0, 0, 0, 0)
	var pressed_bg := Color(Estilo.TINTA, 0.07)
	var b_new := Estilo.boton("+ NUEVO MUNDO", 13, Estilo.ACENTO, transparent, pressed_bg, 10, 12)
	b_new.pressed.connect(_new_world)
	links.add_child(b_new)

	btn_undo = Estilo.boton("DESHACER", 13, Estilo.TINTA, transparent, pressed_bg, 10, 12)
	btn_undo.disabled = true
	btn_undo.pressed.connect(func(): engine.undo())
	links.add_child(btn_undo)

	btn_redo = Estilo.boton("REHACER", 13, Estilo.TINTA, transparent, pressed_bg, 10, 12)
	btn_redo.disabled = true
	btn_redo.pressed.connect(func(): engine.redo())
	links.add_child(btn_redo)

	var b_fit := Estilo.boton("ENCUADRAR", 13, Estilo.TINTA, transparent, pressed_bg, 10, 12)
	b_fit.pressed.connect(_center_map)
	links.add_child(b_fit)

	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)

	lbl_world = Estilo.etiqueta("MUNDO Nº %04d · %s" % [engine.world_seed, Estilo.fecha_corta()], 12, Estilo.GRIS)
	lbl_world.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(lbl_world)

	var gap2 := Control.new()
	gap2.custom_minimum_size = Vector2(20, 0)
	row.add_child(gap2)

	btn_mode_3d = Estilo.boton("VER RELIEVE 3D  →", 15, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 22, 15)
	var bold_grotesk := FontVariation.new()
	bold_grotesk.base_font = Estilo.F_GROTESCA
	bold_grotesk.spacing_glyph = 1
	btn_mode_3d.add_theme_font_override("font", bold_grotesk)
	btn_mode_3d.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	btn_mode_3d.pressed.connect(_toggle_3d)
	row.add_child(btn_mode_3d)

func _build_tabs() -> void:
	var band := Panel.new()
	band.add_theme_stylebox_override("panel", Estilo.caja(Estilo.TINTA))
	band.set_anchors_preset(Control.PRESET_TOP_WIDE)
	band.offset_top = HEADER_H
	band.offset_bottom = HEADER_H + TABS_H
	add_child(band)

	var row := HBoxContainer.new()
	row.set_anchors_preset(Control.PRESET_FULL_RECT)
	row.offset_left = 6
	row.offset_right = -24
	row.add_theme_constant_override("separation", 0)
	band.add_child(row)

	tool_bar = HBoxContainer.new()
	tool_bar.add_theme_constant_override("separation", 0)
	row.add_child(tool_bar)
	for item in TOOLS:
		var b := Estilo.boton(item[0], 13, Estilo.PAPEL, Estilo.TINTA, Color("1C1B19"), 22, 0)
		b.size_flags_vertical = Control.SIZE_EXPAND_FILL
		b.custom_minimum_size = Vector2(0, TABS_H)
		var tool_id: String = item[1]
		b.pressed.connect(func(): _select_tool(tool_id))
		tool_bar.add_child(b)
		tool_buttons[tool_id] = b

	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)

	lbl_hint = Estilo.etiqueta("LÁPIZ DIBUJA · DOS DEDOS MUEVEN Y ACERCAN", 11, Color(Estilo.PAPEL, 0.55))
	lbl_hint.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(lbl_hint)

func _build_map_frame() -> void:
	# Paspartú de papel con filete de tinta, detrás del mapa: se mueve y escala con él.
	var frame := Panel.new()
	frame.show_behind_parent = true
	frame.mouse_filter = Control.MOUSE_FILTER_IGNORE
	frame.set_anchors_preset(Control.PRESET_FULL_RECT)
	frame.offset_left = -MAP_MAT
	frame.offset_top = -MAP_MAT
	frame.offset_right = MAP_MAT
	frame.offset_bottom = MAP_MAT
	frame.add_theme_stylebox_override("panel", Estilo.caja(Estilo.PAPEL, Estilo.TINTA, 3))
	map_canvas.add_child(frame)

# ---------------------------------------------------------------- panel de la derecha

func _build_panel() -> void:
	var panel := Panel.new()
	var sb := Estilo.caja(Estilo.PAPEL)
	sb.border_color = Estilo.TINTA
	sb.border_width_left = 3
	panel.add_theme_stylebox_override("panel", sb)
	panel.set_anchors_preset(Control.PRESET_RIGHT_WIDE)
	panel.offset_left = -PANEL_W
	panel.offset_top = HEADER_H + TABS_H
	add_child(panel)

	var scroll := ScrollContainer.new()
	scroll.set_anchors_preset(Control.PRESET_FULL_RECT)
	scroll.offset_left = 3
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	panel.add_child(scroll)

	var margin := MarginContainer.new()
	margin.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["margin_left", "margin_right"]:
		margin.add_theme_constant_override(side, 24)
	margin.add_theme_constant_override("margin_top", 20)
	margin.add_theme_constant_override("margin_bottom", 24)
	scroll.add_child(margin)

	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 22)
	margin.add_child(col)

	# --- Pincel (2D)
	var brush := VBoxContainer.new()
	brush.add_theme_constant_override("separation", 10)
	sec_brush = brush
	col.add_child(brush)
	lbl_tool_name = _title(brush, "Pincel", "CORDILLERA")
	var blocks := _blocks(brush, [["38", "PX · TAMAÑO"], ["73 %", "FUERZA"]])
	val_size = blocks[0]
	val_strength = blocks[1]
	_slider_row(brush, "TAMAÑO", 10.0, 95.0, 1.0, brush_radius, func(v: float):
		brush_radius = v
		val_size.text = str(int(v))
	)
	_slider_row(brush, "FUERZA", 0.006, 0.05, 0.001, brush_strength, func(v: float):
		brush_strength = v
		val_strength.text = "%d %%" % int(round((v - 0.006) / (0.05 - 0.006) * 100.0))
	)
	val_size.text = str(int(brush_radius))
	val_strength.text = "%d %%" % int(round((brush_strength - 0.006) / (0.05 - 0.006) * 100.0))

	# --- Relieve (3D)
	var relief := VBoxContainer.new()
	relief.add_theme_constant_override("separation", 10)
	relief.visible = false
	sec_relief = relief
	col.add_child(relief)
	val_relief = _title(relief, "Relieve", "× 1,0")
	_slider_row(relief, "EXAGERACIÓN", 0.4, 2.2, 0.1, relief_mult, func(v: float):
		relief_mult = v
		mat_3d.set_shader_parameter("height_scale", 2.6 * v)
		val_relief.text = "× " + ("%.1f" % v).replace(".", ",")
	)

	col.add_child(Estilo.filete(2))

	# --- Mar y sol
	var sea_sun := VBoxContainer.new()
	sea_sun.add_theme_constant_override("separation", 10)
	col.add_child(sea_sun)
	_title(sea_sun, "Mar y sol", "")
	val_sea = _slider_row(sea_sun, "NIVEL DEL MAR", 0.30, 0.70, 0.01, sea_level, func(v: float):
		sea_level = v
		mat_2d.set_shader_parameter("sea_level", v)
		mat_3d.set_shader_parameter("sea_level", v)
		engine.set_sea_level(v)
		val_sea.text = "%d %%" % int(round(v * 100.0))
	)
	val_sea.text = "%d %%" % int(round(sea_level * 100.0))
	val_sun = _slider_row(sea_sun, "LUZ DESDE", 0.0, 359.0, 1.0, sun_azimuth, func(v: float):
		sun_azimuth = v
		_apply_sun()
	)

	col.add_child(Estilo.filete(2))

	# --- Estilo del mapa
	var style := VBoxContainer.new()
	style.add_theme_constant_override("separation", 12)
	col.add_child(style)
	_title(style, "Estilo del mapa", "")
	var chips := HFlowContainer.new()
	chips.add_theme_constant_override("h_separation", 8)
	chips.add_theme_constant_override("v_separation", 8)
	style.add_child(chips)
	for i in THEMES.size():
		var t: Dictionary = THEMES[i]
		var b := Estilo.boton(str(t["name"]), 12, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 14, 10)
		var idx: int = i
		b.pressed.connect(func(): _apply_theme(idx))
		chips.add_child(b)
		theme_buttons.append(b)

	col.add_child(Estilo.filete(2))

	# --- Este mundo
	var world := VBoxContainer.new()
	world.add_theme_constant_override("separation", 12)
	col.add_child(world)
	_title(world, "Este mundo", "")
	var wb := _blocks(world, [["—", "TIERRA"], ["—", "MAR"], ["0", "TRAZOS"]])
	val_land = wb[0]
	val_water = wb[1]
	val_strokes = wb[2]

	# --- Pie, como en las hermanas: «ATLAS · hecho en casa» y la versión en gris.
	var foot := VBoxContainer.new()
	foot.add_theme_constant_override("separation", 2)
	col.add_child(foot)
	foot.add_child(Estilo.filete(1, Color(Estilo.TINTA, 0.2)))
	var made := Estilo.etiqueta("ATLAS · hecho en casa", 13, Estilo.GRIS, Estilo.F_SEMI)
	foot.add_child(made)
	var ver: String = str(ProjectSettings.get_setting("application/config/version", ""))
	foot.add_child(Estilo.etiqueta(ver, 12, Estilo.GRIS_CLARO, Estilo.F_TEXTO))

## El título de un bloque (TituloC): grotesca a la izquierda y, a la derecha, su dato en el acento.
func _title(parent: Control, text: String, right: String) -> Label:
	var row := HBoxContainer.new()
	parent.add_child(row)
	var t := Estilo.etiqueta(text, 26, Estilo.TINTA, Estilo.F_GROTESCA)
	t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(t)
	var r := Estilo.etiqueta(right, 12, Estilo.ACENTO)
	r.size_flags_vertical = Control.SIZE_SHRINK_END
	r.add_theme_constant_override("line_spacing", 0)
	row.add_child(r)
	return r

## Bloques de cifras (BloquesCifras): borde de 3 px; el primero en el acento, el tercero en el azul de la familia.
func _blocks(parent: Control, items: Array) -> Array[Label]:
	var outer := PanelContainer.new()
	var frame_sb := Estilo.caja(Estilo.TINTA, Estilo.TINTA, 3, 3, 3)
	outer.add_theme_stylebox_override("panel", frame_sb)
	parent.add_child(outer)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 3)
	outer.add_child(row)
	var values: Array[Label] = []
	for i in items.size():
		var bg: Color = Estilo.PAPEL
		var fg: Color = Estilo.TINTA
		if i == 0:
			bg = Estilo.ACENTO
			fg = Estilo.SOBRE_ACENTO
		elif i == 2:
			bg = Estilo.BLOQUE2
			fg = Color.WHITE
		var cell := PanelContainer.new()
		cell.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		cell.add_theme_stylebox_override("panel", Estilo.caja(bg, Color(0, 0, 0, 0), 0, 10, 10))
		row.add_child(cell)
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 0)
		cell.add_child(v)
		var item: Array = items[i]
		var val := Estilo.etiqueta(str(item[0]), 28, fg, Estilo.F_GROTESCA)
		v.add_child(val)
		v.add_child(Estilo.etiqueta(str(item[1]), 10, fg))
		values.append(val)
	return values

## Etiqueta, valor a la derecha y deslizador debajo. Devuelve la etiqueta del valor.
func _slider_row(parent: Control, label: String, min_v: float, max_v: float, step: float, value: float, on_change: Callable) -> Label:
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 0)
	parent.add_child(box)
	var head := HBoxContainer.new()
	box.add_child(head)
	var l := Estilo.etiqueta(label, 11, Estilo.GRIS)
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(l)
	var val := Estilo.etiqueta("", 11, Estilo.TINTA)
	head.add_child(val)
	var s := Estilo.deslizador(min_v, max_v, step, value)
	s.value_changed.connect(on_change)
	box.add_child(s)
	return val
