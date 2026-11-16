<?php
// Verifica tus credenciales sin crear nada: consulta un pago que no existe.
// Si la API responde "no existe transacción", tu API KEY, tu SECRET KEY y tu firma están bien.

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

$vp = Virtualpos::fromEnv();

try {
    $vp->getPayment('0000000000000000');
    echo "✔ Credenciales válidas\n";
} catch (VirtualposException $err) {
    if (preg_match('/no existe (la orden|transacci)/i', $err->apiMessage)) {
        echo "✔ Credenciales válidas para {$vp->apiUrl()}\n";
        exit(0);
    }
    fwrite(STDERR, "✘ {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
    exit(1);
}
