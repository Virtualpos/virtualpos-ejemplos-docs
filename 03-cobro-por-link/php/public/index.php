<?php
// Guía: Cobrar por link (email, WhatsApp o SMS)
// https://docs.virtualpos.cl/guias/cobro-por-link/

require __DIR__ . '/../src/bootstrap.php';

use Ejemplo\Charges;
use Ejemplo\Virtualpos;
use Ejemplo\VirtualposException;

const FINAL_STATES = ['pagado', 'rechazado', 'expirado', 'cancelado', 'anulado'];
const RETRY_STATES = ['rechazado', 'expirado', 'cancelado'];

$vp = Virtualpos::fromEnv();
$publicUrl = rtrim(getenv('PUBLIC_URL') ?: 'http://localhost:8000', '/');
$method = $_SERVER['REQUEST_METHOD'];
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

try {
    // 1. Panel del comercio: formulario para cobrar y listado de cobros.
    if ($method === 'GET' && $path === '/') {
        $rows = '';
        foreach (array_reverse(Charges::all()) as $c) {
            $sends = implode('<br>', array_map(
                fn ($s) => e("{$s['channel']} {$s['receiver']}") . (isset($s['error']) ? ' ✘ ' . e($s['error']) : ' ✔'),
                $c['sends'],
            ));
            $id = e($c['chargeId']);
            $actions = ($c['status'] === 'pendiente' ? "<form method=\"post\" action=\"/cobros/$id/enviar\"><button>Reenviar link</button></form>" : '')
                . (in_array($c['status'], RETRY_STATES, true) ? "<form method=\"post\" action=\"/cobros/$id/nuevo\"><button>Cobrar de nuevo</button></form>" : '')
                . "<form method=\"post\" action=\"/cobros/$id/estado\"><button>Consultar estado</button></form>";
            $rows .= "<tr><td><code>$id</code><br><a href=\"" . e($c['link']) . "\" target=\"_blank\">link de pago</a></td>"
                . '<td>$' . number_format($c['amount'], 0, ',', '.') . '</td><td><strong>' . e($c['status']) . "</strong></td><td>$sends</td><td>$actions</td></tr>";
        }
        page('Cobrar por link', '
            <form method="post" action="/cobros" style="display:grid;gap:.5rem">
              <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
              <label>Descripción <input name="description" value="Reserva de mesa" required></label>
              <fieldset><legend>Cliente</legend>
                <label>Nombre <input name="first_name" value="Ana" required></label>
                <label>Apellido <input name="last_name" value="Pérez" required></label>
                <label>RUT <input name="social_id" value="11111111-1" required></label>
                <label>Email <input name="email" type="email" value="cliente@example.com" required></label>
                <label>Teléfono <input name="phone" value="56912345678" pattern="569[0-9]{8}" required></label>
              </fieldset>
              <label>Enviar por <select name="channel">
                <option value="EMAIL">Email</option><option value="WHATSAPP">WhatsApp</option><option value="SMS">SMS</option>
              </select></label>
              <button>Crear cobro y enviar link</button>
            </form>
            <h2>Cobros</h2>
            <table border="1" cellpadding="4" style="border-collapse:collapse;width:100%">
              <tr><th>Cobro</th><th>Monto</th><th>Estado</th><th>Envíos</th><th></th></tr>' . $rows . '
            </table>');
    }

    // 2. Crea el Payment y envía el link al cliente por el canal elegido.
    elseif ($method === 'POST' && $path === '/cobros') {
        $customer = [
            'email' => $_POST['email'],
            'social_id' => $_POST['social_id'],
            'first_name' => $_POST['first_name'],
            'last_name' => $_POST['last_name'],
            'phone' => $_POST['phone'], // formato 569NNNNNNNN
        ];
        createCharge($vp, $publicUrl, (int) $_POST['amount'], $_POST['description'], $customer, $_POST['channel']);
        header('Location: /', true, 303);
    }

    // 3. Reenvía el link de un cobro pendiente (por ejemplo, si el cliente no lo encuentra).
    elseif ($method === 'POST' && preg_match('#^/cobros/([\w-]+)/enviar$#', $path, $m)) {
        $charge = Charges::get($m[1]) ?? throw new \RuntimeException('Cobro no encontrado');
        send($vp, $charge, lastChannel($charge));
        header('Location: /', true, 303);
    }

    // 4. Consulta el estado en la API (útil si el webhook no llegó).
    elseif ($method === 'POST' && preg_match('#^/cobros/([\w-]+)/estado$#', $path, $m)) {
        $charge = Charges::get($m[1]) ?? throw new \RuntimeException('Cobro no encontrado');
        syncPayment($vp, $charge['uuid']);
        header('Location: /', true, 303);
    }

    // 5. Cobrar de nuevo: un pago rechazado, expirado o cancelado ya no se puede pagar ni reenviar.
    //    Crea otro Payment con los mismos datos y envía el link nuevo.
    elseif ($method === 'POST' && preg_match('#^/cobros/([\w-]+)/nuevo$#', $path, $m)) {
        $old = Charges::get($m[1]) ?? throw new \RuntimeException('Cobro no encontrado');
        createCharge($vp, $publicUrl, $old['amount'], $old['description'], $old['customer'], lastChannel($old));
        header('Location: /', true, 303);
    }

    // 6. Webhook: Virtualpos avisa que el pago cambió. Consulta el resultado y responde 200.
    elseif ($method === 'POST' && $path === '/webhook') {
        $uuid = requestUuid();
        http_response_code(200);
        error_log("Webhook recibido para $uuid");
        if ($uuid !== null) {
            syncPayment($vp, $uuid);
        }
    }

    // 7. Retorno: el cliente pagó desde el link y Virtualpos lo trae de vuelta con el uuid por POST.
    //    Es tu cliente, no tu equipo: muéstrale una confirmación simple.
    elseif ($method === 'POST' && $path === '/gracias') {
        $charge = syncPayment($vp, requestUuid() ?? '');
        $paid = $charge['status'] === 'pagado';
        page($paid ? '¡Gracias por tu pago!' : 'El pago no se completó', $paid
            ? '<p>Recibimos tu pago de $' . number_format($charge['amount'], 0, ',', '.') . '.</p>'
            : '<p>No se realizó ningún cargo. Si quieres intentarlo de nuevo, pídenos un nuevo link de pago.</p>');
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

// Crea el Payment de un cobro y envía el link. merchant_internal_code une el pago con tu cobro.
function createCharge(Virtualpos $vp, string $publicUrl, int $amount, string $description, array $customer, string $channel): array
{
    $chargeId = 'COB-' . (int) (microtime(true) * 1000);
    $res = $vp->createPayment($customer + [
        'amount' => $amount,
        'description' => $description,
        'merchant_internal_code' => $chargeId,
        'merchant_internal_channel' => 'cobro-por-link',
        'return_url' => "$publicUrl/gracias",
        'callback_url' => "$publicUrl/webhook",
    ]);
    $order = $res['payment']['order'];
    // url_redirect abre el Web Checkout de este pago. Guárdalo por si quieres compartirlo a mano.
    $charge = [
        'chargeId' => $chargeId, 'amount' => $amount, 'description' => $description, 'uuid' => $order['uuid'],
        'link' => $res['url_redirect'], 'status' => $order['status'], 'customer' => $customer, 'sends' => [],
    ];
    Charges::save($charge);
    error_log("Payment {$order['uuid']} creado para $chargeId");
    return send($vp, $charge, $channel);
}

// Envía el link con POST /v3/payment/{uuid}/send. Para WhatsApp y SMS el destinatario es el teléfono con +56.
function send(Virtualpos $vp, array $charge, string $channel): array
{
    $receiver = $channel === 'EMAIL' ? $charge['customer']['email'] : '+' . $charge['customer']['phone'];
    try {
        $result = $vp->sendPayment($charge['uuid'], $channel, $receiver);
        $charge['sends'][] = ['channel' => $channel, 'receiver' => $receiver, 'at' => gmdate('c')];
        error_log("Link de {$charge['chargeId']} enviado por $channel a $receiver ({$result['send_status']})");
    } catch (VirtualposException $err) {
        // El cobro ya existe: si el envío falla, corrige el dato y reenvía. No crees otro Payment.
        $charge['sends'][] = ['channel' => $channel, 'receiver' => $receiver, 'error' => $err->hint ?? $err->getMessage()];
        error_log("No se pudo enviar {$charge['chargeId']}: {$err->getMessage()}");
    }
    Charges::save($charge);
    return $charge;
}

function lastChannel(array $charge): string
{
    return $charge['sends'] ? end($charge['sends'])['channel'] : 'EMAIL';
}

// Fuente de verdad: siempre consulta el Payment en la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
function syncPayment(Virtualpos $vp, string $uuid): array
{
    if ($uuid === '') {
        throw new \RuntimeException('No llegó el uuid del pago');
    }
    $payment = $vp->getPayment($uuid)['payment']['order'];
    $charge = Charges::get($payment['merchant_internal_code'])
        ?? throw new \RuntimeException("El pago $uuid no corresponde a un cobro de este sistema");
    if (in_array($charge['status'], FINAL_STATES, true)) {
        return $charge; // ya procesado: no lo proceses dos veces
    }
    $charge['status'] = $payment['status'];
    Charges::save($charge);
    if ($charge['status'] === 'pagado') {
        // Aquí registras el pago en tu sistema: emite la boleta, confirma la reserva…
        error_log("Cobro {$charge['chargeId']} pagado (autorización {$payment['auth_code']})");
    }
    return $charge;
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
        '<body style="font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem"><h1>' . e($title) . '</h1>' . $body . '</body>';
}
