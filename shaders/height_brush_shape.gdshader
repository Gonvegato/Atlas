shader_type canvas_item;
render_mode blend_mix;

// Pinceles que LEEN el relieve: la textura `snap` es la capa al empezar el trazo (el espejo de la CPU).
// Cada pincelada mezcla (blend_mix, alfa = fuerza) la altura hacia un objetivo; las pinceladas repetidas
// convergen a ese objetivo, así que repasar hace más efecto sin pasarse.
// mode: 0 suavizar · 1 acantilado (terrazas) · 2 meseta · 3 valle · 4 cañón

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
		// Terrazas: rellanos planos y un escalón casi vertical al final de cada uno (acantilados).
		if (h > sea_level) {
			float hh = (h - sea_level) / max(1.0 - sea_level, 0.001);
			float q = hh * steps;
			float f = fract(q);
			float terr = (floor(q) + smoothstep(0.70, 0.93, f)) / steps;
			target = sea_level + terr * (1.0 - sea_level);
		}
		a = strength * smoothstep(1.0, 0.55, d);
	} else if (mode == 2) {
		target = target_h;
		a = strength * smoothstep(1.0, 0.6, d);
	} else if (mode == 3) {
		// Valle en U: el cauce (el centro) queda bajo el agua y las orillas bajan hasta justo por encima del río y
		// suben suaves hasta el terreno. Repasar ensancha la llanura del río, pero no la inunda.
		float k = smoothstep(0.10, 1.0, d);
		float plain = sea_level + 0.004;
		float bank = plain + max(h - plain, 0.0) * k * k;
		float prof = mix(bed, bank, smoothstep(0.06, 0.14, d));
		target = min(h, prof);
		a = strength;
	} else {
		// Cañón: cauce estrecho con agua, fondo seco algo más ancho y paredes escalonadas (cuatro escarpes con su
		// cornisa, como los cañones del desierto) hasta el borde, que conserva el terreno.
		float floor_h = sea_level + 0.006;
		float wall_t = clamp((d - 0.16) / 0.66, 0.0, 1.0);
		float steps_n = 4.0;
		float q = wall_t * steps_n;
		float stepped = (floor(q) + smoothstep(0.55, 0.85, fract(q))) / steps_n;
		float prof = mix(floor_h, max(h, floor_h), stepped);
		prof = mix(bed, prof, smoothstep(0.05, 0.12, d));
		target = min(h, prof);
		a = strength;
	}
	COLOR = vec4(target, target, target, clamp(a, 0.0, 1.0));
}
