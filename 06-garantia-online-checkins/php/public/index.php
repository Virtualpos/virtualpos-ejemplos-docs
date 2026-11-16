<?php
// Guía: Tomar y cobrar una garantía online con Checkins
// https://docs.virtualpos.cl/guias/garantia-online-checkins/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Reservas;
use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

$vp = Virtualpos::fromEnv();
$publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$back = '/';

try {
    // 1. Página de tu sistema: nueva reserva y listado.
    if ($method === 'GET' && $path === '/') {
        $rows = '';
        foreach (array_reverse(Reservas::all()) as $r) {
            $rows .= '<tr><td><code>' . e($r['reservaId']) . '</code></td><td>' . e($r['status']) . '</td><td>' . clp($r['guarantee']) .
                '</td><td>' . (isset($r['last4']) ? '•••• ' . e($r['last4']) : '') . '</td><td><a href="/reservas/' . e($r['reservaId']) . '">Ver</a></td></tr>';
        }
        page('Arriendo de autos de ejemplo', '
            <form method="post" action="/reservas">
              <p><label>Garantía <input name="guarantee" type="number" value="50000" min="1"></label></p>
              <p><label><input type="radio" name="timing" value="immediate" checked> Tomarla ahora (inmediata)</label><br>
                 <label><input type="radio" name="timing" value="deferred"> Solo registrar la tarjeta (diferida)</label></p>
              <button>Reservar</button>
            </form>' . ($rows ? "<h2>Reservas</h2><table cellpadding=\"6\"><tr><th>Reserva</th><th>Estado</th><th>Garantía</th><th>Tarjeta</th><th></th></tr>$rows</table>" : ''));
    }

    // 2. Crea el check-in y lleva al cliente al formulario de Virtualpos para ingresar su tarjeta.
    elseif ($method === 'POST' && $path === '/reservas') {
        $reservaId = 'RES-' . (int) (microtime(true) * 1000);
        $guarantee = (int) ($_POST['guarantee'] ?? 50000);
        $checkin = $vp->createCheckin([
            'currency' => 'CLP',
            'description' => "Arriendo $reservaId",
            'merchant_internal_code' => $reservaId,
            'date_checkin_commerce' => date('Y-m-d', strtotime('+1 day')),
            'date_checkout_commerce' => date('Y-m-d', strtotime('+4 days')),
            'client' => [
                'first_name' => 'Ana',
                'last_name' => 'Pérez',
                'email' => 'cliente@example.com',
                'social_id_type' => 'RUT',
                'social_id' => '11111111-1',
                'type' => 'PERSONA',
            ],
            'payment_plan' => [
                'guarantee' => ['timing' => ($_POST['timing'] ?? '') === 'deferred' ? 'deferred' : 'immediate', 'amount' => $guarantee],
                'prepayment' => ['amount' => 0],
            ],
            'callback_url' => "$publicUrl/webhook",
            'return_url' => "$publicUrl/retorno",
            'metadata' => ['reserva' => $reservaId],
        ])['checkin'];
        Reservas::save(['reservaId' => $reservaId, 'uuid' => $checkin['uuid'], 'status' => $checkin['status'], 'guarantee' => $guarantee, 'uuidCli' => $checkin['client']['uuid_cli']]);
        error_log("Check-in {$checkin['uuid']} creado para $reservaId");
        header('Location: ' . $checkin['hosted_url'], true, 303);
    }

    // 3. Webhook: Virtualpos avisa que el check-in cambió. Consulta el estado y responde 200.
    elseif ($method === 'POST' && $path === '/webhook') {
        $uuid = requestUuid();
        http_response_code(200);
        if ($uuid !== null) {
            syncCheckin($vp, $uuid);
        }
    }

    // 4. Retorno: el cliente vuelve desde el formulario con el uuid del check-in por POST.
    elseif ($path === '/retorno') {
        $r = syncCheckin($vp, requestUuid() ?? '');
        header('Location: /reservas/' . $r['reservaId'], true, 303);
    }

    // Detalle de la reserva con las operaciones disponibles según su estado.
    elseif ($method === 'GET' && preg_match('#^/reservas/([\w-]+)$#', $path, $m)) {
        $r = Reservas::get($m[1]) ?? notFound();
        $r = syncCheckin($vp, $r['uuid']);
        $op = fn (string $p, string $label, ?string $field = null, int $value = 0) =>
            '<form method="post" action="/reservas/' . e($r['reservaId']) . "/$p\" style=\"margin:.5rem 0\">" .
            ($field ? "<input name=\"$field\" type=\"number\" value=\"$value\" min=\"1\"> " : '') . "<button>$label</button></form>";
        $ops = match ($r['status']) {
            'card_captured' => $op('autorizar', 'Tomar la garantía', 'amount', $r['guarantee']),
            'authorized' => $op('incremental', 'Aumentar la garantía en', 'amount', 20000)
                . $op('checkout', 'Cobrar el monto final', 'amount', $r['guarantee'])
                . $op('cancelar', 'Liberar la garantía (sin cobro)'),
            'captured' => $op('cargo-adicional', 'Cargo adicional (multa, daño)', 'amount', 8000),
            default => '',
        };
        $nueva = isset($r['cardReferenceId']) ? $op('nueva', 'Nueva reserva con la misma tarjeta, garantía', 'amount', 30000) : '';
        page('Reserva ' . $r['reservaId'], '<p>Estado del check-in: <strong>' . e($r['status']) . '</strong></p>' .
            '<p>Garantía: ' . clp($r['guarantee']) . (!empty($r['captured']) ? ' · Cobrado: ' . clp($r['captured']) : '') .
            (isset($r['last4']) ? ' · Tarjeta •••• ' . e($r['last4']) : '') . "</p>$ops$nueva<p><a href=\"/\">Volver</a></p>");
    }

    // 5 a 8. Operaciones sobre el check-in.
    elseif ($method === 'POST' && preg_match('#^/reservas/([\w-]+)/([\w-]+)$#', $path, $m)) {
        $r = Reservas::get($m[1]) ?? notFound();
        $back = '/reservas/' . $r['reservaId'];
        $amount = (int) ($_POST['amount'] ?? 0);
        match ($m[2]) {
            // 5a. Garantía diferida: tómala cuando la necesites (por ejemplo, el día del retiro).
            'autorizar' => $vp->authorize($r['uuid'], $amount),
            // 5b. Aumenta la garantía tomada (por ejemplo, el cliente extiende el arriendo).
            'incremental' => $vp->incremental($r['uuid'], $amount, 'Extensión del arriendo'),
            // 6a. Checkout: cobra el monto final. El resto de la garantía se libera.
            'checkout' => $vp->checkout($r['uuid'], $amount),
            // 6b. O libera la garantía completa sin cobrar.
            'cancelar' => $vp->cancel($r['uuid']),
            // 7. Cargo adicional después del checkout (multas, daños).
            'cargo-adicional' => $vp->additionalCharge($r['uuid'], $amount, 'Multa de tránsito'),
            // 8. Cliente que vuelve: nueva garantía con su tarjeta guardada, sin formulario.
            'nueva' => $back = '/reservas/' . nuevaConTarjetaGuardada($vp, $r, $amount, $publicUrl),
            default => notFound(),
        };
        error_log("{$m[2]} OK en {$r['reservaId']}");
        header("Location: $back", true, 303);
    }

    else {
        notFound();
    }
} catch (\Throwable $err) {
    http_response_code(502);
    error_log("Error en $path: {$err->getMessage()}");
    $hint = $err instanceof VirtualposException ? $err->hint : null;
    page('La operación no se completó', '<p><code>' . e($err->getMessage()) . '</code></p>' .
        ($hint ? '<p>' . e($hint) . '</p>' : '') . '<p><a href="' . e($back) . '">Volver</a></p>');
}

// Fuente de verdad: siempre consulta el check-in en la API. Webhook y retorno pueden llegar en cualquier orden o repetirse.
function syncCheckin(Virtualpos $vp, string $uuid): array
{
    if ($uuid === '') {
        throw new \RuntimeException('No llegó el uuid del check-in');
    }
    $checkin = $vp->getCheckin($uuid)['checkin'];
    $r = Reservas::byUuid($uuid) ?? ['reservaId' => $checkin['merchant_internal_code'], 'uuid' => $uuid];
    $r['status'] = $checkin['status'];
    $r['guarantee'] = $checkin['guarantee_amount'];
    $r['captured'] = array_sum(array_map(fn ($t) => $t['amount'],
        array_filter($checkin['transactions'] ?? [], fn ($t) => $t['transaction_type'] === 'checkout')));
    if (isset($checkin['card'])) {
        $r['cardReferenceId'] = $checkin['card']['card_reference_id']; // guárdalo para cobrarle al cliente sin formulario la próxima vez
        $r['last4'] = $checkin['card']['last4'];
    }
    if ($r['status'] === 'authorized' && empty($r['confirmed'])) {
        $r['confirmed'] = true; // una sola vez, aunque el webhook se repita
        // Aquí confirmas la reserva en tu sistema: correo al cliente, asignación del auto…
        error_log("Reserva {$r['reservaId']} confirmada: garantía de " . clp($r['guarantee']) . ' tomada');
    }
    Reservas::save($r);
    return $r;
}

function nuevaConTarjetaGuardada(Virtualpos $vp, array $anterior, int $guarantee, string $publicUrl): string
{
    $reservaId = 'RES-' . (int) (microtime(true) * 1000);
    $checkin = $vp->createCheckinWithSavedCard($anterior['uuidCli'], $anterior['cardReferenceId'], [
        'currency' => 'CLP',
        'description' => "Arriendo $reservaId",
        'merchant_internal_code' => $reservaId,
        'date_checkin_commerce' => date('Y-m-d', strtotime('+1 day')),
        'date_checkout_commerce' => date('Y-m-d', strtotime('+4 days')),
        'payment_plan' => ['guarantee' => ['timing' => 'immediate', 'amount' => $guarantee]],
        'callback_url' => "$publicUrl/webhook",
    ])['checkin'];
    Reservas::save(['reservaId' => $reservaId, 'uuid' => $checkin['uuid'], 'status' => $checkin['status'], 'guarantee' => $guarantee, 'uuidCli' => $anterior['uuidCli']]);
    error_log("Check-in {$checkin['uuid']} con tarjeta guardada para $reservaId: {$checkin['status']}");
    return $reservaId;
}

// El uuid llega como formulario (retorno) o como JSON (webhook).
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

function notFound(): never
{
    http_response_code(404);
    page('No encontrado', '<p><a href="/">Volver</a></p>');
    exit;
}

function clp(int|float|null $n): string
{
    return '$' . number_format((float) $n, 0, ',', '.');
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
