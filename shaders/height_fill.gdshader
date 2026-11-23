shader_type canvas_item;
render_mode blend_mix;
#include "res://shaders/ruido.gdshaderinc"

// Relleno de un contorno dibujado a mano (Polygon2D con TEXTURE = el relieve al empezar y UV = posición en el mapa).
// mode: 0 tierra (sube lo que esté bajo el mar a tierra baja con algo de relieve) · 1 lago (baja a poco bajo el mar) ·
// 2 mar (baja a mar hondo) · 3 pantano (justo al nivel del mar, con charcas).
uniform int mode = 0;
uniform float sea_level = 0.5;
uniform float seed = 0.0;

float hash(vec2 p) {
	return hash_cell(p, seed);
}

float noise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
	float v = 0.0;
	float a = 0.5;
	for (int i = 0; i < 5; i++) {
		v += a * noise(p);
		p *= 2.03;
		a *= 0.5;
	}
	return v;
}

void fragment() {
	float h = texture(TEXTURE, UV).r;
	float n = fbm(UV * 18.0);
	float t = h;
	if (mode == 0) {
		t = max(h, sea_level + 0.03 + n * 0.07);
	} else if (mode == 1) {
		t = min(h, sea_level - 0.02 - n * 0.015);
	} else if (mode == 2) {
		t = min(h, sea_level - 0.07 - n * 0.04);
	} else {
		float puddle = smoothstep(0.55, 0.62, fbm(UV * 40.0 + 3.1));
		t = mix(sea_level + 0.004 + n * 0.006, sea_level - 0.006, puddle);
	}
	COLOR = vec4(t, t, t, 1.0);
}
