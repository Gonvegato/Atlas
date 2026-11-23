shader_type canvas_item;

// Quita las rayas del relieve (escalones rectos que dejaba el ruido antiguo en algunos móviles): busca, alrededor de
// cada píxel, saltos bruscos entre dos píxeles vecinos (mucho mayores que la pendiente a los dos lados: un escalón,
// no una cresta ni una ladera) y, cerca de ellos, mezcla con la media de alrededor. Lo demás no cambia.
uniform sampler2D snap : filter_nearest, repeat_disable;
uniform vec2 map_size = vec2(2048.0);
uniform vec2 rect_min = vec2(0.0);
uniform vec2 rect_size = vec2(2048.0);
// Escala de la pasada (1, 2, 4…): con 2 o 4 se ven como escalón las rampas cortas que dejó la pasada anterior.
uniform float scale = 1.0;

float h_at(vec2 px) {
	return texture(snap, (px + 0.5) / map_size).r;
}

// ¿Hay un escalón entre p y p + d? 0-1.
float step_at(vec2 p, vec2 d) {
	float a = h_at(p - d);
	float b = h_at(p);
	float c = h_at(p + d);
	float e = h_at(p + d * 2.0);
	float jump = abs(c - b);
	float side = max(abs(b - a), abs(e - c));
	return smoothstep(0.0015 * scale, 0.004 * scale, jump) * smoothstep(2.0, 4.0, jump / max(side, 0.0002));
}

void fragment() {
	vec2 px = floor(rect_min + UV * rect_size);
	float h = h_at(px);
	float m = 0.0;
	for (int j = -2; j <= 2; j++) {
		for (int i = -2; i <= 2; i++) {
			vec2 q = px + vec2(float(i), float(j)) * scale;
			m = max(m, step_at(q, vec2(1.0, 0.0) * scale));
			m = max(m, step_at(q, vec2(0.0, 1.0) * scale));
			m = max(m, step_at(q, vec2(1.0, 1.0) * scale));
			m = max(m, step_at(q, vec2(1.0, -1.0) * scale));
		}
	}
	float out_h = h;
	if (m > 0.0) {
		float sum = 0.0;
		float w = 0.0;
		for (int j = -3; j <= 3; j++) {
			for (int i = -3; i <= 3; i++) {
				float k = exp(-float(i * i + j * j) / 6.0);
				sum += h_at(px + vec2(float(i), float(j)) * scale) * k;
				w += k;
			}
		}
		out_h = mix(h, sum / w, m);
	}
	COLOR = vec4(out_h, out_h, out_h, 1.0);
}
