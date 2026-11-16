<?php
// Carga las dependencias y las variables del archivo .env.

require __DIR__ . '/../vendor/autoload.php';

$envFile = __DIR__ . '/../.env';
if (is_file($envFile)) {
    foreach (parse_ini_file($envFile, false, INI_SCANNER_RAW) ?: [] as $name => $value) {
        if (getenv($name) === false) {
            putenv("$name=$value");
        }
    }
}
