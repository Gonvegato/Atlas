shader_type spatial;
// Como vegetation.gdshader, para modelos con textura (Quaternius, KayKit): hojas recortadas por transparencia
// (alpha scissor). Solo se usa en esos modelos: el recorte es más caro en móviles que lo opaco.
// part: 0 = textura tal cual · 1 = hojas: el color del bioma (instancia) con el detalle de luces de la textura ·
// 2 = roca: textura por el tinte de la instancia.
render_mode cull_disabled, diffuse_lambert_wrap, specular_disabled, shadows_disabled;

uniform sampler2D tex : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform vec3 albedo : source_color = vec3(1.0);
uniform int part = 0;
uniform float scissor = 0.5;
uniform float fill_light = 0.32;

varying vec3 v_tint;

void vertex() {
	v_tint = INSTANCE_CUSTOM.rgb;
}

void fragment() {
	vec4 t = texture(tex, UV);
	vec3 c = albedo * t.rgb;
	if (part == 1) {
		float l = dot(t.rgb, vec3(0.299, 0.587, 0.114));
		c = COLOR.rgb * clamp(l * 1.8, 0.45, 1.35);
	} else if (part == 2) {
		c = c * v_tint;
	}
	ALBEDO = c;
	ALPHA = t.a;
	ALPHA_SCISSOR_THRESHOLD = scissor;
	ROUGHNESS = 1.0;
	EMISSION = c * fill_light;
}
