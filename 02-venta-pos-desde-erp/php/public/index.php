<?php
// Guía: Cobrar una venta en el POS desde tu ERP
// https://docs.virtualpos.cl/guias/venta-pos-desde-erp/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\PosException;
use Ejemplo\Sales;
use Ejemplo\VirtualposPos;

const MESSAGES = [
    'CREATED' => 'Enviando la venta al POS…',
    'DISPATCHED' => 'Esperando que el POS tome la venta…',
    'CLAIMED' => 'El cliente está pagando en el POS…',
    'APPROVED' => '<strong>Venta aprobada</strong>',
    'REJECTED' => '<strong>Pago rechazado.</strong> Pide otra tarjeta o reintenta.',
    'FAILED' => '<strong>La venta no llegó al POS.</strong> Revisa que esté encendido, con internet y con la app Virtualpos abierta.',
    'EXPIRED' => '<strong>El POS no tomó la venta a tiempo.</strong> Revisa que esté encendido y libre.',
    'CANCELLED' => '<strong>La venta se canceló en el POS.</strong>',
    'REVERSED' => '<strong>La venta fue reversada.</strong>',
];

$pos = VirtualposPos::fromEnv();
$posSerial = getenv('VIRTUALPOS_POS_SERIAL') ?: throw new RuntimeException('Configura VIRTUALPOS_POS_SERIAL en tu archivo .env');
$webhookSecret = getenv('VIRTUALPOS_WEBHOOK_SECRET') ?: '';
$publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$sale = null;

try {
    // 1. Pantalla de la caja: monto, propina y botón para cobrar en el POS.
    if ($method === 'GET' && $path === '/') {
        $rows = '';
        foreach (array_reverse(Sales::all()) as $s) {
            $rows .= '<tr><td><a href="/ventas/' . e($s['saleId']) . '">' . e($s['saleId']) . '</a></td><td>$' .
                number_format($s['amount'], 0, ',', '.') . '</td><td>' . e($s['status']) . '</td></tr>';
        }
        page('Caja de ejemplo', '<form method="post" action="/cobrar">
            <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
            <label>Propina <input name="tipAmount" type="number" min="0" value="0"></label>
            <button>Cobrar en el POS ' . e($posSerial) . '</button></form>' .
            ($rows ? "<h2>Ventas</h2><table>$rows</table>" : ''));
    }

    // 2. Envía la venta al POS. El ID de la venta en tu sistema es el externalPaymentId.
    elseif ($method === 'POST' && $path === '/cobrar') {
        $sale = [
            'saleId' => 'VENTA-' . (int) (microtime(true) * 1000),
            'amount' => (int) $_POST['amount'],
            'tipAmount' => (int) ($_POST['tipAmount'] ?? 0),
            'status' => 'CREATED',
            'paymentRequestId' => null,
        ];
        Sales::save($sale); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
        sendToPos($pos, $sale, $posSerial, $publicUrl);
        header("Location: /ventas/{$sale['saleId']}", true, 303);
    }

    // Estado para la pantalla de espera. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos).
    elseif ($method === 'GET' && preg_match('#^/ventas/([\w-]+)/estado$#', $path, $m)) {
        $sale = Sales::get($m[1]);
        if ($sale === null) {
            http_response_code(404);
            exit;
        }
        $final = in_array($sale['status'], VirtualposPos::FINAL, true);
        if (!$final && $sale['paymentRequestId'] && microtime(true) - ($sale['lastSync'] ?? 0) > 3) {
            $sale['lastSync'] = microtime(true);
            Sales::save($sale);
            try {
                $sale = applyStatus($sale['saleId'], $pos->getOperation($sale['paymentRequestId']));
            } catch (PosException $err) {
                error_log("Consulta de {$sale['saleId']}: {$err->getMessage()}");
            }
        }
        if ($sale['status'] === 'APPROVED' && empty($sale['detail'])) {
            $sale = loadDetail($pos, $sale);
        }
        header('Content-Type: application/json');
        echo json_encode([
            'status' => $sale['status'],
            'final' => in_array($sale['status'], VirtualposPos::FINAL, true),
            'detail' => $sale['detail'] ?? null,
        ]);
    }

    // 3. Pantalla de espera: el navegador consulta el estado cada 2 segundos hasta que la venta termina.
    elseif ($method === 'GET' && preg_match('#^/ventas/([\w-]+)$#', $path, $m)) {
        $sale = Sales::get($m[1]);
        if ($sale === null) {
            http_response_code(404);
            page('Venta no encontrada', '<p><a href="/">Volver</a></p>');
        } else {
            page('Venta ' . $sale['saleId'], '<p>Monto: <strong>$' . number_format($sale['amount'] + $sale['tipAmount'], 0, ',', '.') . '</strong></p>' .
                waitingScript());
        }
    }

    // 4. Reintentar una venta que terminó sin aprobarse. Usa el MISMO externalPaymentId:
    // si la venta ya estuviera aprobada, Virtualpos responde con ella y no vuelve a cobrar.
    elseif ($method === 'POST' && preg_match('#^/ventas/([\w-]+)/reintentar$#', $path, $m)) {
        $sale = Sales::get($m[1]);
        // Nunca reenvíes una venta en curso: el cliente podría estar pagando en el POS en este momento.
        // Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
        $unknown = $sale !== null && !$sale['paymentRequestId'];
        $retryable = $sale !== null && in_array($sale['status'], VirtualposPos::FINAL, true) && $sale['status'] !== 'APPROVED';
        if ($unknown || $retryable) {
            sendToPos($pos, $sale, $posSerial, $publicUrl);
        }
        header('Location: /ventas/' . ($sale['saleId'] ?? ''), true, 303);
    }

    // 5. Webhook: verifica la firma sobre el body crudo y procesa una sola vez.
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
        if (Sales::markEvent($event['eventId'])) { // false: reintento de un webhook ya procesado
            if (Sales::get($event['externalPaymentId']) === null) {
                error_log("Webhook de una venta desconocida: {$event['externalPaymentId']}");
            } else {
                $sale = applyStatus($event['externalPaymentId'], $event);
                if ($event['status'] === 'APPROVED' && !empty($event['resultPayload'])) {
                    $sale['detail'] = cardDetail($event['resultPayload']);
                    Sales::save($sale);
                }
            }
        }
        http_response_code(204);
    }

    else {
        http_response_code(404);
        page('No encontrado', '');
    }
} catch (\Throwable $err) {
    http_response_code(502);
    error_log("Error en $path: {$err->getMessage()}");
    $hint = $err instanceof PosException ? $err->hint : null;
    // Timeout o 500: no sabes si la venta llegó al POS. Reintenta con el MISMO externalPaymentId, nunca con uno nuevo.
    $unknownResult = $sale !== null && (!$err instanceof PosException || $err->httpStatus === 0 || $err->httpStatus >= 500);
    page('No se pudo enviar al POS', '<p><code>' . e($err->getMessage()) . '</code></p>' .
        ($hint ? '<p>' . e($hint) . '</p>' : '') .
        ($unknownResult ? '<form method="post" action="/ventas/' . e($sale['saleId']) . '/reintentar"><button>Reintentar ' . e($sale['saleId']) . '</button></form>' : '') .
        '<p><a href="/">Volver</a></p>');
}

function sendToPos(VirtualposPos $pos, array $sale, string $posSerial, string $publicUrl): void
{
    $op = $pos->createSale($posSerial, $sale['saleId'], $sale['amount'], $sale['tipAmount'], "$publicUrl/webhooks/virtualpos");
    $sale['paymentRequestId'] = $op['paymentRequestId'];
    $sale['attemptNumber'] = $op['attemptNumber'];
    $sale['status'] = $op['status'];
    $sale['lastSync'] = microtime(true);
    Sales::save($sale);
    error_log("{$sale['saleId']} enviada al POS (intento {$op['attemptNumber']}, {$op['status']})");
    if ($op['status'] === 'FAILED') {
        error_log('201 con FAILED: la venta no llegó al POS. ¿Está encendido, con internet y con la app Virtualpos abierta?');
    }
}

// Aplica un estado recibido por webhook o por consulta. Idempotente:
// ignora intentos anteriores y no cambia una venta que ya terminó.
function applyStatus(string $saleId, array $op): array
{
    $sale = Sales::get($saleId);
    $attempt = $op['attemptNumber'] ?? null;
    $current = $sale['attemptNumber'] ?? null;
    if ($attempt && $current && $attempt < $current) {
        return $sale;
    }
    if (in_array($sale['status'], VirtualposPos::FINAL, true) && $attempt === $current) {
        return $sale;
    }
    $sale['status'] = $op['status'];
    $sale['attemptNumber'] = $attempt ?? $current;
    Sales::save($sale);
    if ($sale['status'] === 'APPROVED') {
        // Aquí cierras la venta en tu ERP: boleta, descuento de stock, comprobante…
        error_log("$saleId APROBADA");
    } elseif (in_array($sale['status'], VirtualposPos::FINAL, true)) {
        error_log("$saleId terminó en {$sale['status']}");
    }
    return $sale;
}

// El detalle de la tarjeta viene en el webhook. Si no lo recibiste, pídelo a callback-event.
function loadDetail(VirtualposPos $pos, array $sale): array
{
    try {
        $event = $pos->getCallbackEvent($sale['paymentRequestId']);
        if (!empty($event['resultPayload'])) {
            $sale['detail'] = cardDetail($event['resultPayload']);
            Sales::save($sale);
        }
    } catch (PosException $err) {
        error_log("Detalle de {$sale['saleId']}: {$err->getMessage()}");
    }
    return $sale;
}

function cardDetail(array $r): array
{
    return [
        'authCode' => $r['authCode'] ?? null,
        'cardBrand' => $r['cardBrand'] ?? null,
        'cardTypeLabel' => $r['cardTypeLabel'] ?? null,
        'cardLast4' => $r['cardLast4'] ?? null,
        'posTransactionId' => $r['posTransactionId'] ?? null,
    ];
}

function waitingScript(): string
{
    return '<p id="estado">Esperando que el cliente pague en el POS…</p><div id="acciones"></div>
    <script>
      const mensajes = ' . json_encode(MESSAGES, JSON_UNESCAPED_UNICODE) . ';
      async function revisar() {
        const s = await (await fetch(location.pathname + "/estado")).json();
        document.getElementById("estado").innerHTML = mensajes[s.status] ?? s.status;
        if (!s.final) return setTimeout(revisar, 2000);
        let html = "";
        if (s.status === "APPROVED") {
          html = s.detail ? "<p>Autorización <code>" + s.detail.authCode + "</code> · " + s.detail.cardBrand + " " + s.detail.cardTypeLabel + " terminada en " + s.detail.cardLast4 + "</p>" : "";
        } else {
          html = "<form method=\"post\" action=\"" + location.pathname + "/reintentar\"><button>Reintentar en el POS</button></form>";
        }
        document.getElementById("acciones").innerHTML = html + "<p><a href=\"/\">Nueva venta</a></p>";
      }
      revisar();
    </script>';
}

function e(?string $s): string
{
    return htmlspecialchars((string) $s, ENT_QUOTES);
}

function page(string $title, string $body): void
{
    echo '<!doctype html><meta charset="utf-8"><title>' . e($title) . '</title>' .
        '<body style="font-family:system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem"><h1>' . e($title) . '</h1>' . $body . '</body>';
}
