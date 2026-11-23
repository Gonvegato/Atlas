shader_type canvas_item;
render_mode blend_mix;

// Pinta un identificador exacto (bioma o reino) en una capa de 8 bits: borde duro (sin antialias, el valor
// tiene que llegar entero), algo irregular para que las lindes no salgan como círculos.

uniform vec2 rect_min = vec2(0.0);
uniform vec2 rect_size = vec2(100.0);
uniform vec2 p0 = vec2(512.0);
uniform vec2 p1 = vec2(512.0);
uniform float brush_radius = 40.0;
uniform float value = 0.0;
uniform float roughness = 0.22;

float hash(vec2 p) {
	p = fract(p * vec2(234.34, 435.345));
	p += dot(p, p + 34.23);
	return fract(p.x * p.y);
}

float noise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

void fragment() {
	vec2 px = rect_min + UV * rect_size;
	vec2 pa = px - p0;
	vec2 ba = p1 - p0;
	float seg = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
	float d = length(pa - ba * seg) / max(brush_radius, 1.0);
	// Ruido de onda larga: linde irregular pero continua (con ruido fino salían islitas de un píxel).
	float n = noise(px * 0.035) * 0.75 + noise(px * 0.08) * 0.25;
	d += (n - 0.5) * roughness;
	if (d > 1.0) discard;
	COLOR = vec4(value, value, value, 1.0);
}
