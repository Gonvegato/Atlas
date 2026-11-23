shader_type canvas_item;
render_mode blend_sub;

uniform vec2 rect_min = vec2(0.0, 0.0);
uniform vec2 rect_size = vec2(100.0, 100.0);
uniform vec2 p0 = vec2(512.0, 512.0);
uniform vec2 p1 = vec2(512.0, 512.0);
uniform float brush_radius = 40.0;
uniform float strength = 0.025;
uniform float hardness = 0.35;
uniform int brush_mode = 0;

void fragment() {
	vec2 px = rect_min + UV * rect_size;
	vec2 pa = px - p0;
	vec2 ba = p1 - p0;
	float seg = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
	float d = length(pa - ba * seg) / max(brush_radius, 1.0);
	if (d > 1.0) discard;

	float falloff = (brush_mode == 1)
		? smoothstep(1.0, 0.25, d)
		: smoothstep(1.0, hardness * 0.8, d);
	float amount = strength * falloff;
	COLOR = vec4(amount, amount, amount, 1.0);
}
