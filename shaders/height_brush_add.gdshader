shader_type canvas_item;
render_mode blend_add;
#include "res://shaders/ruido.gdshaderinc"

uniform vec2 rect_min = vec2(0.0, 0.0);
uniform vec2 rect_size = vec2(100.0, 100.0);
uniform vec2 p0 = vec2(512.0, 512.0);
uniform vec2 p1 = vec2(512.0, 512.0);
uniform float brush_radius = 40.0;
uniform float strength = 0.025;
uniform float hardness = 0.35;
uniform int brush_mode = 0;
uniform float noise_scale = 26.0;
uniform vec2 map_size = vec2(1024.0, 1024.0);

float hash(vec2 p) {
	return hash_cell(p, 0.0);
}

float noise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(
		mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
		mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
		u.y
	);
}

float ridged(vec2 p) {
	float v = 0.0;
	float a = 0.55;
	for (int i = 0; i < 4; i++) {
		float n = 1.0 - abs(noise(p) * 2.0 - 1.0);
		v += a * n * n;
		p *= 2.1;
		a *= 0.48;
	}
	return v;
}

void fragment() {
	vec2 px = rect_min + UV * rect_size;
	vec2 pa = px - p0;
	vec2 ba = p1 - p0;
	float seg = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
	float d = length(pa - ba * seg) / max(brush_radius, 1.0);
	if (d > 1.0) discard;

	float falloff = smoothstep(1.0, hardness * 0.8, d);
	float amount = strength * falloff;

	if (brush_mode == 1) {
		vec2 world_uv = px / map_size;
		amount *= ridged(world_uv * noise_scale) * 1.85;
	}
	COLOR = vec4(amount, amount, amount, 1.0);
}
