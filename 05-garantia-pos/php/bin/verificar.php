<?php
// Verifica tu API key de POS Cloud to Cloud sin crear operaciones: lista tu última operación.

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\PosException;
use Ejemplo\VirtualposPos;

try {
    $items = VirtualposPos::fromEnv()->listOperations(1)['items'];
    echo '✔ API key válida en ' . getenv('VIRTUALPOS_POS_API_URL') . PHP_EOL;
    echo $items ? "  Última operación: {$items[0]['externalPaymentId']} ({$items[0]['status']})" . PHP_EOL : '  Aún no tienes operaciones.' . PHP_EOL;
    echo getenv('VIRTUALPOS_POS_SERIAL') ? '  Serial del POS: ' . getenv('VIRTUALPOS_POS_SERIAL') . PHP_EOL : '✘ Falta VIRTUALPOS_POS_SERIAL en tu .env' . PHP_EOL;
    echo getenv('VIRTUALPOS_WEBHOOK_SECRET') ? '  Secreto de webhook configurado.' . PHP_EOL : '! Falta VIRTUALPOS_WEBHOOK_SECRET: no podrás verificar webhooks.' . PHP_EOL;
} catch (PosException $e) {
    fwrite(STDERR, "✘ {$e->getMessage()}" . PHP_EOL . ($e->hint ? "  {$e->hint}" . PHP_EOL : ''));
    exit(1);
}
