shader_type canvas_item;
render_mode blend_mix;

// Pinceles que LEEN el relieve: la textura `snap` es la capa al empezar el trazo (el espejo de la CPU).
// Cada pincelada mezcla (blend_mix, alfa = fuerza) la altura hacia un objetivo; las pinceladas repetidas
// convergen a ese objetivo, así que repasar hace más efecto sin pasarse.
// mode: 0 suavizar · 1 acantilado (realza la pendiente en una sola pared) · 2 meseta · 3 valle · 4 cañón
// Todos los perfiles son continuos y llegan al terreno sin arista: nada de escalones.

uniform sampler2D snap : filter_linear, repeat_disable;
uniform vec2 rect_min = vec2(0.0);
uniform vec2 rect_size = vec2(100.0);
uniform vec2 p0 = vec2(512.0);
uniform vec2 p1 = vec2(512.0);
uniform float brush_radius = 40.0;
uniform float strength = 0.3;
uniform int mode = 0;
uniform vec2 map_size = vec2(1024.0);
uniform float sea_level = 0.5;
uniform float target_h = 0.6;
uniform float steps = 10.0;
// Valle y cañón: el recorrido del trazo hasta ahora (los últimos puntos). La distancia se mide a todo el
// recorrido para que una pincelada nueva no rellene lo que talló la anterior (el objetivo solo puede bajar).
uniform vec2 path[48];
uniform int path_count = 0;

float h_at(vec2 px) {
	return texture(snap, px / map_size).r;
}

float hash1(vec2 p) {
	p = fract(p * vec2(123.34, 456.21));
	p += dot(p, p + 45.32);
	return fract(p.x * p.y);
}

float vnoise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash1(i), hash1(i + vec2(1.0, 0.0)), u.x), mix(hash1(i + vec2(0.0, 1.0)), hash1(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Ruido de 3 octavas en [-1, 1], en píxeles del mapa con un periodo base de [scale] px.
float fbm(vec2 px, float scale) {
	vec2 p = px / scale;
	return (vnoise(p) * 0.55 + vnoise(p * 2.1 + 17.0) * 0.3 + vnoise(p * 4.3 + 41.0) * 0.15) * 2.0 - 1.0;
}

// 0 → 1 con pendiente nula en los dos extremos (sin arista ni al pie ni en el borde).
float smoother(float a, float b, float x) {
	float t = clamp((x - a) / (b - a), 0.0, 1.0);
	return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

void fragment() {
	vec2 px = rect_min + UV * rect_size;
	vec2 pa = px - p0;
	vec2 ba = p1 - p0;
	float seg = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
	float d = length(pa - ba * seg) / max(brush_radius, 1.0);
	if (d > 1.0) discard;

	float h = h_at(px);
	if (mode >= 3) {
		for (int i = 0; i < 47; i++) {
			if (i + 1 >= path_count) break;
			vec2 a = path[i];
			vec2 b = path[i + 1];
			vec2 qa = px - a;
			vec2 qb = b - a;
			float sg = clamp(dot(qa, qb) / max(dot(qb, qb), 0.0001), 0.0, 1.0);
			d = min(d, length(qa - qb * sg) / max(brush_radius, 1.0));
		}
	}
	float target = h;
	float a = strength;
	// El río del valle y del cañón queda un poco por debajo del mar: se pinta como agua, con su orilla a tinta.
	float bed = sea_level - 0.014;

	if (mode == 0) {
		// Desenfoque de 2 anillos (13 muestras) con un paso proporcional al pincel.
		float s = max(1.5, brush_radius * 0.12);
		float sum = h * 2.0;
		float w = 2.0;
		for (int i = 0; i < 6; i++) {
			float ang = float(i) * 1.0471976;
			vec2 o = vec2(cos(ang), sin(ang));
			sum += h_at(px + o * s) * 1.5 + h_at(px + o.yx * vec2(-1.0, 1.0) * s * 2.0);
			w += 2.5;
		}
		target = sum / w;
		a = strength * smoothstep(1.0, 0.35, d);
	} else if (mode == 1) {
		// Acantilado: donde hay desnivel (lo más bajo y lo más alto alrededor), la ladera se recoge en UNA pared
		// empinada a media altura, con el borde irregular; arriba y abajo quedan casi como estaban. La pared se decide
		// con la altura suavizada (si no, los bultos pequeños se volvían hoyos) y luego se devuelve el detalle.
		float lo = 1.0;
		float hi = 0.0;
		float hs = h * 2.0;
		float rr = max(brush_radius * 0.6, 4.0);
		for (int i = 0; i < 12; i++) {
			float ang = float(i) * 0.5235988;
			vec2 o = vec2(cos(ang), sin(ang));
			float a1 = h_at(px + o * rr);
			hs += h_at(px + o * rr * 0.3);
			lo = min(lo, a1);
			hi = max(hi, a1);
		}
		hs /= 14.0;
		lo = min(lo, hs);
		hi = max(hi, hs);
		float span = hi - lo;
		float on = smoothstep(0.012, 0.035, span);
		if (on > 0.0) {
			// [steps] (1-10): lo vertical que es la pared.
			float hard = clamp((steps - 1.0) / 9.0, 0.0, 1.0);
			float mid = lo + span * (0.5 + 0.16 * fbm(px, brush_radius * 0.9));
			float w = span * mix(0.32, 0.06, hard);
			float wall = mix(lo, hi, smoothstep(mid - w, mid + w, hs));
			target = mix(h, wall + (h - hs) * 0.5, on);
		}
		a = strength * smoothstep(1.0, 0.5, d);
	} else if (mode == 2) {
		target = target_h;
		a = strength * smoothstep(1.0, 0.6, d);
	} else if (mode == 3) {
		// Valle: un cauce bajo el agua, una vega llana junto al río y laderas que suben en curva suave hasta el
		// terreno (pendiente nula al pie y en el borde: sin escalón ni arista). El ancho ondula a lo largo del trazo.
		float dn = d * (1.0 + 0.12 * fbm(px, brush_radius * 1.4));
		float plain = sea_level + 0.004;
		float slope_k = smoother(0.16, 1.0, dn);
		float bank = plain + max(h - plain, 0.0) * slope_k;
		float prof = mix(bed, bank, smoothstep(0.04, 0.12, dn));
		target = min(h, prof);
		a = strength;
	} else if (mode == 5) {
		// Pantano: al nivel del agua, con charcas (algunas manchas un poco por debajo del mar).
		float n = fract(sin(dot(floor(px / 9.0), vec2(12.9898, 78.233))) * 43758.5453);
		float puddle = step(0.7, n);
		target = mix(sea_level + 0.004, sea_level - 0.006, puddle);
		a = strength * smoothstep(1.0, 0.5, d);
	} else if (mode == 6) {
		// Lago: el fondo plano un poco bajo el agua, orillas suaves.
		target = min(h, mix(sea_level - 0.025, h, smoothstep(0.55, 1.0, d)));
		a = strength;
	} else {
		// Cañón: cauce con agua, fondo seco estrecho y paredes casi verticales que suben de una vez hasta la cornisa,
		// con barrancos (el pie de la pared entra y sale con el ruido). Los estratos los pone la textura del 3D.
		float g = fbm(px, brush_radius * 0.7);
		float dn = d * (1.0 + 0.10 * g);
		float floor_h = sea_level + 0.006;
		float top = max(h, floor_h);
		// Pared: casi toda la subida entre 0,24 y 0,52 del radio; un talud suave al pie y la cornisa redondeada.
		float wall = smoothstep(0.24, 0.52, dn);
		float talus = smoother(0.16, 0.30, dn) * 0.12;
		float rim = smoother(0.48, 0.95, dn);
		float k = clamp(max(wall * 0.86 + talus, rim), 0.0, 1.0);
		float prof = mix(floor_h, top, k);
		prof = mix(bed, prof, smoothstep(0.04, 0.10, dn));
		target = min(h, prof);
		a = strength;
	}
	COLOR = vec4(target, target, target, clamp(a, 0.0, 1.0));
}
