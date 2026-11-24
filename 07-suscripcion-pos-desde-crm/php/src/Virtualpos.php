<?php
// Cliente mínimo de la API de Virtualpos para suscripciones presenciales: firma JWT, requests y errores con pistas.
// Docs: https://docs.virtualpos.cl/general/autenticacion-y-firma/

namespace Ejemplo;

use Firebase\JWT\JWT;

final class Virtualpos
{
    private string $apiUrl;

    public function __construct(string $apiUrl, private string $apiKey, private string $secretKey)
    {
        if ($apiUrl === '' || $apiKey === '' || $secretKey === '') {
            throw new \InvalidArgumentException('Configura VIRTUALPOS_API_URL, VIRTUALPOS_API_KEY y VIRTUALPOS_SECRET_KEY en tu archivo .env');
        }
        $this->apiUrl = rtrim($apiUrl, '/');
    }

    public static function fromEnv(): self
    {
        return new self(
            getenv('VIRTUALPOS_API_URL') ?: '',
            getenv('VIRTUALPOS_API_KEY') ?: '',
            getenv('VIRTUALPOS_SECRET_KEY') ?: '',
        );
    }

    public function apiUrl(): string
    {
        return $this->apiUrl;
    }

    // JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
    public function signature(): string
    {
        return JWT::encode(['api_key' => $this->apiKey], $this->secretKey, 'HS256');
    }

    // POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/
    public function createPlan(array $plan): array
    {
        return $this->request('POST', '/v3/plan', $plan);
    }

    // POST /v3/suscription — https://docs.virtualpos.cl/suscripciones/suscription/crear-una-suscription/
    public function createSubscription(array $data): array
    {
        // Ambas URLs van en Base64.
        $data['return_url'] = base64_encode($data['return_url']);
        $data['callback_url'] = base64_encode($data['callback_url']);
        return $this->request('POST', '/v3/suscription', $data);
    }

    // POST /v3/suscription/push-to-terminal — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/enviar-suscripcion-al-pos/
    public function sendToPos(string $posSerial, string $subscriptionId): array
    {
        return $this->request('POST', '/v3/suscription/push-to-terminal', [
            'terminal_serial_number' => $posSerial,
            'suscription_uuid' => $subscriptionId,
        ]);
    }

    // POST /v3/suscription/push-to-terminal/cancel — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/cancelar-envio-al-pos/
    public function cancelSendToPos(string $posSerial, string $subscriptionId, string $forcedBy, string $reason): array
    {
        return $this->request('POST', '/v3/suscription/push-to-terminal/cancel', [
            'terminal_serial_number' => $posSerial,
            'suscription_uuid' => $subscriptionId,
            'forced_by' => $forcedBy,
            'reason' => $reason,
        ]);
    }

    // GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/
    public function getSubscription(string $subscriptionId): array
    {
        return $this->request('GET', '/v3/suscription/' . rawurlencode($subscriptionId));
    }

    public function request(string $method, string $path, ?array $body = null): array
    {
        $ch = curl_init($this->apiUrl . $path);
        curl_setopt_array($ch, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_HTTPHEADER => [
                'Content-Type: application/json',
                'Authorization: ' . $this->apiKey,
                'Signature: ' . $this->signature(),
            ],
        ]);
        if ($body !== null) {
            curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
        }
        $raw = curl_exec($ch);
        if ($raw === false) {
            throw new \RuntimeException('No se pudo conectar con Virtualpos: ' . curl_error($ch));
        }
        $status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $data = json_decode($raw, true) ?? [];

        // Algunos errores llegan con HTTP 200: revisa también status y error en el cuerpo.
        if ($status >= 400 || ($data['status'] ?? null) === 'NOK' || isset($data['error'])) {
            throw new VirtualposException(
                $status,
                $data['error']['error_code'] ?? null,
                $data['error']['message'] ?? $data['message'] ?? "HTTP $status",
            );
        }
        return $data;
    }
}
