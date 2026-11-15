<?php
// Cliente mínimo de POS Cloud to Cloud (Ventas con tarjeta): requests, errores con pistas y firma de webhooks.
// Docs: https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/introduccion/

namespace Ejemplo;

final class VirtualposPos
{
    public const FINAL = ['APPROVED', 'REJECTED', 'REVERSED', 'FAILED', 'EXPIRED', 'CANCELLED'];

    public function __construct(private string $apiUrl, private string $apiKey)
    {
        $this->apiUrl = rtrim($apiUrl, '/');
    }

    public static function fromEnv(): self
    {
        $url = getenv('VIRTUALPOS_POS_API_URL');
        $key = getenv('VIRTUALPOS_POS_API_KEY');
        if (!$url || !$key) {
            throw new \RuntimeException('Configura VIRTUALPOS_POS_API_URL y VIRTUALPOS_POS_API_KEY en tu archivo .env');
        }
        return new self($url, $key);
    }

    public function request(string $method, string $path, ?array $body = null): array
    {
        $ch = curl_init($this->apiUrl . $path);
        curl_setopt_array($ch, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-Api-Key: ' . $this->apiKey],
            CURLOPT_POSTFIELDS => $body === null ? null : json_encode($body),
        ]);
        $raw = curl_exec($ch);
        $status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $curlError = curl_error($ch);
        if ($raw === false) {
            throw new PosException(0, "Sin respuesta de Virtualpos: $curlError");
        }
        $data = json_decode($raw, true) ?? []; // 401 no trae body
        if ($status >= 400) {
            throw new PosException($status, $data['message'] ?? ($status === 401 ? 'Unauthorized' : 'Error'));
        }
        return $data;
    }

    // POST /api/v1/pos-operations — https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/crear-una-venta/
    // externalPaymentId es tu ID de la venta: repetirlo nunca cobra dos veces.
    public function createSale(string $posSerial, string $externalPaymentId, int $amount, int $tipAmount, string $callbackUrl): array
    {
        return $this->request('POST', '/api/v1/pos-operations', [
            'posSerial' => $posSerial,
            'operationType' => 'SALE',
            'externalPaymentId' => $externalPaymentId,
            'amount' => $amount,
            'tipAmount' => $tipAmount,
            'currency' => 'CLP',
            'callbackUrl' => $callbackUrl,
        ]);
    }

    // GET /api/v1/pos-operations/{paymentRequestId} — estado actual, sin el detalle de la tarjeta.
    public function getOperation(string $paymentRequestId): array
    {
        return $this->request('GET', '/api/v1/pos-operations/' . rawurlencode($paymentRequestId));
    }

    // GET .../callback-event — el webhook armado con el estado actual, incluido resultPayload.
    public function getCallbackEvent(string $paymentRequestId): array
    {
        return $this->request('GET', '/api/v1/pos-operations/' . rawurlencode($paymentRequestId) . '/callback-event');
    }

    // GET /api/v1/pos-operations?limit=n
    public function listOperations(int $limit = 1): array
    {
        return $this->request('GET', "/api/v1/pos-operations?limit=$limit");
    }

    // Verifica un webhook: HMAC-SHA256 del body crudo con tu secreto, y que no tenga más de 5 minutos.
    // https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/webhooks/#verificar-la-firma
    public static function verifyWebhook(string $rawBody, string $signature, string $timestamp, string $secret): ?string
    {
        $expected = 'sha256=' . hash_hmac('sha256', $rawBody, $secret);
        if (!hash_equals($expected, $signature)) {
            return 'firma no coincide';
        }
        $time = strtotime($timestamp);
        if ($time === false || abs(time() - $time) > 300) {
            return 'timestamp fuera de los 5 minutos';
        }
        return null;
    }
}
