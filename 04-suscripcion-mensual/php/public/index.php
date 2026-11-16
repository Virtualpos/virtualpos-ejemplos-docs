<?php
// Guía: Cobrar una suscripción mensual
// https://docs.virtualpos.cl/guias/suscripcion-mensual/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Subscriptions;
use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

const MONTHLY_AMOUNT = 9990;

$vp = Virtualpos::fromEnv();
$planId = getenv('VIRTUALPOS_PLAN_ID') ?: '';
$publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

try {
    if ($planId === '') {
        throw new \RuntimeException('Configura VIRTUALPOS_PLAN_ID en tu archivo .env (créalo con composer crear-plan)');
    }

    // 1. Página de tu sitio con el botón para suscribirse.
    if ($method === 'GET' && $path === '/') {
        $rows = '';
        foreach (Subscriptions::all() as $s) {
            $rows .= '<li><a href="/suscripciones/' . e($s['subscriptionId']) . '">' . e($s['customerId']) . '</a>: ' . e($s['status']) . '</li>';
        }
        page('Suscripción de ejemplo', '<p>Plan mensual por <strong>$9.990</strong>. El primer mes se paga al suscribirse.</p>
            <form method="post" action="/suscribir"><button>Suscribirme</button></form>' .
            ($rows ? "<h2>Suscripciones</h2><ul>$rows</ul>" : ''));
    }

    // 2. Inicia Pagar y suscribir y redirige al cliente al formulario de Virtualpos.
    elseif ($method === 'POST' && $path === '/suscribir') {
        $customerId = 'CLI-' . (int) (microtime(true) * 1000);
        $res = $vp->startPayAndSubscribe([
            'email' => strtolower($customerId) . '@example.com', // en tu sistema, el email de tu cliente
            'social_id' => '11111111-1',
            'first_name' => 'Ana',
            'last_name' => 'Pérez',
            'phone_number' => '56912345678',
            'plan_id' => $planId,
            'service_id' => $customerId,
            'channel' => 'WEB',
            'activation_amount' => MONTHLY_AMOUNT,
            'payment_description' => 'Primer mes',
            'merchant_internal_code' => $customerId . '-' . date('Y-m'),
            'return_url' => "$publicUrl/retorno",
            'callback_url' => "$publicUrl/webhook",
        ]);
        $sub = $res['subscription'];
        Subscriptions::save(['subscriptionId' => $sub['id'], 'customerId' => $customerId, 'status' => $sub['status']]);
        error_log("Suscripción {$sub['id']} iniciada para $customerId");
        header('Location: ' . $res['url_redirect'], true, 303);
    }

    // 3. Webhook: Virtualpos avisa que terminó la inscripción. Consulta el resultado y responde 200.
    //    El body es JSON {"uuid": "subsc_..."}, pero el Content-Type puede no ser estándar: léelo crudo.
    elseif ($method === 'POST' && $path === '/webhook') {
        $id = requestUuid();
        http_response_code(200);
        if ($id !== null) {
            syncSubscription($vp, $id);
        }
    }

    // 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
    elseif ($method === 'POST' && $path === '/retorno') {
        $sub = syncSubscription($vp, requestUuid() ?? '');
        $active = $sub['status'] === 'ACTIVA';
        page($active ? '¡Suscripción activa!' : 'La suscripción no se completó',
            '<p>Cliente <code>' . e($sub['customerId']) . '</code>: <strong>' . e($sub['status']) . '</strong></p><p>' .
            ($active
                ? '<a href="/suscripciones/' . e($sub['subscriptionId']) . '">Ver suscripción y cargos</a>'
                : '<a href="/">Intentar nuevamente</a>') . '</p>');
    }

    // 5. Detalle: estado, tarjeta y cargos, siempre leídos desde la API.
    //    charge_program trae los cargos en todos los estados, también después de cancelar.
    elseif ($method === 'GET' && preg_match('#^/suscripciones/([\w-]+)$#', $path, $m)) {
        $s = $vp->getSubscription($m[1])['suscription'];
        $card = $s['payment_method'] ?? null;
        $rows = '';
        foreach ($s['charge_program'] ?? [] as $c) {
            $retry = $c['status'] === 'rechazado'
                ? '<form method="post" action="/cargos/' . e($c['id']) . '/reintentar"><input type="hidden" name="subscriptionId" value="' . e($m[1]) . '"><button>Reintentar</button></form>'
                : '';
            $rows .= '<tr><td>' . e($c['charge_date']) . '</td><td>' . e($c['description']) . '</td><td>$' . e((string) $c['amount']) . '</td><td>' . e($c['status']) . "</td><td>$retry</td></tr>";
        }
        $base = '/suscripciones/' . e($m[1]);
        page('Suscripción ' . $s['status'],
            '<p>' . ($card ? 'Tarjeta ' . e($card['brand']) . ' terminada en ' . e((string) $card['last4CardDigit']) : 'Sin tarjeta') . '</p>' .
            '<h2>Cargos</h2>' . ($rows ? "<table>$rows</table>" : '<p>Aún no hay cargos.</p>') .
            ($s['status'] === 'ACTIVA' ? "
            <form method=\"post\" action=\"$base/programar\"><button>Programar el cargo del próximo mes</button></form>
            <form method=\"post\" action=\"$base/cobrar-ahora\"><button>Cobrar el mes ahora</button></form>
            <form method=\"post\" action=\"$base/cambiar-tarjeta\"><button>Cambiar tarjeta</button></form>
            <form method=\"post\" action=\"$base/cancelar\"><button>Cancelar suscripción</button></form>" : '') .
            '<p><a href="/">Volver</a></p>');
    }

    // 6a. Programa el cargo del próximo mes: Virtualpos lo cobra en charge_date.
    //     La API no rechaza un internal_code repetido: revisa tú que el mes no esté programado, o lo cobrarás dos veces.
    elseif ($method === 'POST' && preg_match('#^/suscripciones/([\w-]+)/programar$#', $path, $m)) {
        $next = new DateTimeImmutable('first day of next month');
        $next = $next->setDate((int) $next->format('Y'), (int) $next->format('m'), min((int) date('d'), (int) $next->format('t')));
        $internalCode = $vp->getSubscription($m[1])['suscription']['service_id'] . '-' . $next->format('Y-m');
        $already = array_filter($vp->listCharges($m[1])['charges'],
            fn ($c) => ($c['internal_code'] ?? '') === $internalCode && $c['status'] !== 'cancelado');
        if ($already) {
            error_log("El cargo $internalCode ya estaba programado");
        } else {
            $charge = $vp->scheduleCharge($m[1], $next->format('Y-m-d'), MONTHLY_AMOUNT, 'Mensualidad ' . $next->format('Y-m'), $internalCode)['charge'];
            error_log("Cargo {$charge['id']} programado para {$charge['charge_date']} ({$charge['status']})");
        }
        header("Location: /suscripciones/{$m[1]}", true, 303);
    }

    // 6b. Cobra el mes ahora: la respuesta trae el resultado del cobro.
    //     Úsalo para cobros fuera de calendario. internal_code es obligatorio en este endpoint.
    elseif ($method === 'POST' && preg_match('#^/suscripciones/([\w-]+)/cobrar-ahora$#', $path, $m)) {
        $serviceId = $vp->getSubscription($m[1])['suscription']['service_id'];
        $charge = $vp->chargeNow($m[1], MONTHLY_AMOUNT, 'Mensualidad ' . date('Y-m'), "$serviceId-" . date('Y-m') . '-' . time())['charge'];
        error_log("Cargo {$charge['id']}: {$charge['status']}");
        header("Location: /suscripciones/{$m[1]}", true, 303);
    }

    // 7. Reintenta un cargo rechazado.
    elseif ($method === 'POST' && preg_match('#^/cargos/([\w.-]+)/reintentar$#', $path, $m)) {
        error_log($vp->retryCharge($m[1])['message'] ?? 'Reintento solicitado');
        header('Location: /suscripciones/' . rawurlencode($_POST['subscriptionId'] ?? ''), true, 303);
    }

    // 8. Cambio de tarjeta: genera el link y envía al cliente a actualizar su tarjeta.
    elseif ($method === 'POST' && preg_match('#^/suscripciones/([\w-]+)/cambiar-tarjeta$#', $path, $m)) {
        $res = $vp->changeCardLink($m[1]);
        error_log("Link de cambio de tarjeta válido hasta {$res['change_card_expiration_date']}");
        header('Location: ' . $res['change_card_url'], true, 303);
    }

    // 9. Cancela la suscripción: Virtualpos deja de cobrar los cargos pendientes.
    elseif ($method === 'POST' && preg_match('#^/suscripciones/([\w-]+)/cancelar$#', $path, $m)) {
        $status = $vp->cancelSubscription($m[1])['suscription']['status'];
        if ($sub = Subscriptions::get($m[1])) {
            $sub['status'] = $status;
            Subscriptions::save($sub);
        }
        error_log("Suscripción {$m[1]}: $status");
        header("Location: /suscripciones/{$m[1]}", true, 303);
    }

    else {
        http_response_code(404);
        page('No encontrado', '');
    }
} catch (\Throwable $err) {
    http_response_code(502);
    error_log("Error en $path: {$err->getMessage()}");
    $hint = $err instanceof VirtualposException ? $err->hint : null;
    page('Ocurrió un error', '<p><code>' . e($err->getMessage()) . '</code></p>' .
        ($hint ? '<p>' . e($hint) . '</p>' : '') . '<p><a href="/">Volver</a></p>');
}

// Fuente de verdad: siempre consulta la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
function syncSubscription(Virtualpos $vp, string $subscriptionId): array
{
    if ($subscriptionId === '') {
        throw new \RuntimeException('No llegó el id de la suscripción');
    }
    $s = $vp->getSubscription($subscriptionId)['suscription'];
    $sub = Subscriptions::get($subscriptionId) ?? ['subscriptionId' => $subscriptionId, 'customerId' => $s['service_id'], 'status' => null];
    $previous = $sub['status'];
    $sub['status'] = $s['status'];
    Subscriptions::save($sub);
    if ($sub['status'] === 'ACTIVA' && $previous !== 'ACTIVA') {
        // Aquí activas el servicio de tu cliente. Solo una vez, aunque el aviso llegue repetido.
        $card = $s['payment_method'] ?? [];
        error_log("Suscripción $subscriptionId activa con " . ($card['brand'] ?? '') . ' ' . ($card['last4CardDigit'] ?? ''));
    }
    return $sub;
}

// El uuid puede llegar como formulario o como JSON, a veces con un Content-Type no estándar.
function requestUuid(): ?string
{
    if (isset($_POST['uuid'])) {
        return (string) $_POST['uuid'];
    }
    $raw = file_get_contents('php://input');
    $json = json_decode($raw, true);
    if (isset($json['uuid'])) {
        return (string) $json['uuid'];
    }
    parse_str($raw, $form);
    return isset($form['uuid']) ? (string) $form['uuid'] : null;
}

function e(?string $s): string
{
    return htmlspecialchars((string) $s, ENT_QUOTES);
}

function page(string $title, string $body): void
{
    echo '<!doctype html><meta charset="utf-8"><title>' . e($title) . '</title>' .
        '<body style="font-family:system-ui;max-width:40rem;margin:3rem auto;padding:0 1rem"><h1>' . e($title) . '</h1>' . $body . '</body>';
}
