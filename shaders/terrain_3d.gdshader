shader_type spatial;
render_mode cull_back, diffuse_lambert, specular_schlick_ggx;
#include "res://shaders/ruido.gdshaderinc"

uniform sampler2D height_tex : filter_linear, repeat_disable;
uniform float sea_level = 0.5;
uniform float height_scale = 2.6;
// Tamaño de la malla en unidades del mundo (ancho, fondo): los mapas apaisados no son cuadrados.
uniform vec2 terrain_size = vec2(12.0, 12.0);
uniform sampler2D biome_tex : filter_nearest, repeat_disable;
uniform sampler2D realm_tex : filter_nearest, repeat_disable;
uniform sampler2D biome_pal : source_color, filter_nearest, repeat_disable;
uniform sampler2D realm_pal : source_color, filter_nearest, repeat_disable;
uniform float biome_desat = 0.0;
uniform vec3 biome_tint : source_color = vec3(0.95, 0.92, 0.84);
uniform float biome_tint_amt = 0.0;
uniform bool show_realms = true;
// Fundido entre biomas (píxeles del mapa): el suelo se mezcla con los biomas vecinos en ese radio.
uniform float biome_blend = 16.0;
// Calles y plazas de los asentamientos (0 nada, ~0,5 calle, 1 plaza).
uniform sampler2D road_tex : filter_linear, repeat_disable;
uniform bool has_roads = false;
// Suelos con textura (CC0 de Poly Haven, assets/terrain/suelos.png como Texture2DArray). biome_data es la misma
// paleta de biomas SIN source_color: su fila 3 guarda índices de capa, que no deben pasar por la conversión de color.
uniform sampler2DArray ground_tex : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D biome_data : filter_nearest, repeat_disable;
uniform bool use_ground = true;
// Repeticiones de la textura por unidad del mundo (el mapa mide 12 de lado mayor).
uniform float ground_scale = 1.7;
// Cuánto se tiñe el suelo con el color del bioma/estilo (identidad del mapa).
uniform float ground_tint = 0.28;
uniform float ground_gain = 1.75;
// Fantasía: más saturación y algo más de contraste que la foto (para ir con los árboles y los edificios).
uniform float ground_saturation = 1.55;
uniform float ground_contrast = 1.12;
// Montañas low-poly: sombreado por caras (como los modelos de Kenney y KayKit) y altura suavizada en el vértice
// para que las crestas no tiemblen. vertex_step = 1 / subdivisiones de la malla (lo pone main).
uniform float facets = 1.0;
uniform float vertex_step = 0.0033;
uniform vec3 rock_col : source_color = vec3(0.70, 0.55, 0.42);
uniform vec3 cliff_col : source_color = vec3(0.48, 0.36, 0.29);
uniform vec3 snowcap_col : source_color = vec3(0.96, 0.97, 1.0);

varying float v_h;

uniform vec3 col_deep_sea : source_color = vec3(0.12, 0.20, 0.30);
uniform vec3 col_shallow_sea : source_color = vec3(0.32, 0.47, 0.59);
uniform vec3 col_land_low : source_color = vec3(0.88, 0.84, 0.74);
uniform vec3 col_land_mid : source_color = vec3(0.64, 0.62, 0.52);
uniform vec3 col_cliff : source_color = vec3(0.38, 0.36, 0.34);
uniform vec3 col_snow : source_color = vec3(0.96, 0.96, 0.95);

int id_at(sampler2D tex, vec2 uv) {
	return int(round(texture(tex, uv).r * 255.0));
}

vec3 biome_color(int id) {
	vec3 c = texelFetch(biome_pal, ivec2(id, 0), 0).rgb;
	float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
	c = mix(c, vec3(l), biome_desat);
	return mix(c, biome_tint * (0.55 + 0.55 * l), biome_tint_amt);
}

float hash1(vec2 p) {
	return hash_cell(p, 0.0);
}

float vnoise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash1(i), hash1(i + vec2(1.0, 0.0)), u.x), mix(hash1(i + vec2(0.0, 1.0)), hash1(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Una capa de suelo a dos escalas (la grande rompe la repetición de la pequeña).
vec3 ground(float layer, vec2 p) {
	vec3 a = texture(ground_tex, vec3(p * ground_scale, layer)).rgb;
	vec3 b = texture(ground_tex, vec3(p * ground_scale * 0.23 + vec2(0.37, 0.71), layer)).rgb;
	return mix(a, b, 0.4);
}

// Roca proyectada desde los tres ejes (en las paredes no se estira).
vec3 ground_triplanar(float layer, vec3 p, vec3 n) {
	vec3 w = pow(abs(n), vec3(4.0));
	w /= (w.x + w.y + w.z);
	vec3 x = texture(ground_tex, vec3(p.zy * ground_scale, layer)).rgb;
	vec3 y = texture(ground_tex, vec3(p.xz * ground_scale, layer)).rgb;
	vec3 z = texture(ground_tex, vec3(p.xy * ground_scale, layer)).rgb;
	return x * w.x + y * w.y + z * w.z;
}

// El suelo texturizado de un bioma (sus dos capas mezcladas con ruido y teñido con su color).
vec3 biome_ground(int gid, vec2 wp, float n1, float elev, vec3 auto_land) {
	float l1 = 13.0;
	float l2 = 0.0;
	float m = 0.35;
	if (gid > 0) {
		vec4 d = texelFetch(biome_data, ivec2(gid, 3), 0);
		l1 = round(d.r * 255.0);
		l2 = round(d.g * 255.0);
		m = d.b;
	} else if (elev > 0.3) {
		l1 = 16.0;
		l2 = 0.0;
		m = 0.45;
	}
	vec3 g = ground(l1, wp);
	if (m > 0.01) {
		float k = smoothstep(1.0 - m - 0.12, 1.0 - m + 0.12, n1);
		g = mix(g, ground(l2, wp), k);
	}
	float lum = dot(g, vec3(0.2126, 0.7152, 0.0722));
	vec3 base_col = gid > 0 ? biome_color(gid) : auto_land;
	float tint_k = gid > 0 ? ground_tint * 1.4 : ground_tint;
	return mix(g, base_col * lum * 2.2, tint_k);
}

void vertex() {
	// Media de 5 muestras a medio paso de malla: la malla tiene menos vértices que píxeles el mapa y, sin esto,
	// las crestas finas salen con dientes y parpadean al girar.
	float d = vertex_step * 0.5;
	float h = texture(height_tex, UV).r * 0.4;
	h += texture(height_tex, UV + vec2(d, 0.0)).r * 0.15;
	h += texture(height_tex, UV - vec2(d, 0.0)).r * 0.15;
	h += texture(height_tex, UV + vec2(0.0, d)).r * 0.15;
	h += texture(height_tex, UV - vec2(0.0, d)).r * 0.15;
	v_h = h;
	float elev = max(h - sea_level, 0.0);
	VERTEX.y = elev * height_scale;
}

void fragment() {
	vec2 t = 2.0 / vec2(textureSize(height_tex, 0));
	float h = texture(height_tex, UV).r;
	float coast_dist = h - sea_level;

	if (coast_dist <= 0.001) {
		float depth = clamp(-coast_dist * 4.5, 0.0, 1.0);
		vec3 water = mix(col_shallow_sea, col_deep_sea, pow(depth, 0.6));
		float foam = smoothstep(0.015, 0.0, -coast_dist);
		ALBEDO = mix(water, vec3(0.95, 0.98, 1.0), foam * 0.75);
		EMISSION = water * 0.25;
		ROUGHNESS = mix(0.35, 0.6, foam);
		METALLIC = 0.05;
		NORMAL = normalize((VIEW_MATRIX * MODEL_MATRIX * vec4(0.0, 1.0, 0.0, 0.0)).xyz);
	} else {
		float tl = texture(height_tex, UV + vec2(-t.x, -t.y)).r;
		float tc = texture(height_tex, UV + vec2( 0.0, -t.y)).r;
		float tr = texture(height_tex, UV + vec2( t.x, -t.y)).r;
		float ml = texture(height_tex, UV + vec2(-t.x,  0.0)).r;
		float mr = texture(height_tex, UV + vec2( t.x,  0.0)).r;
		float bl = texture(height_tex, UV + vec2(-t.x,  t.y)).r;
		float bc = texture(height_tex, UV + vec2( 0.0,  t.y)).r;
		float br = texture(height_tex, UV + vec2( t.x,  t.y)).r;

		float dx = ((tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl)) * 0.25;
		float dz = ((bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr)) * 0.25;
		vec2 step_world = terrain_size * t * 2.0;

		vec3 n_obj = normalize(vec3(
			-dx * height_scale / step_world.x,
			1.0,
			-dz * height_scale / step_world.y
		));
		// Normal de la cara (low-poly): de las derivadas de la posición. Se mezcla con la del mapa según facets.
		vec3 fn_view = normalize(cross(dFdx(VERTEX), dFdy(VERTEX)));
		vec3 fn_obj = normalize((INV_VIEW_MATRIX * vec4(fn_view, 0.0)).xyz);
		if (fn_obj.y < 0.0) {
			fn_obj = -fn_obj;
		}
		// Solo las montañas van por caras: en laderas suaves, valles y cañones las filas de la malla con normales
		// distintas se leían como escalones.
		float elev_f = clamp((v_h - sea_level) * 2.6, 0.0, 1.0);
		float rough = smoothstep(0.42, 0.62, elev_f) * smoothstep(0.10, 0.30, 1.0 - n_obj.y);
		n_obj = normalize(mix(n_obj, fn_obj, facets * rough * 0.7));
		NORMAL = normalize((VIEW_MATRIX * MODEL_MATRIX * vec4(n_obj, 0.0)).xyz);

		// La altura de la geometría (la del vértice), para que las franjas sigan a las caras.
		float elev = clamp((v_h - sea_level) * 2.6, 0.0, 1.0);
		float slope = 1.0 - n_obj.y;

		vec3 land = mix(col_land_low, col_land_mid, smoothstep(0.08, 0.55, elev));
		int bid = id_at(biome_tex, UV);
		if (bid > 0) {
			land = mix(biome_color(bid), land, smoothstep(0.55, 0.95, elev) * 0.6);
		}
		if (use_ground) {
			vec2 wp = UV * terrain_size;
			float n1 = vnoise(wp * 1.4);
			float n2 = vnoise(wp * 0.33 + 7.0);
			// Bioma con el punto desplazado por ruido (lindes irregulares) y fundido con los vecinos: 7 muestras en un
			// disco de radio biome_blend; los biomas repetidos se suman y cada bioma distinto se texturiza una vez.
			vec2 bsz = vec2(textureSize(biome_tex, 0));
			vec2 wuv = UV + (vec2(vnoise(wp * 3.1), vnoise(wp * 3.1 + 11.0)) - 0.5) * 4.0 / bsz;
			int ids[7];
			float ws[7];
			int nid = 0;
			float rot = vnoise(wp * 5.3) * 6.2831853;
			for (int k = 0; k < 7; k++) {
				float rk = k == 0 ? 0.0 : (k < 4 ? 0.5 : 1.0);
				float ak = rot + float(k) * 2.0943951 + (k >= 4 ? 1.0471976 : 0.0);
				vec2 suv = wuv + vec2(cos(ak), sin(ak)) * rk * biome_blend / bsz;
				int sid = id_at(biome_tex, k == 0 ? wuv : suv);
				float wk = k == 0 ? 2.0 : (k < 4 ? 1.2 : 0.7);
				if (biome_blend < 1.0 && k > 0) {
					break;
				}
				bool found = false;
				for (int j = 0; j < 7; j++) {
					if (j >= nid) break;
					if (ids[j] == sid) {
						ws[j] += wk;
						found = true;
						break;
					}
				}
				if (!found) {
					ids[nid] = sid;
					ws[nid] = wk;
					nid++;
				}
			}
			int gid = ids[0];
			vec3 g = vec3(0.0);
			float wsum = 0.0;
			float best_w = 0.0;
			for (int j = 0; j < 7; j++) {
				if (j >= nid) break;
				g += biome_ground(ids[j], wp, n1, elev, land) * ws[j];
				wsum += ws[j];
				if (ws[j] > best_w) {
					best_w = ws[j];
					gid = ids[j];
				}
			}
			g /= wsum;
			// Arena en la orilla.
			float beach = smoothstep(0.03, 0.006, coast_dist) * (gid > 0 ? 0.6 : 1.0);
			g = mix(g, ground(5.0, wp), beach);
			float lum2 = dot(g, vec3(0.2126, 0.7152, 0.0722));
			g = max(mix(vec3(lum2), g, ground_saturation), vec3(0.0));
			g = max((g - 0.18) * ground_contrast + 0.18, vec3(0.0));
			g *= ground_gain * (0.9 + 0.2 * n2);
			// Calles y caminos (tierra, grava, adoquín y losas) y plazas empedradas: con su textura pero sin la
			// saturación del suelo. Un canal de road_tex por suelo.
			if (has_roads) {
				vec4 rd = texture(road_tex, UV + (vec2(vnoise(wp * 40.0), vnoise(wp * 40.0 + 5.0)) - 0.5) * 0.0006);
				vec4 m = smoothstep(vec4(0.25), vec4(0.6), rd);
				if (m.r + m.g + m.b + m.a > 0.0) {
					float l_dirt = dot(ground(1.0, wp * 1.6), vec3(0.2126, 0.7152, 0.0722));
					float l_grav = dot(ground(14.0, wp * 1.6), vec3(0.2126, 0.7152, 0.0722));
					float l_cobb = dot(ground(18.0, wp * 2.2), vec3(0.2126, 0.7152, 0.0722));
					float l_slab = dot(ground(15.0, wp * 1.2), vec3(0.2126, 0.7152, 0.0722));
					g = mix(g, vec3(0.70, 0.56, 0.40) * (0.55 + l_dirt * 0.9), m.r * 0.9);
					g = mix(g, vec3(0.76, 0.70, 0.60) * (0.55 + l_grav * 0.9), m.g * 0.92);
					g = mix(g, vec3(0.68, 0.66, 0.63) * (0.5 + l_cobb * 1.0), m.b * 0.95);
					// Losas: la textura de roca con juntas.
					vec2 sl = fract(wp * 90.0);
					float joint = 1.0 - smoothstep(0.0, 0.08, min(min(sl.x, 1.0 - sl.x), min(sl.y, 1.0 - sl.y)));
					g = mix(g, vec3(0.84, 0.80, 0.72) * (0.6 + l_slab * 0.7) * (1.0 - joint * 0.35), m.a * 0.95);
				}
			}

			// Montaña estilizada: roca cálida (con el detalle de luces de la textura), paredes más oscuras y casquetes de
			// nieve con el borde irregular pero nítido. Por caras, como los modelos low-poly.
			vec3 p3 = vec3(wp.x, coast_dist * height_scale, wp.y);
			float rockw = smoothstep(0.26, 0.40, slope);
			if (gid == 0 || gid == 4 || gid == 11 || gid == 12) {
				rockw = max(rockw, smoothstep(0.42, 0.56, elev + (n1 - 0.5) * 0.12));
			}
			if (rockw > 0.01) {
				float rl = dot(ground_triplanar(19.0, p3, n_obj), vec3(0.2126, 0.7152, 0.0722));
				vec3 rock = mix(rock_col, cliff_col, smoothstep(0.38, 0.62, slope)) * (0.7 + rl * 1.1);
				// Estratos en las paredes empinadas (cañones, escarpes): franjas de ocre y rojizo según la altura.
				// Bandas suaves (seno, no escalón) y onduladas, para que no parezcan peldaños.
				float band = sin((p3.y * 7.0 + (n1 - 0.5) * 0.6 + (n2 - 0.5) * 0.4) * 6.2831853) * 0.5 + 0.5;
				vec3 strata = mix(vec3(0.78, 0.55, 0.37), vec3(0.64, 0.40, 0.29), band);
				rock = mix(rock, strata * (0.75 + rl * 0.8), smoothstep(0.50, 0.70, slope) * 0.5);
				g = mix(g, rock, rockw);
			}
			float sn = elev + (n1 - 0.5) * 0.1;
			float snow = smoothstep(0.74, 0.77, sn) * (1.0 - smoothstep(0.42, 0.54, slope));
			if (snow > 0.01) {
				g = mix(g, snowcap_col * (0.93 + 0.07 * n2), snow);
			}
			// Luces suaves (la arena y la nieve se quemaban a blanco con la ganancia).
			g = g / (1.0 + max(max(g.r, g.g), g.b) * 0.3);
			ALBEDO = g;
			land = g;
		}
		if (show_realms) {
			int rid = id_at(realm_tex, UV);
			if (rid > 0) {
				land = mix(land, texelFetch(realm_pal, ivec2(rid, 0), 0).rgb, 0.22);
			}
		}
		if (!use_ground) {
			if (has_roads) {
				vec4 rd = smoothstep(vec4(0.25), vec4(0.6), texture(road_tex, UV));
				land = mix(land, vec3(0.62, 0.52, 0.40), rd.r * 0.85);
				land = mix(land, vec3(0.66, 0.62, 0.55), rd.g * 0.85);
				land = mix(land, vec3(0.62, 0.61, 0.60), rd.b * 0.9);
				land = mix(land, vec3(0.78, 0.75, 0.68), rd.a * 0.9);
			}
			land = mix(land, col_cliff, smoothstep(0.18, 0.52, slope));
			land = mix(land, col_snow, smoothstep(0.68, 0.90, elev) * (1.0 - smoothstep(0.35, 0.65, slope)));
		}

		ALBEDO = land;
		ROUGHNESS = 0.92;
		METALLIC = 0.0;
		// Papel mate: sin esto, a contraluz el Fresnel rasante quema el primer plano a blanco puro.
		SPECULAR = 0.08;
	}
}
