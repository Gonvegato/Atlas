shader_type canvas_item;

// El mapa 2D: relieve (Sobel, sombreado, curvas, costa a tinta), biomas con sus símbolos y reinos con sus
// fronteras. Las tres capas tienen el mismo tamaño; biomas y reinos son identificadores (filtro nearest).

uniform sampler2D height_tex : filter_linear, repeat_disable;
uniform sampler2D biome_tex : filter_nearest, repeat_disable;
uniform sampler2D realm_tex : filter_nearest, repeat_disable;
// Paletas: biomas (N × 2: color; símbolo y densidad) y reinos (64 × 1: color, a = existe).
uniform sampler2D biome_pal : filter_nearest, repeat_disable;
uniform sampler2D realm_pal : filter_nearest, repeat_disable;

uniform float sea_level = 0.5;
uniform float relief_strength = 16.0;
uniform vec3 sun_dir = vec3(-0.6, -0.5, 0.62);
uniform float contour_density = 28.0;
uniform bool show_symbols = true;
uniform bool show_realms = true;
// Lado de la celda de los símbolos, en píxeles del mapa.
uniform float symbol_cell = 26.0;

uniform vec3 col_deep_sea : source_color = vec3(0.24, 0.33, 0.43);
uniform vec3 col_shallow_sea : source_color = vec3(0.42, 0.53, 0.64);
uniform vec3 col_land_low : source_color = vec3(0.95, 0.92, 0.84);
uniform vec3 col_land_mid : source_color = vec3(0.86, 0.82, 0.72);
uniform vec3 col_land_high : source_color = vec3(0.66, 0.63, 0.57);
uniform vec3 col_snow : source_color = vec3(0.98, 0.97, 0.94);
uniform vec3 col_ink : source_color = vec3(0.10, 0.11, 0.13);
// Cómo traduce cada estilo los colores de los biomas: desaturar y teñir hacia un tono de papel.
uniform float biome_desat = 0.0;
uniform vec3 biome_tint : source_color = vec3(0.95, 0.92, 0.84);
uniform float biome_tint_amt = 0.0;

float hash1(vec2 p) {
	p = fract(p * vec2(123.34, 456.21));
	p += dot(p, p + 45.32);
	return fract(p.x * p.y);
}

vec2 hash2(vec2 p) {
	return vec2(hash1(p), hash1(p + vec2(17.31, 9.73)));
}

float vnoise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash1(i), hash1(i + vec2(1.0, 0.0)), u.x), mix(hash1(i + vec2(0.0, 1.0)), hash1(i + vec2(1.0, 1.0)), u.x), u.y);
}

float paper_grain(vec2 p) {
	return vnoise(p);
}

int id_at(sampler2D tex, vec2 uv) {
	return int(round(texture(tex, uv).r * 255.0));
}

vec3 biome_color(int id) {
	vec3 c = texelFetch(biome_pal, ivec2(id, 0), 0).rgb;
	float l = dot(c, vec3(0.299, 0.587, 0.114));
	c = mix(c, vec3(l), biome_desat);
	return mix(c, biome_tint * (0.55 + 0.55 * l), biome_tint_amt);
}

// --- Formas (distancias con signo, coordenadas de celda centradas; y hacia abajo)

float sd_circle(vec2 p, float r) {
	return length(p) - r;
}

float sd_seg(vec2 p, vec2 a, vec2 b) {
	vec2 pa = p - a;
	vec2 ba = b - a;
	float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
	return length(pa - ba * h);
}

// Triángulo isósceles con la punta en el origen y la base en y = q.y (Inigo Quilez).
float sd_tri(vec2 p, vec2 q) {
	p.x = abs(p.x);
	vec2 a = p - q * clamp(dot(p, q) / dot(q, q), 0.0, 1.0);
	vec2 b = p - q * vec2(clamp(p.x / q.x, 0.0, 1.0), 1.0);
	float s = -sign(q.y);
	vec2 d = min(vec2(dot(a, a), s * (p.x * q.y - p.y * q.x)), vec2(dot(b, b), s * (p.y - q.y)));
	return -sqrt(d.x) * sign(d.y);
}

float ink_line(float d, float lw, float aa) {
	return 1.0 - smoothstep(lw - aa, lw + aa, d);
}

float inside(float d, float aa) {
	return 1.0 - smoothstep(-aa, aa, d);
}

// Un símbolo: x = relleno, y = tinta.
vec2 glyph(int kind, vec2 p, float aa, float rnd, vec2 cell_id) {
	float lw = max(0.032, aa * 0.8);
	float fill = 0.0;
	float ink = 0.0;
	if (kind == 1 || kind == 14) {
		float r = 0.21 + rnd * 0.06;
		float dc = sd_circle(p - vec2(0.0, -0.07), r);
		fill = inside(dc, aa);
		ink = ink_line(abs(dc), lw, aa);
		float dt = sd_seg(p, vec2(0.0, -0.07 + r), vec2(0.0, 0.30));
		ink = max(ink, ink_line(dt, lw, aa) * step(0.0, dc));
		if (kind == 14) {
			ink = max(ink, ink_line(sd_seg(p, vec2(-0.30, 0.31), vec2(0.30, 0.31)), lw * 0.8, aa));
		}
	} else if (kind == 2) {
		float dtr = sd_tri(p - vec2(0.0, -0.36), vec2(0.18 + rnd * 0.04, 0.52));
		fill = inside(dtr, aa);
		ink = ink_line(abs(dtr), lw, aa);
		ink = max(ink, ink_line(sd_seg(p, vec2(0.0, 0.16), vec2(0.0, 0.30)), lw, aa));
	} else if (kind == 3) {
		float dc = min(min(sd_circle(p - vec2(-0.12, 0.0), 0.19), sd_circle(p - vec2(0.12, 0.0), 0.19)), sd_circle(p - vec2(0.0, -0.15), 0.21));
		fill = inside(dc, aa);
		ink = ink_line(abs(dc), lw, aa);
	} else if (kind == 15) {
		vec2 q = (p - vec2(0.0, -0.12)) / vec2(0.30, 0.09);
		float de = (length(q) - 1.0) * 0.09;
		fill = inside(de, aa);
		ink = ink_line(abs(de), lw, aa);
		float dt = min(sd_seg(p, vec2(0.0, -0.05), vec2(0.0, 0.26)), sd_seg(p, vec2(0.0, 0.06), vec2(0.12, -0.05)));
		ink = max(ink, ink_line(dt, lw, aa) * step(0.0, de));
	} else if (kind == 4) {
		float dg = min(min(sd_seg(p, vec2(-0.10, 0.12), vec2(-0.17, -0.04)), sd_seg(p, vec2(0.0, 0.12), vec2(0.0, -0.09))), sd_seg(p, vec2(0.10, 0.12), vec2(0.17, -0.04)));
		ink = ink_line(dg, lw * 0.75, aa);
	} else if (kind == 5) {
		float da = abs(length(p - vec2(0.0, 0.30)) - 0.32);
		ink = ink_line(da, lw * 0.85, aa) * step(p.y, 0.10) * step(abs(p.x), 0.30);
	} else if (kind == 6) {
		for (int i = 0; i < 3; i++) {
			vec2 c = (hash2(cell_id + vec2(float(i) * 7.1, 3.3)) - 0.5) * 0.7;
			ink = max(ink, inside(sd_circle(p - c, 0.035 + aa * 0.3), aa));
		}
	} else if (kind == 7) {
		float dm = min(sd_seg(p, vec2(-0.26, 0.08), vec2(0.26, 0.08)), sd_seg(p, vec2(-0.14, 0.18), vec2(0.14, 0.18)));
		dm = min(dm, min(sd_seg(p, vec2(-0.08, 0.08), vec2(-0.11, -0.15)), sd_seg(p, vec2(0.06, 0.08), vec2(0.09, -0.11))));
		ink = ink_line(dm, lw * 0.8, aa);
	} else if (kind == 10) {
		float dr = (abs(p.x) / 0.13 + abs(p.y + 0.02) / 0.30 - 1.0) * 0.11;
		fill = inside(dr, aa);
		ink = max(ink_line(abs(dr), lw, aa), ink_line(sd_seg(p, vec2(0.0, -0.30), vec2(0.0, 0.26)), lw * 0.6, aa) * inside(dr, aa));
	} else if (kind == 11) {
		float dcap = max(sd_circle(p - vec2(0.0, 0.02), 0.23), p.y - 0.02);
		float dst = sd_seg(p, vec2(0.0, 0.02), vec2(0.0, 0.27));
		fill = max(inside(dcap, aa), ink_line(dst, 0.055, aa));
		ink = max(ink_line(abs(dcap), lw, aa), ink_line(abs(dst - 0.055), lw * 0.8, aa) * step(0.02, p.y));
	} else if (kind == 13) {
		float dd = min(sd_seg(p, vec2(0.0, 0.30), vec2(0.0, -0.22)), min(sd_seg(p, vec2(0.0, -0.02), vec2(-0.15, -0.20)), sd_seg(p, vec2(0.0, 0.07), vec2(0.14, -0.11))));
		ink = ink_line(dd, lw, aa);
	}
	return vec2(fill, ink);
}

// Voronoi: x = distancia al borde de la celda, y = azar de la celda.
vec2 voronoi(vec2 x) {
	vec2 n = floor(x);
	vec2 f = fract(x);
	float md = 8.0;
	float md2 = 8.0;
	vec2 best = vec2(0.0);
	for (int j = -1; j <= 1; j++) {
		for (int i = -1; i <= 1; i++) {
			vec2 g = vec2(float(i), float(j));
			vec2 o = hash2(n + g);
			vec2 r = g + o - f;
			float d = dot(r, r);
			if (d < md) {
				md2 = md;
				md = d;
				best = n + g;
			} else if (d < md2) {
				md2 = d;
			}
		}
	}
	return vec2(sqrt(md2) - sqrt(md), hash1(best));
}

void fragment() {
	vec2 tex_size = vec2(textureSize(height_tex, 0));
	vec2 t = 1.5 / tex_size;
	float h = texture(height_tex, UV).r;

	// Sobel 3x3 para el sombreado
	float tl = texture(height_tex, UV + vec2(-t.x, -t.y)).r;
	float tc = texture(height_tex, UV + vec2( 0.0, -t.y)).r;
	float tr = texture(height_tex, UV + vec2( t.x, -t.y)).r;
	float ml = texture(height_tex, UV + vec2(-t.x,  0.0)).r;
	float mr = texture(height_tex, UV + vec2( t.x,  0.0)).r;
	float bl = texture(height_tex, UV + vec2(-t.x,  t.y)).r;
	float bc = texture(height_tex, UV + vec2( 0.0,  t.y)).r;
	float br = texture(height_tex, UV + vec2( t.x,  t.y)).r;

	float dx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
	float dy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
	vec3 normal = normalize(vec3(-dx * relief_strength * 0.25, -dy * relief_strength * 0.25, 1.0));
	float shade = clamp(dot(normal, normalize(sun_dir)), 0.0, 1.0);

	// Altura suavizada (núcleo 1-2-1): costa y curvas sin escalones aunque la capa sea de 8 bits.
	float hs = (tl + 2.0 * tc + tr + 2.0 * ml + 4.0 * h + 2.0 * mr + bl + 2.0 * bc + br) / 16.0;
	float coast_dist = hs - sea_level;
	float fw = max(fwidth(coast_dist), 0.0002);
	// Píxeles del mapa que caben en un píxel de pantalla.
	float texel_px = max(fwidth(UV.x * tex_size.x), 0.0001);

	vec3 color;
	bool land = coast_dist >= 0.0;
	if (!land) {
		float depth = clamp(-coast_dist * 4.0, 0.0, 1.0);
		color = mix(col_shallow_sea, col_deep_sea, pow(depth, 0.65));
		float wave_fade = smoothstep(0.12, 0.0, -coast_dist);
		float wave = smoothstep(0.92, 0.99, sin(-coast_dist * 260.0));
		color = mix(color, col_land_low, wave * wave_fade * 0.22);
	} else {
		float elev = clamp(coast_dist * 2.6, 0.0, 1.0);
		vec3 auto_col = mix(col_land_low, col_land_mid, smoothstep(0.05, 0.45, elev));
		auto_col = mix(auto_col, col_land_high, smoothstep(0.42, 0.78, elev));

		// Biomas: los 4 vecinos mezclados (lindes suaves) con el punto desplazado por ruido (lindes orgánicas).
		vec2 warp = (vec2(vnoise(UV * 90.0), vnoise(UV * 90.0 + 31.7)) - 0.5) * 3.0;
		vec2 bp = UV * tex_size + warp - 0.5;
		vec2 b0 = floor(bp);
		vec2 bf = fract(bp);
		vec3 bc4[4];
		for (int k = 0; k < 4; k++) {
			vec2 o = vec2(float(k % 2), float(k / 2));
			int bid = id_at(biome_tex, (b0 + o + 0.5) / tex_size);
			bc4[k] = bid > 0 ? mix(biome_color(bid), auto_col, smoothstep(0.55, 0.95, elev) * 0.6) : auto_col;
		}
		color = mix(mix(bc4[0], bc4[1], bf.x), mix(bc4[2], bc4[3], bf.x), bf.y);
		color = mix(color, col_snow, smoothstep(0.80, 0.96, elev));

		float slope = 1.0 - normal.z;
		float shadow_factor = mix(1.0, 0.58 + shade * 0.58, smoothstep(0.02, 0.25, slope));
		color *= shadow_factor;

		float contour_val = abs(fract(coast_dist * contour_density - 0.5) - 0.5) / max(fwidth(coast_dist * contour_density), 0.001);
		float contour_line = 1.0 - smoothstep(0.0, 1.4, contour_val);
		color = mix(color, col_ink, contour_line * 0.12);

		if (show_symbols) {
			// Patrones continuos (grietas, hielo, cultivos) según el bioma de este punto.
			int pid = id_at(biome_tex, UV);
			if (pid > 0) {
				vec4 meta = texelFetch(biome_pal, ivec2(pid, 1), 0);
				int pk = int(round(meta.r * 255.0));
				if (pk == 8 || pk == 12 || pk == 9) {
					vec2 vp = UV * tex_size / (symbol_cell * (pk == 9 ? 2.2 : 1.3));
					vec2 v = voronoi(vp);
					float aa_v = fwidth(vp.x);
					float edge = 1.0 - smoothstep(0.04 - aa_v, 0.04 + aa_v, v.x);
					if (pk == 9) {
						float ang = v.y * 3.1416;
						vec2 dir = vec2(cos(ang), sin(ang));
						float stripe = abs(fract(dot(vp, dir) * 5.0) - 0.5);
						float st = 1.0 - smoothstep(0.08, 0.08 + aa_v * 5.0, stripe);
						color *= 0.9 + 0.2 * v.y;
						color = mix(color, col_ink, st * 0.18 + edge * 0.45);
					} else {
						color = mix(color, col_ink, edge * (pk == 12 ? 0.25 : 0.55));
					}
				}
			}
			// Símbolos sueltos: uno por celda (con azar), del bioma que hay en el centro de su celda.
			vec2 gp = UV * tex_size / symbol_cell;
			vec2 ci = floor(gp);
			float rnd = hash1(ci);
			vec2 jit = (hash2(ci + 5.0) - 0.5) * 0.28;
			vec2 center_uv = (ci + 0.5 + jit) * symbol_cell / tex_size;
			int gid = id_at(biome_tex, center_uv);
			float hc = texture(height_tex, center_uv).r;
			if (gid > 0 && hc > sea_level + 0.006) {
				vec4 meta = texelFetch(biome_pal, ivec2(gid, 1), 0);
				int kind = int(round(meta.r * 255.0));
				if (kind != 8 && kind != 9 && kind != 12 && rnd < meta.g) {
					float aa = max(fwidth(gp.x), 0.002);
					vec2 g = glyph(kind, fract(gp) - 0.5 - jit, aa, hash1(ci + 2.0), ci);
					vec3 fill_col = biome_color(gid) * 0.78;
					color = mix(color, fill_col, g.x * 0.95);
					color = mix(color, col_ink, g.y * 0.85);
				}
			}
		}

		if (show_realms) {
			int rid = id_at(realm_tex, UV);
			vec2 px1 = vec2(max(texel_px * 1.1, 0.75)) / tex_size;
			bool edge = false;
			int nb = 0;
			for (int k = 0; k < 4; k++) {
				vec2 o = k == 0 ? vec2(1.0, 0.0) : (k == 1 ? vec2(-1.0, 0.0) : (k == 2 ? vec2(0.0, 1.0) : vec2(0.0, -1.0)));
				int n = id_at(realm_tex, UV + o * px1);
				if (n != rid) {
					edge = true;
					nb = max(nb, n);
				}
			}
			if (rid > 0) {
				vec3 rc = texelFetch(realm_pal, ivec2(rid, 0), 0).rgb;
				float band = 0.0;
				float br_px = max(texel_px * 7.0, 3.0);
				for (int k = 0; k < 8; k++) {
					float ang = float(k) * 0.7853982;
					vec2 o = vec2(cos(ang), sin(ang)) * br_px / tex_size;
					if (id_at(realm_tex, UV + o) != rid) band = 1.0;
				}
				color = mix(color, rc, 0.13 + band * 0.42);
			}
			if (edge && (rid > 0 || nb > 0)) {
				color = mix(color, col_ink, 0.82);
			}
		}
	}

	float coast_ink = 1.0 - smoothstep(0.0, fw * 1.8, abs(coast_dist));
	color = mix(color, col_ink, coast_ink * 0.85);

	float grain = (paper_grain(FRAGCOORD.xy * 0.65) - 0.5) * 0.035;
	COLOR = vec4(color + grain, 1.0);
}
