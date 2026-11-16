<?php
// Verifica tus credenciales sin crear nada: consulta un check-in que no existe.
// Si la API responde "No existe un checkin", tu API KEY, tu SECRET KEY y tu firma están bien.

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

$vp = Virtualpos::fromEnv();

try {
    $vp->getCheckin('chk_00000000-0000-0000-0000-000000000000');
    echo "✔ Credenciales válidas\n";
} catch (VirtualposException $err) {
    if (preg_match('/no existe un checkin/i', $err->apiMessage)) {
        echo "✔ Credenciales válidas para {$vp->apiUrl()}\n";
        exit(0);
    }
    fwrite(STDERR, "✘ {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
    exit(1);
}
