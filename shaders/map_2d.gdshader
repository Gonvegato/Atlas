shader_type canvas_item;

uniform sampler2D height_tex : filter_linear, repeat_disable;
uniform float sea_level = 0.5;
uniform float relief_strength = 16.0;
uniform vec3 sun_dir = vec3(-0.6, -0.5, 0.62);
uniform float contour_density = 28.0;

uniform vec3 col_deep_sea : source_color = vec3(0.24, 0.33, 0.43);
uniform vec3 col_shallow_sea : source_color = vec3(0.42, 0.53, 0.64);
uniform vec3 col_land_low : source_color = vec3(0.95, 0.92, 0.84);
uniform vec3 col_land_mid : source_color = vec3(0.86, 0.82, 0.72);
uniform vec3 col_land_high : source_color = vec3(0.66, 0.63, 0.57);
uniform vec3 col_snow : source_color = vec3(0.98, 0.97, 0.94);
uniform vec3 col_ink : source_color = vec3(0.10, 0.11, 0.13);

float paper_grain(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	float a = fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453);
	float b = fract(sin(dot(i + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453);
	float c = fract(sin(dot(i + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
	float d = fract(sin(dot(i + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void fragment() {
	vec2 t = 2.0 / vec2(textureSize(height_tex, 0));
	float h = texture(height_tex, UV).r;

	// Sobel 3x3 para eliminar escalonado en 8 bits y dar relieve suave
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

	float coast_dist = h - sea_level;
	float fw = max(fwidth(coast_dist), 0.0005);

	vec3 color;
	if (coast_dist < 0.0) {
		float depth = clamp(-coast_dist * 4.0, 0.0, 1.0);
		color = mix(col_shallow_sea, col_deep_sea, pow(depth, 0.65));

		float wave_fade = smoothstep(0.12, 0.0, -coast_dist);
		float wave = smoothstep(0.92, 0.99, sin(-coast_dist * 260.0));
		color = mix(color, col_land_low, wave * wave_fade * 0.22);
	} else {
		float elev = clamp(coast_dist * 2.6, 0.0, 1.0);
		color = mix(col_land_low, col_land_mid, smoothstep(0.05, 0.45, elev));
		color = mix(color, col_land_high, smoothstep(0.42, 0.78, elev));
		color = mix(color, col_snow, smoothstep(0.76, 0.94, elev));

		float slope = 1.0 - normal.z;
		float shadow_factor = mix(1.0, 0.58 + shade * 0.58, smoothstep(0.02, 0.25, slope));
		color *= shadow_factor;

		float contour_val = abs(fract(coast_dist * contour_density - 0.5) - 0.5) / max(fwidth(coast_dist * contour_density), 0.001);
		float contour_line = 1.0 - smoothstep(0.0, 1.4, contour_val);
		color = mix(color, col_ink, contour_line * 0.14);
	}

	float coast_ink = 1.0 - smoothstep(0.0, fw * 1.8, abs(coast_dist));
	color = mix(color, col_ink, coast_ink * 0.85);

	float grain = (paper_grain(FRAGCOORD.xy * 0.65) - 0.5) * 0.035;
	COLOR = vec4(color + grain, 1.0);
}
