shader_type canvas_item;

// El mapa 2D: relieve (Sobel, sombreado, curvas, costa a tinta), biomas con sus símbolos y reinos con sus
// fronteras. Las tres capas tienen el mismo tamaño; biomas y reinos son identificadores (filtro nearest).

uniform sampler2D height_tex : filter_linear, repeat_disable;
uniform sampler2D biome_tex : filter_nearest, repeat_disable;
uniform sampler2D realm_tex : filter_nearest, repeat_disable;
uniform sampler2D area_tex : filter_nearest, repeat_disable;
// Regiones con nombre: su contorno solo se ve al editarlas (sección Regiones).
uniform bool show_area_edges = false;
uniform int selected_area = 0;
uniform vec3 area_col : source_color = vec3(0.36, 0.47, 0.58);
// Paletas: biomas (N × 2: color; símbolo y densidad) y reinos (64 × 1: color, a = existe).
uniform sampler2D biome_pal : filter_nearest, repeat_disable;
uniform sampler2D realm_pal : filter_nearest, repeat_disable;

uniform float sea_level = 0.5;
uniform float relief_strength = 16.0;
uniform vec3 sun_dir = vec3(-0.6, -0.5, 0.62);
uniform float contour_density = 28.0;
uniform bool show_symbols = true;
uniform bool show_realms = true;
// Lado de la celda de los símbolos, en píxeles del mapa (main lo ajusta al zoom: lejos, celdas grandes; cerca,
// pequeñas, para que los árboles midan en pantalla más o menos lo mismo y haya más cuanto más te acercas).
uniform float symbol_cell = 26.0;
uniform float tree_cell = 16.0;
// Dirección de la luz en el plano (para iluminar las copas).
uniform vec2 light2d = vec2(-0.7, -0.7);

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

// Patrones de suelo (uno por celda): x = relleno, y = tinta.
vec2 glyph(int kind, vec2 p, float aa, float rnd, vec2 cell_id) {
	float lw = max(0.032, aa * 0.8);
	float ink = 0.0;
	if (kind == 4) {
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
	}
	return vec2(0.0, ink);
}

bool is_tree(int k) {
	return k == 1 || k == 2 || k == 3 || k == 10 || k == 11 || k == 13 || (k >= 14 && k <= 20);
}

float sd_ellipse(vec2 p, vec2 c, vec2 r) {
	vec2 q = (p - c) / r;
	return (length(q) - 1.0) * min(r.x, r.y);
}

// Un árbol con la base del tronco en (0, 0), y hacia abajo, en unidades de celda.
// x = relleno (copa), y = tinta, z = luz de la copa (0,7..1,2), w = detalle (flores, motas, tronco claro).
vec4 tree(int kind, vec2 p, float aa, float rnd, vec2 L) {
	float lw = max(0.045, aa * 0.9);
	float d = 1e3;
	vec2 cc = vec2(0.0, -0.55);
	float trunk = 1e3;
	float extra = 0.0;
	float detail = 0.0;
	if (kind == 17) {
		kind = rnd < 0.5 ? 2 : 1;
	}
	if (kind == 3 && rnd > 0.78) {
		kind = 16;
	}
	if (kind == 1 || kind == 20 || kind == 14) {
		float j = rnd - 0.5;
		d = sd_circle(p - vec2(0.0, -0.55), 0.34 + j * 0.06);
		d = min(d, sd_circle(p - vec2(-0.21, -0.45 + j * 0.05), 0.25));
		d = min(d, sd_circle(p - vec2(0.21, -0.47 - j * 0.05), 0.26));
		d = min(d, sd_circle(p - vec2(0.03 * j, -0.78), 0.24));
		trunk = sd_seg(p, vec2(0.0, -0.3), vec2(0.0, 0.0)) - 0.035;
		if (kind == 20) {
			for (int i = 0; i < 4; i++) {
				vec2 fp = vec2(-0.2, -0.75) + hash2(vec2(rnd * 91.0, float(i) * 3.7)) * vec2(0.4, 0.4);
				detail = max(detail, inside(sd_circle(p - fp, 0.05), aa));
			}
		}
		if (kind == 14) {
			extra = min(sd_seg(p, vec2(0.0, -0.12), vec2(-0.2, 0.06)), sd_seg(p, vec2(0.0, -0.12), vec2(0.2, 0.06)));
		}
	} else if (kind == 2) {
		float w1 = 0.23 + (rnd - 0.5) * 0.06;
		d = sd_tri(p - vec2(0.0, -1.08), vec2(w1, 0.62));
		d = min(d, sd_tri(p - vec2(0.0, -0.82), vec2(w1 + 0.07, 0.66)));
		trunk = sd_seg(p, vec2(0.0, -0.16), vec2(0.0, 0.0)) - 0.035;
		cc = vec2(0.0, -0.6);
	} else if (kind == 3) {
		d = sd_circle(p - vec2(0.0, -0.62), 0.4);
		d = min(d, sd_circle(p - vec2(-0.3, -0.5), 0.28));
		d = min(d, sd_circle(p - vec2(0.31, -0.52), 0.28));
		d = min(d, sd_circle(p - vec2(-0.12, -0.9), 0.26));
		d = min(d, sd_circle(p - vec2(0.16, -0.88), 0.25));
		trunk = sd_seg(p, vec2(0.0, -0.3), vec2(0.0, 0.0)) - 0.04;
		cc = vec2(0.0, -0.65);
	} else if (kind == 16) {
		vec2 top = vec2(0.1, -0.78);
		trunk = sd_seg(p, vec2(0.0, 0.0), top) - 0.035;
		for (int i = 0; i < 6; i++) {
			float ang = -2.9 + float(i) * 0.6 + rnd * 0.3;
			vec2 tip = top + vec2(cos(ang), sin(ang) + 0.45) * 0.36;
			d = min(d, sd_seg(p, top, tip) - 0.05 * (1.0 - clamp(length(p - top) / 0.36, 0.0, 1.0)) - 0.012);
		}
		cc = top;
	} else if (kind == 15) {
		d = sd_ellipse(p, vec2(0.0, -0.66), vec2(0.46, 0.13));
		trunk = min(sd_seg(p, vec2(0.0, 0.0), vec2(0.0, -0.4)), min(sd_seg(p, vec2(0.0, -0.4), vec2(-0.14, -0.6)), sd_seg(p, vec2(0.0, -0.4), vec2(0.15, -0.6)))) - 0.03;
		cc = vec2(0.0, -0.66);
	} else if (kind == 18) {
		d = sd_ellipse(p, vec2(0.0, -0.64), vec2(0.19, 0.36));
		trunk = sd_seg(p, vec2(0.0, -0.3), vec2(0.0, 0.0)) - 0.045;
		cc = vec2(0.0, -0.64);
	} else if (kind == 19) {
		d = sd_circle(p - vec2(-0.13, -0.16), 0.17);
		d = min(d, sd_circle(p - vec2(0.12, -0.17), 0.18));
		d = min(d, sd_circle(p - vec2(0.0, -0.3), 0.18));
		cc = vec2(0.0, -0.2);
	} else if (kind == 11) {
		d = max(sd_ellipse(p, vec2(0.0, -0.45), vec2(0.36, 0.3)), p.y + 0.45);
		trunk = sd_seg(p, vec2(0.0, -0.45), vec2(0.0, 0.0)) - 0.07;
		cc = vec2(0.0, -0.55);
		for (int i = 0; i < 3; i++) {
			vec2 fp = vec2(-0.2 + float(i) * 0.2, -0.58 + abs(float(i) - 1.0) * 0.06);
			detail = max(detail, inside(sd_circle(p - fp, 0.055), aa));
		}
	} else if (kind == 10) {
		d = (abs(p.x) / 0.12 + abs(p.y + 0.45) / 0.45 - 1.0) * 0.11;
		d = min(d, (abs(p.x + 0.2) / 0.09 + abs(p.y + 0.28) / 0.28 - 1.0) * 0.08);
		d = min(d, (abs(p.x - 0.19) / 0.09 + abs(p.y + 0.33) / 0.33 - 1.0) * 0.08);
		cc = vec2(0.0, -0.4);
		extra = sd_seg(p, vec2(0.0, -0.9), vec2(0.0, 0.0));
	} else if (kind == 13) {
		extra = min(sd_seg(p, vec2(0.0, 0.0), vec2(0.0, -0.75)), min(sd_seg(p, vec2(0.0, -0.4), vec2(-0.22, -0.66)), sd_seg(p, vec2(0.0, -0.5), vec2(0.2, -0.74))));
		extra = min(extra, sd_seg(p, vec2(-0.12, -0.53), vec2(-0.2, -0.45)));
	}
	float fill = inside(d, aa);
	float ink = ink_line(abs(d), lw, aa);
	float t_fill = inside(trunk, aa) * (1.0 - fill);
	ink = max(ink, ink_line(abs(trunk), lw * 0.8, aa) * (1.0 - fill));
	if (kind == 13) {
		ink = max(ink, ink_line(extra, lw * 1.2, aa));
	} else if (kind == 14) {
		ink = max(ink, ink_line(extra, lw * 0.8, aa) * (1.0 - fill));
	} else if (kind == 10) {
		ink = max(ink, ink_line(extra, lw * 0.6, aa) * fill);
	}
	// Luz: más clara hacia el sol, sombra al lado contrario (en coníferas, la mitad de la sombra marcada).
	vec2 n = p - cc;
	float light = 0.95 + 0.28 * clamp(dot(normalize(n + vec2(0.0001)), L), -1.0, 1.0);
	if (kind == 2) {
		light = (p.x * L.x > 0.0) ? 1.12 : 0.80;
	}
	// El tronco cuenta como tinta con algo de relleno oscuro.
	ink = max(ink, t_fill * 0.85);
	if (kind == 18) {
		detail = max(detail, t_fill);
	}
	return vec4(fill, ink, light, detail * fill + (kind == 18 ? t_fill : 0.0));
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
	// Las muestras del sombreado, a la distancia que abarca un píxel de pantalla (nunca menos de 1,5 píxeles del
	// mapa): de lejos un píxel cubre varios del mapa y, con la distancia fija, el relieve hacía moiré y parpadeaba.
	float foot = max(1.5, fwidth(UV.x * tex_size.x) * 1.15);
	vec2 t = foot / tex_size;
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
	// Pendiente por unidad de distancia: el mismo sombreado aunque cambie la separación de las muestras.
	float slope_k = relief_strength * 0.25 * (1.5 / foot);
	vec3 normal = normalize(vec3(-dx * slope_k, -dy * slope_k, 1.0));
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

		// Hachures: en los escarpes (cañones, acantilados), trazos de tinta a favor de la pendiente cada ~4 px de
		// pantalla, como en los mapas grabados. Pegados al mapa (no a la pantalla) para que no bailen al moverlo.
		float steep = smoothstep(0.32, 0.62, slope);
		if (steep > 0.0) {
			vec2 gdir = normalize(vec2(dx, dy) + vec2(1e-6));
			vec2 ps = UV * tex_size / max(fwidth(UV.x * tex_size.x), 0.2);
			float along = dot(ps, vec2(-gdir.y, gdir.x));
			float hl = abs(fract(along / 4.0) - 0.5) * 4.0;
			float hline = 1.0 - smoothstep(0.45, 1.15, hl);
			color = mix(color, col_ink, hline * steep * 0.5);
		}

		float contour_val = abs(fract(coast_dist * contour_density - 0.5) - 0.5) / max(fwidth(coast_dist * contour_density), 0.001);
		float contour_line = 1.0 - smoothstep(0.0, 1.4, contour_val);
		// Si las curvas quedan a menos de ~4 px unas de otras, se desvanecen (si no, hacen moiré al alejar).
		contour_line *= smoothstep(2.0, 5.0, 1.0 / max(fwidth(coast_dist * contour_density), 0.0001));
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
			// Patrones de suelo sueltos (hierba, dunas, punteado, pantano): uno por celda, del bioma del centro.
			vec2 gp = UV * tex_size / symbol_cell;
			vec2 ci = floor(gp);
			float rnd = hash1(ci);
			vec2 jit = (hash2(ci + 5.0) - 0.5) * 0.28;
			vec2 center_uv = (ci + 0.5 + jit) * symbol_cell / tex_size;
			int gid = id_at(biome_tex, center_uv);
			if (gid > 0 && texture(height_tex, center_uv).r > sea_level + 0.006) {
				vec4 meta = texelFetch(biome_pal, ivec2(gid, 1), 0);
				int kind = int(round(meta.r * 255.0));
				if (kind >= 4 && kind <= 7 && rnd < meta.g) {
					float aa = max(fwidth(gp.x), 0.002);
					vec2 g = glyph(kind, fract(gp) - 0.5 - jit, aa, hash1(ci + 2.0), ci);
					color = mix(color, col_ink, g.y * 0.8);
				}
			}

			// Bosques: un árbol por celda como mucho, con la base abajo y la copa hacia arriba. Se recorren las
			// 3×3 celdas vecinas de arriba abajo para que los de delante tapen a los de detrás.
			vec2 tq = UV * tex_size / tree_cell;
			vec2 tb = floor(tq);
			float taa = max(fwidth(tq.x), 0.002);
			int here = id_at(biome_tex, UV);
			bool near_forest = here > 0 && is_tree(int(round(texelFetch(biome_pal, ivec2(here, 1), 0).r * 255.0)));
			if (!near_forest) {
				// Un borde de bosque puede asomar desde la celda de abajo.
				int below = id_at(biome_tex, (tb + vec2(0.5, 1.5)) * tree_cell / tex_size);
				near_forest = below > 0 && is_tree(int(round(texelFetch(biome_pal, ivec2(below, 1), 0).r * 255.0)));
			}
			if (near_forest) {
				// Suelo del bosque: el color del bioma algo más oscuro y moteado.
				if (here > 0) {
					float mott = vnoise(UV * tex_size / (tree_cell * 1.7));
					color *= 0.86 + mott * 0.12;
				}
				for (int dj = -1; dj <= 1; dj++) {
					for (int di = -1; di <= 1; di++) {
						vec2 cell = tb + vec2(float(di), float(dj));
						float r = hash1(cell * 1.31 + 7.0);
						vec2 base = cell + vec2(0.5, 0.86) + (hash2(cell + 11.0) - 0.5) * vec2(0.55, 0.22);
						vec2 buv = base * tree_cell / tex_size;
						int tid = id_at(biome_tex, buv);
						if (tid <= 0) continue;
						vec4 m1 = texelFetch(biome_pal, ivec2(tid, 1), 0);
						int tk = int(round(m1.r * 255.0));
						if (!is_tree(tk) || r > m1.g) continue;
						if (texture(height_tex, buv).r <= sea_level + 0.004) continue;
						float size = m1.a * 2.0 * (0.82 + hash1(cell + 3.0) * 0.36);
						vec2 lp = (tq - base) / size;
						if (lp.y > 0.2 || lp.y < -1.25 || abs(lp.x) > 0.6) continue;
						vec4 tr = tree(tk, lp, taa / size, hash1(cell + 5.0), light2d);
						if (tr.x + tr.y <= 0.0) continue;
						vec3 c1 = biome_color(tid);
						vec3 c2 = texelFetch(biome_pal, ivec2(tid, 2), 0).rgb;
						float l2 = dot(c2, vec3(0.299, 0.587, 0.114));
						c2 = mix(mix(c2, vec3(l2), biome_desat), biome_tint * (0.55 + 0.55 * l2), biome_tint_amt);
						bool detail_kind = (tk == 20 || tk == 11 || tk == 18);
						vec3 crown = detail_kind ? c1 : mix(c1, c2, hash1(cell + 9.0) * m1.b);
						crown *= tr.z * (0.92 + hash1(cell + 13.0) * 0.16);
						color = mix(color, crown, tr.x);
						if (detail_kind) {
							color = mix(color, c2, tr.w);
						}
						color = mix(color, col_ink, tr.y * 0.9);
					}
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

	if (show_area_edges) {
		int aid = id_at(area_tex, UV);
		vec2 ap = vec2(max(texel_px * 1.2, 0.75)) / tex_size;
		bool aedge = false;
		int anb = 0;
		for (int k = 0; k < 4; k++) {
			vec2 o = k == 0 ? vec2(1.0, 0.0) : (k == 1 ? vec2(-1.0, 0.0) : (k == 2 ? vec2(0.0, 1.0) : vec2(0.0, -1.0)));
			int n = id_at(area_tex, UV + o * ap);
			if (n != aid) {
				aedge = true;
				anb = max(anb, n);
			}
		}
		if (aid > 0 && aid == selected_area) {
			color = mix(color, area_col, 0.16);
		}
		if (aedge && (aid > 0 || anb > 0)) {
			float dash = step(0.45, fract((FRAGCOORD.x + FRAGCOORD.y) * 0.07));
			color = mix(color, area_col, 0.95 * dash);
		}
	}

	float coast_ink = 1.0 - smoothstep(0.0, fw * 1.8, abs(coast_dist));
	color = mix(color, col_ink, coast_ink * 0.85);

	float grain = (paper_grain(FRAGCOORD.xy * 0.65) - 0.5) * 0.035;
	COLOR = vec4(color + grain, 1.0);
}
