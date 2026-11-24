<?php
// Verifica tus credenciales sin crear nada ni enviar nada al POS: consulta una suscripción que no existe.
// Si la API responde "no existen suscripciones", tu API KEY y tu firma están bien.

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

$vp = Virtualpos::fromEnv();

try {
    $vp->getSubscription('subsc_00000000-0000-0000-0000-000000000000');
    echo "✔ Credenciales válidas\n";
} catch (VirtualposException $err) {
    if (preg_match('/no existen suscripciones/i', $err->apiMessage)) {
        echo "✔ Credenciales válidas para {$vp->apiUrl()}\n";
    } else {
        fwrite(STDERR, "✘ {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
        exit(1);
    }
}
if (!getenv('VIRTUALPOS_PLAN_ID')) {
    echo "Falta VIRTUALPOS_PLAN_ID: créalo con composer crear-plan\n";
}
