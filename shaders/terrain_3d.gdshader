shader_type spatial;
render_mode cull_back, diffuse_lambert, specular_schlick_ggx;

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

void vertex() {
	float h = texture(height_tex, UV).r;
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
		ALBEDO = mix(water, vec3(0.95, 0.96, 0.97), foam * 0.7);
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
		NORMAL = normalize((VIEW_MATRIX * MODEL_MATRIX * vec4(n_obj, 0.0)).xyz);

		float elev = clamp(coast_dist * 2.6, 0.0, 1.0);
		float slope = 1.0 - n_obj.y;

		vec3 land = mix(col_land_low, col_land_mid, smoothstep(0.08, 0.55, elev));
		int bid = id_at(biome_tex, UV);
		if (bid > 0) {
			land = mix(biome_color(bid), land, smoothstep(0.55, 0.95, elev) * 0.6);
		}
		if (show_realms) {
			int rid = id_at(realm_tex, UV);
			if (rid > 0) {
				land = mix(land, texelFetch(realm_pal, ivec2(rid, 0), 0).rgb, 0.22);
			}
		}
		land = mix(land, col_cliff, smoothstep(0.18, 0.52, slope));
		land = mix(land, col_snow, smoothstep(0.68, 0.90, elev) * (1.0 - smoothstep(0.35, 0.65, slope)));

		ALBEDO = land;
		ROUGHNESS = 0.92;
		METALLIC = 0.0;
		// Papel mate: sin esto, a contraluz el Fresnel rasante quema el primer plano a blanco puro.
		SPECULAR = 0.08;
	}
}
