<?php
// Guía: Cobrar un pago único con Web Checkout
// https://docs.virtualpos.cl/guias/pago-unico-web-checkout/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Orders;
use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

const CUSTOMER_EMAIL = 'cliente@example.com'; // en tu sistema, el email de tu cliente
const FINAL_STATES = ['pagado', 'rechazado', 'expirado', 'cancelado', 'anulado'];

$vp = Virtualpos::fromEnv();
$publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

try {
    // 1. Página de tu tienda con el botón de pago.
    if ($method === 'GET' && $path === '/') {
        page('Tienda de ejemplo', '<p>Pedido de prueba por <strong>$9.990</strong>.</p>
            <form method="post" action="/pagar"><button>Pagar con Virtualpos</button></form>');
    }

    // 2. Crea el Payment y redirige al cliente al Web Checkout.
    elseif ($method === 'POST' && $path === '/pagar') {
        $orderId = 'ORD-' . (int) (microtime(true) * 1000);
        $amount = 9990;
        $res = $vp->createPayment([
            'amount' => $amount,
            'email' => CUSTOMER_EMAIL,
            'social_id' => '11111111-1',
            'first_name' => 'Ana',
            'last_name' => 'Pérez',
            'phone' => '56912345678',
            'description' => "Pedido $orderId",
            'merchant_internal_code' => $orderId,
            'merchant_internal_channel' => 'tienda-ejemplo',
            'return_url' => "$publicUrl/retorno",
            'callback_url' => "$publicUrl/webhook",
        ]);
        $order = $res['payment']['order'];
        Orders::save(['orderId' => $orderId, 'amount' => $amount, 'uuid' => $order['uuid'], 'status' => $order['status']]);
        header('Location: ' . $res['url_redirect'], true, 303);
    }

    // 3. Webhook: Virtualpos avisa que el pago cambió. Consulta el resultado y responde 200.
    elseif ($method === 'POST' && $path === '/webhook') {
        $uuid = requestUuid();
        http_response_code(200);
        if ($uuid !== null) {
            syncPayment($vp, $uuid);
        }
    }

    // 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
    elseif ($method === 'POST' && $path === '/retorno') {
        $order = syncPayment($vp, requestUuid() ?? '');
        $paid = $order['status'] === 'pagado';
        page($paid ? '¡Pago recibido!' : 'El pago no se completó',
            '<p>Pedido <code>' . e($order['orderId']) . '</code>: <strong>' . e($order['status']) . '</strong></p>' .
            ($paid
                ? '<form method="post" action="/anular/' . e($order['orderId']) . '"><button>Anular este pago</button></form>'
                : '<p><a href="/">Intentar nuevamente</a></p>'));
    }

    // 5. Anulación total del pago (opcional).
    elseif ($method === 'POST' && preg_match('#^/anular/([\w-]+)$#', $path, $m)) {
        $order = Orders::get($m[1]);
        if ($order === null || $order['status'] !== 'pagado') {
            http_response_code(404);
            page('Pedido no encontrado', '');
        } else {
            $refund = $vp->refundPayment($order['uuid'], $order['amount'], CUSTOMER_EMAIL);
            $order['status'] = 'anulado';
            Orders::save($order);
            error_log("Pedido {$order['orderId']} anulado ({$refund['status']})");
            page('Pago anulado', '<p>Estado de la anulación: <strong>' . e($refund['status']) . '</strong></p><p><a href="/">Volver</a></p>');
        }
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

// Fuente de verdad: siempre consulta el Payment en la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
function syncPayment(Virtualpos $vp, string $uuid): array
{
    if ($uuid === '') {
        throw new \RuntimeException('No llegó el uuid del pago');
    }
    $payment = $vp->getPayment($uuid)['payment']['order'];
    $orderId = $payment['merchant_internal_code'];
    $order = Orders::get($orderId) ?? ['orderId' => $orderId, 'amount' => $payment['amount'], 'uuid' => $uuid, 'status' => null];
    if (in_array($order['status'], FINAL_STATES, true)) {
        return $order; // ya procesado: no lo proceses dos veces
    }
    $order['status'] = $payment['status'];
    Orders::save($order);
    if ($order['status'] === 'pagado') {
        // Aquí confirmas el pedido en tu sistema: despacho, boleta, correo al cliente…
        error_log("Pedido $orderId pagado (autorización {$payment['auth_code']})");
    }
    return $order;
}

// El uuid puede llegar como JSON o como formulario.
function requestUuid(): ?string
{
    if (isset($_POST['uuid'])) {
        return (string) $_POST['uuid'];
    }
    $json = json_decode(file_get_contents('php://input'), true);
    return isset($json['uuid']) ? (string) $json['uuid'] : null;
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
