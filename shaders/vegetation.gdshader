shader_type spatial;
// Árboles y plantas del 3D (MultiMesh), con luz envolvente para que el lado en sombra no se quede negro, sin brillos
// y algo de luz de relleno propia (la luz con sombra en Compatibility se pinta con poca energía; ver main.tscn).
// part: 0 = conserva el color del modelo (troncos, flores, setas) · 1 = hojas: el color de la instancia (el del
// bioma) · 2 = roca/tierra: el color del modelo por el tinte de la instancia (INSTANCE_CUSTOM) · 3 = corteza:
// el salmón del kit pasado a marrón.
render_mode cull_disabled, diffuse_lambert_wrap, specular_disabled;

uniform vec3 albedo : source_color = vec3(0.4, 0.6, 0.3);
uniform int part = 0;
uniform float leaf_shade = 1.0;
uniform float fill_light = 0.32;

varying vec3 v_tint;

void vertex() {
	v_tint = INSTANCE_CUSTOM.rgb;
}

void fragment() {
	vec3 c = albedo;
	if (part == 1) {
		c = COLOR.rgb * leaf_shade;
	} else if (part == 2) {
		c = albedo * v_tint;
	} else if (part == 3) {
		c = albedo * vec3(0.52, 0.42, 0.34);
	}
	ALBEDO = c;
	ROUGHNESS = 1.0;
	EMISSION = c * fill_light;
}
