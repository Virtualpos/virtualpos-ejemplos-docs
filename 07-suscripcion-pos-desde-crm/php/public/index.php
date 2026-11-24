<?php
// Guía: Suscribir clientes en el POS desde tu CRM
// https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Subscriptions;
use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

const VIGENCIA_SEGUNDOS = 30 * 60; // el cliente tiene 30 minutos para presentar su tarjeta

$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

try {
    $vp = Virtualpos::fromEnv();
    $planId = getenv('VIRTUALPOS_PLAN_ID') ?: '';
    if ($planId === '') {
        throw new \RuntimeException('Configura VIRTUALPOS_PLAN_ID en tu archivo .env (créalo con composer crear-plan)');
    }
    $publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');

    // POS de tu comercio: "serial:nombre,serial:nombre"
    $posList = [];
    foreach (array_filter(explode(',', getenv('POS_TERMINALES') ?: '')) as $p) {
        $parts = explode(':', $p, 2);
        $serial = trim($parts[0]);
        $posList[$serial] = trim($parts[1] ?? '') ?: $serial;
    }
    if (!$posList) {
        throw new \RuntimeException('Configura POS_TERMINALES en tu archivo .env');
    }
} catch (\Throwable $err) {
    http_response_code(500);
    error_log($err->getMessage());
    page('Falta configuración', '<p><code>' . e($err->getMessage()) . '</code></p>');
    return;
}

// 1. Ficha del cliente: el vendedor completa los datos y elige el POS donde está el cliente.
if ($method === 'GET' && $path === '/') {
    // Respaldo del webhook: mientras haya envíos pendientes, consulta la API cada vez que se recarga la página.
    foreach (array_filter(Subscriptions::all(), 'needsCheck') as $s) {
        try {
            syncSubscription($vp, $s['subscriptionId']);
        } catch (\Throwable $err) {
            error_log("Consulta de {$s['subscriptionId']}: {$err->getMessage()}");
        }
    }

    $options = '';
    foreach ($posList as $serial => $name) {
        $options .= '<option value="' . e($serial) . '">' . e($name) . ' (' . e($serial) . ')</option>';
    }
    $rows = '';
    foreach (array_reverse(Subscriptions::all()) as $s) {
        $rows .= '<tr>
      <td>' . e($s['customer']['first_name']) . ' ' . e($s['customer']['last_name']) . '</td>
      <td>' . e($posList[$s['posSerial']] ?? $s['posSerial']) . '</td>
      <td>' . label($s) . '</td>
      <td>' . (isPending($s) ? '<form method="post" action="/suscripciones/' . e($s['subscriptionId']) . '/cancelar"><button>Cancelar envío</button></form>' : '') . '</td>
    </tr>';
    }
    page('CRM de ejemplo', '
    <form method="post" action="/suscripciones" style="display:grid;gap:.5rem;max-width:24rem">
      <input name="first_name" placeholder="Nombre" required>
      <input name="last_name" placeholder="Apellido" required>
      <input name="social_id" placeholder="RUT (11111111-1)" required>
      <input name="email" type="email" placeholder="Email" required>
      <input name="phone_number" placeholder="Teléfono (56912345678)" required>
      <select name="pos">' . $options . '</select>
      <button>Suscribir en el POS</button>
    </form>' .
        ($rows ? "
    <h2>Suscripciones</h2><table cellpadding=\"6\"><tr><th>Cliente</th><th>POS</th><th>Estado</th><th></th></tr>$rows</table>
    <script>setTimeout(() => location.reload(), 5000)</script>" : ''));
}

// 2. Crea la suscripción y la envía al POS elegido.
elseif ($method === 'POST' && $path === '/suscripciones') {
    $posSerial = (string) ($_POST['pos'] ?? '');
    if (!isset($posList[$posSerial])) {
        http_response_code(400);
        page('POS desconocido', '<p><a href="/">Volver</a></p>');
        return;
    }

    // Un POS atiende una suscripción a la vez: no envíes otra mientras haya una pendiente en ese equipo.
    foreach (Subscriptions::all() as $s) {
        if ($s['posSerial'] === $posSerial && isPending($s)) {
            http_response_code(409);
            page('El POS está ocupado', '<p>' . e($posList[$posSerial]) . ' tiene una suscripción pendiente de ' . e($s['customer']['first_name']) .
                '. Espera a que termine o cancela el envío.</p><p><a href="/">Volver</a></p>');
            return;
        }
    }

    $customer = [
        'first_name' => (string) ($_POST['first_name'] ?? ''),
        'last_name' => (string) ($_POST['last_name'] ?? ''),
        'social_id' => (string) ($_POST['social_id'] ?? ''),
        'email' => (string) ($_POST['email'] ?? ''),
        'phone_number' => (string) ($_POST['phone_number'] ?? ''),
    ];
    $subscriptionId = null;
    try {
        $suscription = $vp->createSubscription($customer + [
            'plan_id' => $planId,
            'channel' => 'CRM',
            'return_url' => $publicUrl,
            'callback_url' => "$publicUrl/webhook",
        ])['suscription'];
        $subscriptionId = $suscription['id'];
        Subscriptions::save([
            'subscriptionId' => $subscriptionId,
            'customer' => $customer,
            'posSerial' => $posSerial,
            'status' => $suscription['status'],
            'sentAt' => time(),
            'finalCheck' => false,
            'card' => null,
        ]);
        error_log("Suscripción $subscriptionId creada ({$suscription['status']})");

        $vp->sendToPos($posSerial, $subscriptionId);
        error_log("Suscripción $subscriptionId enviada al POS $posSerial");
        header('Location: /', true, 303);
    } catch (\Throwable $err) {
        // Si la suscripción se creó pero no llegó al POS, no la reutilices: la próxima vez crea una nueva.
        if ($subscriptionId !== null) {
            Subscriptions::update($subscriptionId, fn (array $sub) => ['status' => 'NO_ENVIADA'] + $sub);
        }
        error_log("No se pudo enviar al POS: {$err->getMessage()}");
        http_response_code(502);
        page('No se pudo enviar al POS', errorHtml($err));
    }
}

// 3. Webhook: Virtualpos avisa que la inscripción terminó, con el uuid de la suscripción.
//    El body es JSON {"uuid": "subsc_..."}, pero el Content-Type puede no ser estándar: léelo crudo.
elseif ($method === 'POST' && $path === '/webhook') {
    $subscriptionId = requestUuid();
    http_response_code(200);
    // Responde de inmediato; la consulta a la API va después.
    // Con PHP-FPM, fastcgi_finish_request() entrega la respuesta antes de consultar; con php -S sale al terminar.
    if (function_exists('fastcgi_finish_request')) {
        fastcgi_finish_request();
    }
    if ($subscriptionId !== null) {
        try {
            syncSubscription($vp, $subscriptionId);
        } catch (\Throwable $err) {
            error_log("Webhook $subscriptionId: {$err->getMessage()}");
        }
    }
}

// 4. Cancela el envío si el cliente se fue o se eligió el POS equivocado.
elseif ($method === 'POST' && preg_match('#^/suscripciones/([\w-]+)/cancelar$#', $path, $m)) {
    $sub = Subscriptions::get($m[1]);
    if ($sub === null) {
        http_response_code(404);
        page('Suscripción no encontrada', '<p><a href="/">Volver</a></p>');
        return;
    }
    try {
        $vp->cancelSendToPos($sub['posSerial'], $sub['subscriptionId'], 'crm', 'Cancelado desde el CRM');
        Subscriptions::update($sub['subscriptionId'], fn (array $s) => ['status' => 'ENVIO_CANCELADO'] + $s);
        error_log("Envío de {$sub['subscriptionId']} al POS {$sub['posSerial']} cancelado");
        header('Location: /', true, 303);
    } catch (\Throwable $err) {
        error_log("No se pudo cancelar el envío: {$err->getMessage()}");
        http_response_code(502);
        page('No se pudo cancelar el envío', errorHtml($err));
    }
}

else {
    http_response_code(404);
    page('No encontrado', '<p><a href="/">Volver</a></p>');
}

// Fuente de verdad: siempre consulta la API. Es idempotente: el webhook puede repetirse o llegar después de la consulta.
function syncSubscription(Virtualpos $vp, string $subscriptionId): void
{
    if (Subscriptions::get($subscriptionId) === null) {
        return; // no es una suscripción de este CRM
    }
    $s = $vp->getSubscription($subscriptionId)['suscription'];
    $activated = null;
    Subscriptions::update($subscriptionId, function (array $sub) use ($s, &$activated) {
        if (time() - $sub['sentAt'] >= VIGENCIA_SEGUNDOS) {
            $sub['finalCheck'] = true; // una última consulta después de vencer
        }
        if (in_array($sub['status'], ['ENVIO_CANCELADO', 'NO_ENVIADA'], true) && $s['status'] === 'SUSCRIBIENDO') {
            return $sub;
        }
        $previous = $sub['status'];
        $sub['status'] = $s['status'];
        if ($sub['status'] === 'ACTIVA' && $previous !== 'ACTIVA') {
            // Aquí marcas al cliente como suscrito en tu CRM. Solo una vez, aunque el aviso llegue repetido.
            $card = $s['payment_method'] ?? null;
            $sub['card'] = $card ? "{$card['brand']} ****{$card['last4CardDigit']}" : null;
            $activated = $sub;
        }
        return $sub;
    });
    if ($activated) {
        error_log("Suscripción $subscriptionId activa con " . ($activated['card'] ?? 'tarjeta inscrita'));
    }
}

// Pendiente en el POS, o vencida pero aún sin la última consulta.
function needsCheck(array $s): bool
{
    return isPending($s) || ($s['status'] === 'SUSCRIBIENDO' && empty($s['finalCheck']));
}

function isPending(array $s): bool
{
    return $s['status'] === 'SUSCRIBIENDO' && time() - $s['sentAt'] < VIGENCIA_SEGUNDOS;
}

function label(array $s): string
{
    return match ($s['status']) {
        'ACTIVA' => '✔ Activa' . (($s['card'] ?? null) ? ' (' . e($s['card']) . ')' : ''),
        'SUSCRIPCION_FALLIDA' => '✘ Falló: crea una nueva suscripción',
        'ENVIO_CANCELADO' => 'Envío cancelado',
        'NO_ENVIADA' => '✘ No llegó al POS',
        'SUSCRIBIENDO' => isPending($s) ? '⏳ Esperando la tarjeta en el POS' : '✘ Venció: crea una nueva suscripción',
        default => e($s['status']),
    };
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

function errorHtml(\Throwable $err): string
{
    $hint = $err instanceof VirtualposException ? $err->hint : null;
    return '<p><code>' . e($err->getMessage()) . '</code></p>' . ($hint ? '<p>' . e($hint) . '</p>' : '') . '<p><a href="/">Volver</a></p>';
}

function e(?string $s): string
{
    return htmlspecialchars((string) $s, ENT_QUOTES);
}

function page(string $title, string $body): void
{
    echo '<!doctype html><meta charset="utf-8"><title>' . e($title) . '</title>' .
        '<body style="font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem"><h1>' . e($title) . '</h1>' . $body . '</body>';
}
