<?php
// Cliente mínimo de la API de Virtualpos para suscripciones: firmas JWT, requests y errores con pistas.
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

    // Planes, suscripciones y cargos: JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
    public function signature(): string
    {
        return JWT::encode(['api_key' => $this->apiKey], $this->secretKey, 'HS256');
    }

    // Pagar y suscribir: JWT HS256 con la hora actual (ciat, en segundos).
    public function ciatSignature(): string
    {
        return JWT::encode(['ciat' => time()], $this->secretKey, 'HS256');
    }

    // POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/
    public function createPlan(array $plan): array
    {
        return $this->request('POST', '/v3/plan', $plan);
    }

    // POST /v3/pay-and-subscribe — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/iniciar-transaccion/
    public function startPayAndSubscribe(array $data): array
    {
        // Ambas URLs van en Base64.
        $data['return_url'] = base64_encode($data['return_url']);
        $data['callback_url'] = base64_encode($data['callback_url']);
        return $this->request('POST', '/v3/pay-and-subscribe', $data, $this->ciatSignature());
    }

    // GET /v3/pay-and-subscribe/{id} — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/obtener-estado/
    public function getPayAndSubscribe(string $subscriptionId): array
    {
        return $this->request('GET', '/v3/pay-and-subscribe/' . rawurlencode($subscriptionId), null, $this->ciatSignature());
    }

    // GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/
    public function getSubscription(string $subscriptionId): array
    {
        return $this->request('GET', '/v3/suscription/' . rawurlencode($subscriptionId));
    }

    // POST /v3/charge — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge/
    public function scheduleCharge(string $subscriptionId, string $chargeDate, int $amount, string $description, string $internalCode): array
    {
        return $this->request('POST', '/v3/charge', [
            'suscription_id' => $subscriptionId,
            'charge_date' => $chargeDate,
            'amount' => $amount,
            'description' => $description,
            'internal_code' => $internalCode,
        ]);
    }

    // POST /v3/charge/online — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge-online/
    public function chargeNow(string $subscriptionId, int $amount, string $description, string $internalCode): array
    {
        return $this->request('POST', '/v3/charge/online', [
            'suscription_id' => $subscriptionId,
            'amount' => $amount,
            'description' => $description,
            'internal_code' => $internalCode,
        ]);
    }

    // GET /v3/suscription/{id}/charges — https://docs.virtualpos.cl/suscripciones/charge/listar-charges/
    public function listCharges(string $subscriptionId): array
    {
        return $this->request('GET', '/v3/suscription/' . rawurlencode($subscriptionId) . '/charges');
    }

    // GET /v3/charge/{id}/retry — https://docs.virtualpos.cl/suscripciones/charge/reintentar-charge/
    public function retryCharge(string $chargeId): array
    {
        return $this->request('GET', '/v3/charge/' . rawurlencode($chargeId) . '/retry');
    }

    // PUT /v3/suscription/{id}/changecard — https://docs.virtualpos.cl/suscripciones/suscription/generar-link-de-cambio-de-tarjeta/
    public function changeCardLink(string $subscriptionId): array
    {
        return $this->request('PUT', '/v3/suscription/' . rawurlencode($subscriptionId) . '/changecard');
    }

    // DELETE /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/cancelar-una-suscription/
    public function cancelSubscription(string $subscriptionId): array
    {
        return $this->request('DELETE', '/v3/suscription/' . rawurlencode($subscriptionId));
    }

    public function request(string $method, string $path, ?array $body = null, ?string $signature = null): array
    {
        $ch = curl_init($this->apiUrl . $path);
        curl_setopt_array($ch, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_HTTPHEADER => [
                'Content-Type: application/json',
                'Authorization: ' . $this->apiKey,
                'Signature: ' . ($signature ?? $this->signature()),
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

        // Los errores llegan con HTTP 200: revisa también status y error en el cuerpo.
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
