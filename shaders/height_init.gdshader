shader_type canvas_item;

uniform float seed = 42.0;

float hash(vec2 p) {
	p = fract(p * vec2(123.34, 456.21) + seed);
	p += dot(p, p + 45.32);
	return fract(p.x * p.y);
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
	float dist = length((uv - 0.5) * vec2(1.85, 2.0));
	float continent = fbm(uv * 4.5) - dist * 0.65 + 0.18;
	// Las cordilleras solo donde una máscara de baja frecuencia lo permite: cadenas, no un macizo nevado entero.
	float range_mask = smoothstep(0.36, 0.60, fbm(uv * 2.2 + vec2(7.3, 1.9)));
	float mountains = ridged(uv * 9.0) * smoothstep(0.05, 0.45, continent) * range_mask * 0.40;
	float h = clamp(0.5 + continent * 0.30 + mountains, 0.02, 0.98);
	COLOR = vec4(h, h, h, 1.0);
}
