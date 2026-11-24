<?php
// Carga las dependencias y las variables del archivo .env.

require __DIR__ . '/../vendor/autoload.php';

// Formato NOMBRE=valor, una por línea; las líneas que empiezan con # son comentarios.
// (parse_ini_file no sirve aquí: no acepta # como comentario y falla con caracteres como "(" o "|".)
$envFile = __DIR__ . '/../.env';
if (is_file($envFile)) {
    foreach (file($envFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line === '' || $line[0] === '#' || !str_contains($line, '=')) {
            continue;
        }
        [$name, $value] = array_map('trim', explode('=', $line, 2));
        $value = preg_replace('/^(["\'])(.*)\1$/', '$2', $value); // admite valores entre comillas
        if (getenv($name) === false) {
            putenv("$name=$value");
        }
    }
}
