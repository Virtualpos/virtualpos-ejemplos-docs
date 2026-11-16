<?php
// Crea el plan mensual una sola vez y muestra su ID para que lo guardes en VIRTUALPOS_PLAN_ID.
// https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

try {
    $plan = Virtualpos::fromEnv()->createPlan([
        'name' => 'Plan mensual',
        'description' => 'Suscripción mensual de ejemplo',
        'type' => 'MONTO_VARIABLE', // Pagar y suscribir exige este tipo: tú informas el monto de cada cargo
        'currency' => 'CLP',
        'amount' => 0,
        'frequency_type' => 'Mensual',
        'num_charges' => 0,
        'trial_days' => 0,
        'fixed_amount_day_charge' => '0',
        'return_url' => getenv('PUBLIC_URL') ?: 'http://localhost:8000', // texto plano, no Base64
    ])['plan'];
    echo "✔ Plan creado: {$plan['name']} ({$plan['type']}, {$plan['frequency_type']})\n";
    echo "  Agrega esta línea a tu .env:\n  VIRTUALPOS_PLAN_ID={$plan['id']}\n";
} catch (VirtualposException $err) {
    fwrite(STDERR, "✘ {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
    exit(1);
}
