<?php
// Verifica tus credenciales y las dos firmas sin crear nada: consulta una suscripción que no existe.
// Si la API responde "no existe" / "no se encontró", tu API KEY, tu SECRET KEY y tus firmas están bien.

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

$vp = Virtualpos::fromEnv();
$fakeId = 'subsc_00000000-0000-0000-0000-000000000000';

$checks = [
    ['Firma {"api_key"} (planes, suscripciones y cargos)', fn () => $vp->getSubscription($fakeId), '/no existen suscripciones/i'],
    ['Firma {"ciat"} (Pagar y suscribir)', fn () => $vp->getPayAndSubscribe($fakeId), '/no se encontr/i'],
];

$ok = true;
foreach ($checks as [$name, $call, $notFound]) {
    try {
        $call();
        echo "✔ $name\n";
    } catch (VirtualposException $err) {
        if (preg_match($notFound, $err->apiMessage)) {
            echo "✔ $name\n";
        } else {
            $ok = false;
            fwrite(STDERR, "✘ $name: {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
        }
    }
}
if (!$ok) {
    exit(1);
}
echo "Credenciales válidas para {$vp->apiUrl()}\n";
if (!getenv('VIRTUALPOS_PLAN_ID')) {
    echo "Falta VIRTUALPOS_PLAN_ID: créalo con composer crear-plan\n";
}
