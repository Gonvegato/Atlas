shader_type canvas_item;

// Renumera identificadores (reinos, regiones) dentro de un rectángulo de la capa con una tabla de 256 entradas:
// lo usa «unir mapas» para que los reinos del mapa que se une no choquen con los del otro.
uniform sampler2D snap : filter_nearest, repeat_disable;
uniform sampler2D lut : filter_nearest, repeat_disable;
uniform vec2 rect_min = vec2(0.0);
uniform vec2 rect_size = vec2(1.0);
uniform vec2 zone_min = vec2(0.0);
uniform vec2 zone_max = vec2(1.0);

void fragment() {
	vec2 uv = rect_min + UV * rect_size;
	float v = texture(snap, uv).r;
	if (uv.x >= zone_min.x && uv.y >= zone_min.y && uv.x < zone_max.x && uv.y < zone_max.y) {
		int id = int(round(v * 255.0));
		v = texelFetch(lut, ivec2(id, 0), 0).r;
	}
	COLOR = vec4(v, v, v, 1.0);
}
