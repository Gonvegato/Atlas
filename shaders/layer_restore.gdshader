shader_type canvas_item;

// Escribe una región guardada (un canal) tal cual en la capa: deshacer, rehacer, cargar y ampliar.
void fragment() {
	float v = texture(TEXTURE, UV).r;
	COLOR = vec4(v, v, v, 1.0);
}
