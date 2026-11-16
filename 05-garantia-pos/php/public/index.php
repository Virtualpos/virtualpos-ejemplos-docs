<?php
// Guía: Garantía con el POS (preautorización y cierre)
// https://docs.virtualpos.cl/guias/garantia-pos/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\PosException;
use Ejemplo\Reservas;
use Ejemplo\VirtualposPos;

const MESSAGES = [
    'CREATED' => 'Enviando al POS…',
    'DISPATCHED' => 'Esperando que el POS tome la operación…',
    'CLAIMED' => 'El huésped está presentando su tarjeta…',
    'APPROVED' => '<strong>Aprobada</strong>',
    'REJECTED' => '<strong>Rechazada.</strong> Pide otra tarjeta y reintenta.',
    'FAILED' => '<strong>No llegó al POS.</strong> Revisa que esté encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso.',
    'EXPIRED' => '<strong>El POS no tomó la operación a tiempo.</strong> Revisa que esté encendido y libre.',
    'CANCELLED' => '<strong>Se canceló en el POS.</strong>',
    'REVERSED' => '<strong>Fue reversada.</strong>',
];

$pos = VirtualposPos::fromEnv();
$posSerial = getenv('VIRTUALPOS_POS_SERIAL') ?: throw new RuntimeException('Configura VIRTUALPOS_POS_SERIAL en tu archivo .env');
$webhookSecret = getenv('VIRTUALPOS_WEBHOOK_SECRET') ?: '';
$callbackUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/') . '/webhooks/virtualpos';
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$reserva = null;
$kind = null;

try {
    // 1. Recepción: ingresa el monto de la garantía y preautoriza en el POS.
    if ($method === 'GET' && $path === '/') {
        $rows = '';
        foreach (array_reverse(Reservas::all()) as $r) {
            $rows .= '<tr><td><a href="/reservas/' . e($r['reservaId']) . '">' . e($r['reservaId']) . '</a></td><td>' . clp($r['guarantee']) .
                '</td><td>' . e($r['checkin']['status']) . '</td><td>' .
                ($r['checkout'] ? clp($r['checkout']['amount']) . ' ' . e($r['checkout']['status']) : '—') . '</td></tr>';
        }
        page('Recepción de ejemplo', '<form method="post" action="/checkin">
            <label>Garantía <input name="amount" type="number" min="1" value="50000" required></label>
            <button>Preautorizar en el POS ' . e($posSerial) . '</button></form>' .
            ($rows ? "<h2>Reservas</h2><table><tr><th>Reserva</th><th>Garantía</th><th>Check-in</th><th>Check-out</th></tr>$rows</table>" : ''));
    }

    // 2. Check-in: guarda la reserva y envía la preautorización (CHECKIN). El ID de la reserva es el externalPaymentId.
    elseif ($method === 'POST' && $path === '/checkin') {
        $id = 'RESERVA-' . (int) (microtime(true) * 1000);
        $reserva = [
            'reservaId' => $id,
            'guarantee' => (int) $_POST['amount'],
            'checkin' => newOperation('CHECKIN', $id, (int) $_POST['amount']),
            'checkout' => null,
            'prints' => [],
        ];
        Reservas::save($reserva); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
        $kind = 'checkin';
        send($pos, $reserva, $kind, $posSerial, $callbackUrl);
        header("Location: /reservas/$id", true, 303);
    }

    // 3. Check-out: cierra la preautorización con el monto final. Requiere el resultPayload del check-in aprobado.
    elseif ($method === 'POST' && preg_match('#^/reservas/([\w-]+)/checkout$#', $path, $m)) {
        $reserva = Reservas::get($m[1]) ?? notFound();
        if ($reserva['checkin']['status'] !== 'APPROVED' || empty($reserva['checkin']['detail']['posTransactionId'])) {
            http_response_code(409);
            page('Aún no puedes cerrar', '<p>El check-in debe estar aprobado y con su posTransactionId.</p>' . back($reserva));
            exit;
        }
        if ($reserva['checkout'] === null) { // si ya se envió, usa Reintentar
            $reserva['checkout'] = newOperation('CHECKOUT', "{$reserva['reservaId']}-CHECKOUT", (int) $_POST['amount']);
            Reservas::save($reserva);
            $kind = 'checkout';
            send($pos, $reserva, $kind, $posSerial, $callbackUrl);
        }
        header("Location: /reservas/{$reserva['reservaId']}", true, 303);
    }

    // 4. Reintentar el check-in o el check-out con el MISMO externalPaymentId.
    elseif ($method === 'POST' && preg_match('#^/reservas/([\w-]+)/reintentar/(checkin|checkout)$#', $path, $m)) {
        $reserva = Reservas::get($m[1]) ?? notFound();
        $kind = $m[2];
        $op = $reserva[$kind] ?? notFound();
        // Nunca reenvíes una operación en curso: el huésped podría estar presentando su tarjeta.
        // Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
        $unknown = empty($op['paymentRequestId']);
        $retryable = in_array($op['status'], VirtualposPos::FINAL, true) && $op['status'] !== 'APPROVED';
        if ($unknown || $retryable) {
            send($pos, $reserva, $kind, $posSerial, $callbackUrl);
        }
        header("Location: /reservas/{$reserva['reservaId']}", true, 303);
    }

    // 5. Imprime el comprobante de la estadía. Cada impresión usa un externalPaymentId nuevo.
    elseif ($method === 'POST' && preg_match('#^/reservas/([\w-]+)/imprimir$#', $path, $m)) {
        $reserva = Reservas::get($m[1]) ?? notFound();
        $i = count($reserva['prints']);
        $reserva['prints'][] = newOperation('PRINT', "{$reserva['reservaId']}-COMPROBANTE-" . ($i + 1), null);
        Reservas::save($reserva);
        $kind = "prints.$i";
        send($pos, $reserva, $kind, $posSerial, $callbackUrl);
        header("Location: /reservas/{$reserva['reservaId']}", true, 303);
    }

    // Estado de la reserva. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos por operación).
    elseif ($method === 'GET' && preg_match('#^/reservas/([\w-]+)/estado$#', $path, $m)) {
        $reserva = Reservas::get($m[1]) ?? notFound();
        $waiting = false;
        foreach (operationKeys($reserva) as $key) {
            $op = getOp($reserva, $key);
            if (!in_array($op['status'], VirtualposPos::FINAL, true) && !empty($op['paymentRequestId']) && microtime(true) - ($op['lastSync'] ?? 0) > 3) {
                $op['lastSync'] = microtime(true);
                $reserva = setOp($reserva, $key, $op);
                Reservas::save($reserva);
                try {
                    $reserva = applyStatus($reserva['reservaId'], $key, $pos->getOperation($op['paymentRequestId']));
                } catch (PosException $err) {
                    error_log("Consulta de {$op['externalPaymentId']}: {$err->getMessage()}");
                }
                $op = getOp($reserva, $key);
            }
            // El checkout necesita el resultPayload del check-in. Si no llegó el webhook, pídelo a callback-event.
            if ($op['status'] === 'APPROVED' && $op['kind'] !== 'PRINT' && empty($op['detail'])) {
                try {
                    $event = $pos->getCallbackEvent($op['paymentRequestId']);
                    if (!empty($event['resultPayload'])) {
                        $op['detail'] = $event['resultPayload'];
                        $reserva = setOp($reserva, $key, $op);
                        Reservas::save($reserva);
                    }
                } catch (PosException $err) {
                    error_log("Detalle de {$op['externalPaymentId']}: {$err->getMessage()}");
                }
            }
            $waiting = $waiting || !in_array($op['status'], VirtualposPos::FINAL, true);
        }
        header('Content-Type: application/json');
        echo json_encode(['html' => reservaHtml($reserva), 'waiting' => $waiting]);
    }

    // Pantalla de la reserva: el navegador consulta el estado cada 2 segundos.
    elseif ($method === 'GET' && preg_match('#^/reservas/([\w-]+)$#', $path, $m)) {
        $reserva = Reservas::get($m[1]) ?? notFound();
        page("Reserva {$reserva['reservaId']}", '<p>Garantía: <strong>' . clp($reserva['guarantee']) . '</strong></p>
            <div id="estado">Consultando…</div>
            <script>
              async function revisar() {
                const r = await (await fetch(location.pathname + "/estado")).json();
                document.getElementById("estado").innerHTML = r.html;
                if (r.waiting) setTimeout(revisar, 2000);
              }
              revisar();
            </script>');
    }

    // 6. Webhook: verifica la firma sobre el body crudo y procesa una sola vez.
    elseif ($method === 'POST' && $path === '/webhooks/virtualpos') {
        $raw = file_get_contents('php://input');
        $problem = $webhookSecret === ''
            ? 'sin secreto'
            : VirtualposPos::verifyWebhook($raw, $_SERVER['HTTP_X_VP_SIGNATURE'] ?? '', $_SERVER['HTTP_X_VP_TIMESTAMP'] ?? '', $webhookSecret);
        if ($problem !== null) {
            error_log("Webhook rechazado: $problem");
            http_response_code(401);
            exit;
        }
        $event = json_decode($raw, true);
        if (Reservas::markEvent($event['eventId'])) { // false: reintento de un webhook ya procesado
            $found = Reservas::findOperation($event['externalPaymentId']);
            if ($found === null) {
                error_log("Webhook de una operación desconocida: {$event['externalPaymentId']}");
            } else {
                [$id, $key] = $found;
                $reserva = applyStatus($id, $key, $event);
                if ($event['status'] === 'APPROVED' && !empty($event['resultPayload'])) {
                    $op = getOp($reserva, $key);
                    $op['detail'] = $event['resultPayload'];
                    Reservas::save(setOp($reserva, $key, $op));
                }
            }
        }
        http_response_code(204);
    }

    else {
        notFound();
    }
} catch (\Throwable $err) {
    http_response_code(502);
    error_log("Error en $path: {$err->getMessage()}");
    $hint = $err instanceof PosException ? $err->hint : null;
    // Timeout o 500: no sabes si la operación llegó al POS. Reintenta con el MISMO externalPaymentId, nunca con uno nuevo.
    $unknownResult = $reserva !== null && in_array($kind, ['checkin', 'checkout'], true)
        && (!$err instanceof PosException || $err->httpStatus === 0 || $err->httpStatus >= 500);
    page('No se pudo enviar al POS', '<p><code>' . e($err->getMessage()) . '</code></p>' .
        ($hint ? '<p>' . e($hint) . '</p>' : '') .
        ($unknownResult ? '<form method="post" action="/reservas/' . e($reserva['reservaId']) . "/reintentar/$kind\"><button>Reintentar</button></form>" : '') .
        ($reserva ? back($reserva) : '<p><a href="/">Volver</a></p>'));
}

function newOperation(string $kind, string $externalPaymentId, ?int $amount): array
{
    return ['kind' => $kind, 'externalPaymentId' => $externalPaymentId, 'amount' => $amount, 'status' => 'CREATED', 'paymentRequestId' => null];
}

// $key es 'checkin', 'checkout' o 'prints.<n>'.
function getOp(array $reserva, string $key): array
{
    return str_starts_with($key, 'prints.') ? $reserva['prints'][(int) substr($key, 7)] : $reserva[$key];
}

function setOp(array $reserva, string $key, array $op): array
{
    if (str_starts_with($key, 'prints.')) {
        $reserva['prints'][(int) substr($key, 7)] = $op;
    } else {
        $reserva[$key] = $op;
    }
    return $reserva;
}

function operationKeys(array $reserva): array
{
    $keys = ['checkin'];
    if ($reserva['checkout'] !== null) {
        $keys[] = 'checkout';
    }
    foreach (array_keys($reserva['prints']) as $i) {
        $keys[] = "prints.$i";
    }
    return $keys;
}

function send(VirtualposPos $pos, array $reserva, string $key, string $posSerial, string $callbackUrl): void
{
    $op = getOp($reserva, $key);
    $result = match ($op['kind']) {
        'CHECKIN' => $pos->createCheckin($posSerial, $op['externalPaymentId'], $op['amount'], $callbackUrl),
        // El checkout lleva el resultPayload del check-in aprobado.
        'CHECKOUT' => $pos->createCheckout($posSerial, $op['externalPaymentId'], $op['amount'], $reserva['checkin']['detail'], $callbackUrl),
        'PRINT' => $pos->print($posSerial, $op['externalPaymentId'], receiptLines($reserva), $callbackUrl),
    };
    $op['paymentRequestId'] = $result['paymentRequestId'];
    $op['attemptNumber'] = $result['attemptNumber'] ?? null; // 201 con FAILED no trae attemptNumber
    $op['status'] = $result['status'];
    $op['lastSync'] = microtime(true);
    Reservas::save(setOp($reserva, $key, $op));
    error_log("{$op['externalPaymentId']} ({$op['kind']}) enviada al POS: {$op['status']}" . ($op['attemptNumber'] ? ", intento {$op['attemptNumber']}" : ''));
    if ($result['status'] === 'FAILED') {
        error_log('201 con FAILED: no llegó al POS. ¿Está encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso?');
    }
}

// Aplica un estado recibido por webhook o por consulta. Idempotente:
// ignora intentos anteriores y no cambia una operación que ya terminó.
function applyStatus(string $reservaId, string $key, array $update): array
{
    $reserva = Reservas::get($reservaId);
    $op = getOp($reserva, $key);
    $attempt = $update['attemptNumber'] ?? null;
    $current = $op['attemptNumber'] ?? null;
    if ($attempt && $current && $attempt < $current) {
        return $reserva;
    }
    if (in_array($op['status'], VirtualposPos::FINAL, true) && $attempt === $current) {
        return $reserva;
    }
    $op['status'] = $update['status'];
    $op['attemptNumber'] = $attempt ?? $current;
    $reserva = setOp($reserva, $key, $op);
    Reservas::save($reserva);
    if ($op['status'] === 'APPROVED' && $op['kind'] === 'CHECKIN') {
        error_log("{$op['externalPaymentId']}: garantía preautorizada"); // aquí entregas la llave de la habitación
    } elseif ($op['status'] === 'APPROVED' && $op['kind'] === 'CHECKOUT') {
        error_log("{$op['externalPaymentId']}: estadía cobrada"); // aquí emites la boleta y cierras la reserva
    } elseif (in_array($op['status'], VirtualposPos::FINAL, true)) {
        error_log("{$op['externalPaymentId']} terminó en {$op['status']}");
    }
    return $reserva;
}

// Comprobante de la estadía, línea por línea. https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/imprimir/
function receiptLines(array $reserva): array
{
    $card = $reserva['checkin']['detail'] ?? [];
    $lines = [
        ['type' => 'text', 'content' => 'Comprobante de estadía', 'font_size' => 'big', 'align' => 'center'],
        ['type' => 'blank'],
        ['type' => 'left_right', 'left' => 'Reserva', 'right' => $reserva['reservaId'], 'font_size' => 'small'],
        ['type' => 'left_right', 'left' => 'Garantía', 'right' => clp($reserva['guarantee']), 'font_size' => 'small'],
    ];
    if ($reserva['checkout']) {
        $lines[] = ['type' => 'left_right', 'left' => 'Total cobrado', 'right' => clp($reserva['checkout']['amount']), 'font_size' => 'normal'];
    }
    if (!empty($card['cardLast4'])) {
        $lines[] = ['type' => 'left_right', 'left' => 'Tarjeta', 'right' => ($card['cardBrand'] ?? '') . ' ****' . $card['cardLast4'], 'font_size' => 'small'];
    }
    $lines[] = ['type' => 'blank'];
    $lines[] = ['type' => 'text', 'content' => 'Gracias por su visita', 'align' => 'center'];
    return $lines;
}

function reservaHtml(array $r): string
{
    $id = e($r['reservaId']);
    $retryable = fn (array $op) => in_array($op['status'], VirtualposPos::FINAL, true) && $op['status'] !== 'APPROVED';
    $retry = fn (string $kind) => "<form method=\"post\" action=\"/reservas/$id/reintentar/$kind\"><button>Reintentar</button></form>";
    $html = '<h2>Check-in (' . clp($r['guarantee']) . ')</h2><p>' . (MESSAGES[$r['checkin']['status']] ?? e($r['checkin']['status'])) . '</p>';
    $card = $r['checkin']['detail'] ?? null;
    if ($card) {
        $html .= '<p>Autorización <code>' . e($card['authCode'] ?? '') . '</code> · ' . e($card['cardBrand'] ?? '') . ' terminada en ' . e($card['cardLast4'] ?? '') . '</p>';
    }
    if ($retryable($r['checkin'])) {
        $html .= $retry('checkin');
    }
    if ($r['checkin']['status'] === 'APPROVED' && $card && !$r['checkout']) {
        $html .= "<h2>Check-out</h2><form method=\"post\" action=\"/reservas/$id/checkout\">
            <label>Monto final <input name=\"amount\" type=\"number\" min=\"1\" value=\"{$r['guarantee']}\" required></label>
            <button>Cobrar en el POS</button></form>";
    }
    if ($r['checkout']) {
        $html .= '<h2>Check-out (' . clp($r['checkout']['amount']) . ')</h2><p>' . (MESSAGES[$r['checkout']['status']] ?? e($r['checkout']['status'])) . '</p>';
        if ($retryable($r['checkout'])) {
            $html .= $retry('checkout');
        }
        if ($r['checkout']['status'] === 'APPROVED') {
            $html .= "<form method=\"post\" action=\"/reservas/$id/imprimir\"><button>Imprimir comprobante</button></form>";
        }
    }
    foreach ($r['prints'] as $p) {
        $html .= '<p>Impresión ' . e($p['externalPaymentId']) . ': ' . ($p['status'] === 'APPROVED' ? 'impresa' : (MESSAGES[$p['status']] ?? e($p['status']))) . '</p>';
    }
    return $html . '<p><a href="/">Volver a la recepción</a></p>';
}

function notFound(): never
{
    http_response_code(404);
    page('No encontrado', '<p><a href="/">Volver</a></p>');
    exit;
}

function clp(int|string|null $n): string
{
    return '$' . number_format((int) $n, 0, ',', '.');
}

function back(array $reserva): string
{
    return '<p><a href="/reservas/' . e($reserva['reservaId']) . '">Volver a la reserva</a></p>';
}

function e(?string $s): string
{
    return htmlspecialchars((string) $s, ENT_QUOTES);
}

function page(string $title, string $body): void
{
    echo '<!doctype html><meta charset="utf-8"><title>' . e($title) . '</title>' .
        '<body style="font-family:system-ui;max-width:36rem;margin:3rem auto;padding:0 1rem"><h1>' . e($title) . '</h1>' . $body . '</body>';
}
