shader_type canvas_item;
#include "res://shaders/ruido.gdshaderinc"

uniform float seed = 42.0;
// Escala de la textura para que el ruido no se estire en mapas apaisados (ancho/alto, el lado corto = 1).
uniform vec2 aspect_scale = vec2(1.0, 1.0);
// 0 = continente generado · 1 = océano (para dibujar desde cero)
uniform int mode = 0;
// Mundo infinito: la capa es una ventana del mundo. El ruido se calcula con las coordenadas del mundo (píxeles,
// a la escala de un mapa de 2.048) y el continente no tiene borde de isla: tierras y mares sin fin.
uniform bool infinite = false;
uniform vec2 world_origin = vec2(0.0);
uniform vec2 window_size = vec2(4096.0);

float hash(vec2 p) {
	return hash_cell(p, seed);
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

float fbm(vec2 p) {
	float v = 0.0;
	float a = 0.5;
	mat2 rot = mat2(vec2(0.8, 0.6), vec2(-0.6, 0.8));
	for (int i = 0; i < 6; i++) {
		v += a * noise(p);
		p = rot * p * 2.05;
		a *= 0.5;
	}
	return v;
}

float ridged(vec2 p) {
	float v = 0.0;
	float a = 0.5;
	for (int i = 0; i < 5; i++) {
		float n = 1.0 - abs(noise(p) * 2.0 - 1.0);
		v += a * n * n;
		p *= 2.15;
		a *= 0.5;
	}
	return v;
}

void fragment() {
	vec2 uv = UV;
	vec2 nuv = uv * aspect_scale;
	if (infinite) {
		nuv = (world_origin + UV * window_size) / 2048.0;
	}
	if (mode == 1) {
		float sea = 0.38 + (fbm(nuv * 3.0) - 0.5) * 0.06;
		COLOR = vec4(sea, sea, sea, 1.0);
	} else {
		float dist = length((uv - 0.5) * vec2(1.85, 2.0));
		float continent = fbm(nuv * 4.5) - dist * 0.65 + 0.18;
		if (infinite) {
			// Continentes y océanos de varios miles de píxeles, con costas del mismo detalle que antes.
			continent = (fbm(nuv * 0.9 + vec2(13.1, 7.7)) - 0.5) * 1.35 + (fbm(nuv * 4.5) - 0.5) * 0.45 + 0.03;
			// Siempre hay tierra donde empiezas (alrededor del centro del mundo).
			continent += 0.10 * exp(-dot(nuv, nuv) / 3.0);
		}
		// Las cordilleras solo donde una máscara de baja frecuencia lo permite: cadenas, no un macizo nevado entero.
		float range_mask = smoothstep(0.36, 0.60, fbm(nuv * 2.2 + vec2(7.3, 1.9)));
		float mountains = ridged(nuv * 9.0) * smoothstep(0.05, 0.45, continent) * range_mask * 0.40;
		float h = clamp(0.5 + continent * 0.30 + mountains, 0.02, 0.98);
		COLOR = vec4(h, h, h, 1.0);
	}
}
