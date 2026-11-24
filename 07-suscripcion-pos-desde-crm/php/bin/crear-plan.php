<?php
// Crea una sola vez el plan que ofrecerás en el POS y muestra su ID para que lo guardes en VIRTUALPOS_PLAN_ID.
// https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

try {
    $plan = Virtualpos::fromEnv()->createPlan([
        'name' => 'Plan mensual',
        'description' => 'Suscripción mensual de ejemplo',
        'type' => 'MONTO_FIJO',
        'currency' => 'CLP',
        'amount' => 9990,
        'frequency_type' => 'Mensual',
        'num_charges' => 12,
        'trial_days' => 0,
        'fixed_amount_day_charge' => '05',
        'return_url' => getenv('PUBLIC_URL') ?: 'http://localhost:8000', // texto plano, no Base64
        'show_in_terminal' => 'T', // sin esto, el POS rechaza la suscripción (E-PTT-003)
        'shipping_address' => 'F', // los planes con dirección de despacho no se pueden suscribir en el POS
    ])['plan'];
    echo "✔ Plan creado: {$plan['name']} ({$plan['type']}, {$plan['frequency_type']})\n";
    echo "  Agrega esta línea a tu .env:\n  VIRTUALPOS_PLAN_ID={$plan['id']}\n";
} catch (VirtualposException $err) {
    fwrite(STDERR, "✘ {$err->getMessage()}\n" . ($err->hint ? "  {$err->hint}\n" : ''));
    exit(1);
}
