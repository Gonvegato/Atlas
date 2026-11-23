// Azar por casilla con enteros (exacto en cualquier GPU).
//
// El hash de siempre (fract(p * 123.34 ...)) multiplica la casilla por números grandes y se queda con los decimales:
// en el móvil, el compilador de la GPU reordena esas cuentas y el mismo punto de la rejilla salía con un valor distinto
// visto desde una casilla o desde la vecina. El ruido tenía escalones rectos en los bordes de casilla (rayas en el
// relieve, en varias direcciones porque cada octava va girada). Con enteros de 32 bits (highp: en los shaders de
// Godot para móvil los enteros van por defecto en precisión media) el resultado es idéntico en todas partes.

float hash_cell(vec2 cell, float salt) {
	highp ivec2 c = ivec2(floor(cell + 0.5));
	highp int s = int(floor(salt + 0.5));
	highp uint h = uint(c.x) * 2376512323u;
	h ^= uint(c.y) * 3625334849u;
	h ^= uint(s) * 3407524639u;
	h ^= h >> 15u;
	h *= 739982445u;
	h ^= h >> 12u;
	h *= 695872825u;
	h ^= h >> 15u;
	return float(h >> 8u) * (1.0 / 16777216.0);
}

// Ruido de valor (0-1) sobre la rejilla de enteros, con interpolación suave.
float value_noise(vec2 p, float salt) {
	vec2 i = floor(p);
	vec2 f = p - i;
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash_cell(i, salt), hash_cell(i + vec2(1.0, 0.0), salt), u.x),
		mix(hash_cell(i + vec2(0.0, 1.0), salt), hash_cell(i + vec2(1.0, 1.0), salt), u.x), u.y);
}
