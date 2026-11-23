extends Control

@onready var engine: Node = $HeightmapEngine
@onready var input_ctrl: Node = $TouchInputController
@onready var workspace: Control = $Workspace
@onready var view_2d_root: Control = $Workspace/View2D
@onready var map_canvas: ColorRect = $Workspace/View2D/MapCanvas
@onready var view_3d_container: SubViewportContainer = $Workspace/View3DContainer
@onready var subviewport_3d: SubViewport = $Workspace/View3DContainer/SubViewport3D
@onready var terrain_mesh: MeshInstance3D = $Workspace/View3DContainer/SubViewport3D/TerrainMesh
@onready var cam_pivot: Node3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot
@onready var cam_3d: Camera3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot/Camera3D

var current_tool: String = "mountain"
var brush_radius: float = 38.0
var brush_strength: float = 0.022
var map_zoom: float = 1.0
var map_offset: Vector2 = Vector2.ZERO
var yaw: float = 0.45
var pitch: float = -0.62
var cam_dist: float = 13.5

var mat_2d: ShaderMaterial
var mat_3d: ShaderMaterial
var btn_mode_3d: Button
var tool_buttons: Dictionary = {}
var current_theme_idx: int = 0

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
	_build_editorial_ui()
	_setup_materials()
	_connect_signals()
	view_3d_container.visible = false
	subviewport_3d.render_target_update_mode = SubViewport.UPDATE_DISABLED
	_update_3d_camera()
	await get_tree().process_frame
	_center_map()

func _center_map() -> void:
	var ws_size := view_2d_root.size
	if ws_size.x <= 0.0 or ws_size.y <= 0.0:
		return
	var target_dim := minf(ws_size.x, ws_size.y) * 0.92
	map_canvas.size = Vector2(target_dim, target_dim)
	map_zoom = 1.0
	map_offset = (ws_size - map_canvas.size) * 0.5
	_apply_2d_transform()

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
		engine.begin_stroke()
		_on_paint(pos, pressure)
	)
	input_ctrl.stroke_moved.connect(_on_paint)
	input_ctrl.stroke_ended.connect(func(): engine.end_stroke())
	input_ctrl.pan_changed.connect(func(d: Vector2):
		map_offset += d
		_apply_2d_transform()
	)
	input_ctrl.zoom_changed.connect(func(factor: float, center: Vector2):
		if input_ctrl.is_3d_mode:
			cam_dist = clampf(cam_dist / factor, 4.5, 24.0)
			_update_3d_camera()
		else:
			var old_zoom := map_zoom
			map_zoom = clampf(map_zoom * factor, 0.5, 10.0)
			var local_center := center - workspace.global_position
			map_offset = local_center - (local_center - map_offset) * (map_zoom / old_zoom)
			_apply_2d_transform()
	)
	input_ctrl.orbit_changed.connect(func(d: Vector2):
		yaw -= d.x * 0.007
		pitch = clampf(pitch - d.y * 0.007, -1.45, -0.15)
		_update_3d_camera()
	)

func _on_workspace_gui_input(event: InputEvent) -> void:
	input_ctrl.handle_input(event)

func _on_paint(screen_pos: Vector2, pressure: float) -> void:
	var local_pos: Vector2 = map_canvas.get_global_transform_with_canvas().affine_inverse() * screen_pos
	var uv := local_pos / map_canvas.size
	var effective_radius: float = brush_radius * (0.6 if current_tool == "river" else 1.0)
	var effective_strength: float = brush_strength * pressure * (1.35 if current_tool == "river" else 1.0)
	engine.paint_uv(uv, current_tool, effective_radius, effective_strength)

func _apply_2d_transform() -> void:
	map_canvas.scale = Vector2.ONE * map_zoom
	map_canvas.position = map_offset

func _update_3d_camera() -> void:
	cam_pivot.rotation = Vector3(pitch, yaw, 0.0)
	cam_3d.position = Vector3(0.0, 0.0, cam_dist)

func _toggle_3d() -> void:
	input_ctrl.is_3d_mode = not input_ctrl.is_3d_mode
	view_3d_container.visible = input_ctrl.is_3d_mode
	view_2d_root.visible = not input_ctrl.is_3d_mode
	subviewport_3d.render_target_update_mode = (
		SubViewport.UPDATE_ALWAYS if input_ctrl.is_3d_mode else SubViewport.UPDATE_DISABLED
	)
	btn_mode_3d.text = "VOLVER A 2D" if input_ctrl.is_3d_mode else "VER RELIEVE 3D"

func _cycle_theme(btn: Button) -> void:
	current_theme_idx = (current_theme_idx + 1) % THEMES.size()
	var t: Dictionary = THEMES[current_theme_idx]
	btn.text = "TEMA: " + t["name"]
	mat_2d.set_shader_parameter("col_deep_sea", t["deep"])
	mat_2d.set_shader_parameter("col_shallow_sea", t["shallow"])
	mat_2d.set_shader_parameter("col_land_low", t["low"])
	mat_2d.set_shader_parameter("col_land_mid", t["mid"])
	mat_2d.set_shader_parameter("col_land_high", t["high"])
	mat_2d.set_shader_parameter("col_snow", t["snow"])
	mat_2d.set_shader_parameter("col_ink", t["ink"])

	mat_3d.set_shader_parameter("col_deep_sea", t["deep"])
	mat_3d.set_shader_parameter("col_shallow_sea", t["shallow"])
	mat_3d.set_shader_parameter("col_land_low", t["low"])
	mat_3d.set_shader_parameter("col_land_mid", t["mid"])
	mat_3d.set_shader_parameter("col_snow", t["snow"])

func _make_style(bg: Color, radius: int = 0) -> StyleBoxFlat:
	var sb := StyleBoxFlat.new()
	sb.bg_color = bg
	sb.corner_radius_top_left = radius
	sb.corner_radius_top_right = radius
	sb.corner_radius_bottom_left = radius
	sb.corner_radius_bottom_right = radius
	sb.content_margin_left = 14
	sb.content_margin_right = 14
	sb.content_margin_top = 8
	sb.content_margin_bottom = 8
	return sb

func _select_tool(tool_id: String) -> void:
	current_tool = tool_id
	for k in tool_buttons.keys():
		var b: Button = tool_buttons[k]
		var active: bool = (str(k) == tool_id)
		b.add_theme_stylebox_override("normal", _make_style(Color("5C7894") if active else Color("141414")))
		b.add_theme_stylebox_override("hover", _make_style(Color("6E8AA6") if active else Color("242629")))
		b.add_theme_stylebox_override("pressed", _make_style(Color("4A647E")))

func _build_editorial_ui() -> void:
	var bg := ColorRect.new()
	bg.color = Color("1A1B1E")
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	move_child(bg, 0)

	var top_bar := ColorRect.new()
	top_bar.color = Color("F6F1DF")
	top_bar.custom_minimum_size = Vector2(0, 68)
	top_bar.set_anchors_preset(Control.PRESET_TOP_WIDE)
	add_child(top_bar)

	var title_box := HBoxContainer.new()
	title_box.position = Vector2(22, 10)
	title_box.add_theme_constant_override("separation", 2)
	top_bar.add_child(title_box)

	var title := Label.new()
	title.text = "ATLAS"
	title.add_theme_color_override("font_color", Color("141414"))
	title.add_theme_font_size_override("font_size", 36)
	title_box.add_child(title)

	var dot := Label.new()
	dot.text = "."
	dot.add_theme_color_override("font_color", Color("5C7894"))
	dot.add_theme_font_size_override("font_size", 36)
	title_box.add_child(dot)

	btn_mode_3d = Button.new()
	btn_mode_3d.text = "VER RELIEVE 3D"
	btn_mode_3d.set_anchors_preset(Control.PRESET_CENTER_RIGHT)
	btn_mode_3d.offset_left = -190
	btn_mode_3d.offset_right = -18
	btn_mode_3d.offset_top = -20
	btn_mode_3d.offset_bottom = 20
	btn_mode_3d.add_theme_stylebox_override("normal", _make_style(Color("5C7894"), 3))
	btn_mode_3d.add_theme_stylebox_override("hover", _make_style(Color("6E8AA6"), 3))
	btn_mode_3d.add_theme_stylebox_override("pressed", _make_style(Color("4A647E"), 3))
	btn_mode_3d.add_theme_color_override("font_color", Color("FFFFFF"))
	btn_mode_3d.pressed.connect(_toggle_3d)
	top_bar.add_child(btn_mode_3d)

	var sub_bar := ColorRect.new()
	sub_bar.color = Color("141414")
	sub_bar.set_anchors_preset(Control.PRESET_TOP_WIDE)
	sub_bar.offset_top = 68
	sub_bar.offset_bottom = 116
	add_child(sub_bar)

	var scroll := ScrollContainer.new()
	scroll.set_anchors_preset(Control.PRESET_FULL_RECT)
	scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	sub_bar.add_child(scroll)

	var tool_bar := HBoxContainer.new()
	tool_bar.add_theme_constant_override("separation", 4)
	scroll.add_child(tool_bar)

	for item in [["TIERRA", "land"], ["CORDILLERA", "mountain"], ["MAR", "sea"], ["RÍO", "river"]]:
		var b := Button.new()
		b.text = item[0]
		b.custom_minimum_size = Vector2(105, 48)
		b.add_theme_color_override("font_color", Color("F6F1DF"))
		b.pressed.connect(func(): _select_tool(item[1]))
		tool_bar.add_child(b)
		tool_buttons[item[1]] = b

	_select_tool(current_tool)

	var size_lbl := Label.new()
	size_lbl.text = "  PINCEL:"
	size_lbl.add_theme_color_override("font_color", Color("A8A498"))
	tool_bar.add_child(size_lbl)

	var slider := HSlider.new()
	slider.min_value = 10.0
	slider.max_value = 95.0
	slider.value = brush_radius
	slider.custom_minimum_size = Vector2(120, 48)
	slider.value_changed.connect(func(v: float): brush_radius = v)
	tool_bar.add_child(slider)

	var btn_theme := Button.new()
	btn_theme.text = "TEMA: EDITORIAL"
	btn_theme.custom_minimum_size = Vector2(150, 48)
	btn_theme.add_theme_stylebox_override("normal", _make_style(Color("141414")))
	btn_theme.add_theme_stylebox_override("hover", _make_style(Color("242629")))
	btn_theme.add_theme_color_override("font_color", Color("F6F1DF"))
	btn_theme.pressed.connect(func(): _cycle_theme(btn_theme))
	tool_bar.add_child(btn_theme)

	var btn_undo := Button.new()
	btn_undo.text = "DESHACER"
	btn_undo.custom_minimum_size = Vector2(108, 48)
	btn_undo.add_theme_stylebox_override("normal", _make_style(Color("141414")))
	btn_undo.add_theme_stylebox_override("hover", _make_style(Color("242629")))
	btn_undo.add_theme_color_override("font_color", Color("F6F1DF"))
	btn_undo.pressed.connect(func(): engine.undo())
	tool_bar.add_child(btn_undo)

	var btn_new := Button.new()
	btn_new.text = "NUEVO MUNDO"
	btn_new.custom_minimum_size = Vector2(130, 48)
	btn_new.add_theme_stylebox_override("normal", _make_style(Color("141414")))
	btn_new.add_theme_stylebox_override("hover", _make_style(Color("242629")))
	btn_new.add_theme_color_override("font_color", Color("5C7894"))
	btn_new.pressed.connect(func(): engine.regenerate_world(randf() * 1000.0))
	tool_bar.add_child(btn_new)
