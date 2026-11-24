shader_type canvas_item;
render_mode blend_disabled;

// El fundido entre biomas, precalculado: se pinta una vez (cuando cambian los biomas, el estilo o el radio) en una
// textura a 1/4 del mapa, y el mapa 2D la lee con una muestra en vez de 13 por píxel y fotograma.
// Salida: rgb = suma de los colores de bioma × su peso (ya multiplicada por la presencia), a = presencia (0 = el color
// automático de la altura).

uniform sampler2D biome_tex : filter_nearest, repeat_disable;
uniform sampler2D biome_pal : filter_nearest, repeat_disable;
uniform vec2 tex_size = vec2(2048.0);
uniform float biome_blend = 16.0;
uniform float biome_desat = 0.0;
uniform vec3 biome_tint : source_color = vec3(0.95, 0.92, 0.84);
uniform float biome_tint_amt = 0.0;

int id_at(vec2 uv) {
	return int(round(texture(biome_tex, uv).r * 255.0));
}

vec3 biome_color(int id) {
	vec3 c = texelFetch(biome_pal, ivec2(id, 0), 0).rgb;
	float l = dot(c, vec3(0.299, 0.587, 0.114));
	c = mix(c, vec3(l), biome_desat);
	return mix(c, biome_tint * (0.55 + 0.55 * l), biome_tint_amt);
}

void fragment() {
	vec2 bc = UV * tex_size;
	vec3 acc = vec3(0.0);
	float pres = 0.0;
	float wsum = 0.0;
	for (int k = 0; k < 25; k++) {
		float rk = k == 0 ? 0.0 : sqrt(float(k) / 24.0);
		float ak = float(k) * 2.3999632;
		vec2 sp = bc + vec2(cos(ak), sin(ak)) * rk * biome_blend;
		int bid = id_at(sp / tex_size);
		float wk = 1.0 - rk * rk * 0.75;
		if (bid > 0) {
			acc += biome_color(bid) * wk;
			pres += wk;
		}
		wsum += wk;
	}
	COLOR = vec4(acc / wsum, pres / wsum);
}
