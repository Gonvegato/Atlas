shader_type canvas_item;

// Cambia un identificador por otro en toda la capa (p. ej., borrar un reino: su territorio pasa a 0).
uniform sampler2D snap : filter_nearest, repeat_disable;
uniform int from_id = 1;
uniform int to_id = 0;

void fragment() {
	float v = texture(snap, UV).r;
	int id = int(round(v * 255.0));
	float o = (id == from_id) ? float(to_id) / 255.0 : v;
	COLOR = vec4(o, o, o, 1.0);
}
