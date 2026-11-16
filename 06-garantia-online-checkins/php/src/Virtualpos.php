<?php
// Cliente mínimo de Checkins de Virtualpos: firma JWT con ciat, requests y errores con pistas.
// Docs: https://docs.virtualpos.cl/checkins/introduccion/

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

    // Checkins firma con la hora actual: JWT HS256 con {"ciat": <segundos>}. Vale 5 minutos, así que se genera en cada request.
    public function signature(): string
    {
        return JWT::encode(['ciat' => time()], $this->secretKey, 'HS256');
    }

    // POST /v3/checkins — https://docs.virtualpos.cl/checkins/crear-checkin/
    public function createCheckin(array $checkin): array
    {
        // Ambas URLs van en Base64.
        $checkin['callback_url'] = base64_encode($checkin['callback_url']);
        $checkin['return_url'] = base64_encode($checkin['return_url']);
        return $this->request('POST', '/v3/checkins', $checkin);
    }

    // POST /v3/checkins/cof/{uuid_cli}/{card_reference_id} — cliente y tarjeta ya registrados, garantía inmediata y sin formulario.
    public function createCheckinWithSavedCard(string $uuidCli, string $cardReferenceId, array $checkin): array
    {
        $checkin['callback_url'] = base64_encode($checkin['callback_url']);
        return $this->request('POST', '/v3/checkins/cof/' . rawurlencode($uuidCli) . '/' . rawurlencode($cardReferenceId), $checkin);
    }

    // GET /v3/checkins/{uuid} — https://docs.virtualpos.cl/checkins/recuperar-checkin/
    public function getCheckin(string $uuid): array
    {
        return $this->request('GET', '/v3/checkins/' . rawurlencode($uuid));
    }

    // POST /v3/checkins/authorize — garantía diferida.
    public function authorize(string $uuid, int $amount): array
    {
        return $this->request('POST', '/v3/checkins/authorize', ['uuid' => $uuid, 'amount' => $amount]);
    }

    // POST /v3/checkins/incremental
    public function incremental(string $uuid, int $amount, string $description): array
    {
        return $this->request('POST', '/v3/checkins/incremental', ['uuid' => $uuid, 'amount' => $amount, 'description' => $description]);
    }

    // POST /v3/checkins/checkout — cobra el monto final.
    public function checkout(string $uuid, int $finalAmount): array
    {
        return $this->request('POST', '/v3/checkins/checkout', ['uuid' => $uuid, 'final_checkout_amount' => $finalAmount]);
    }

    // DELETE /v3/checkins/{uuid} — libera la garantía.
    public function cancel(string $uuid): array
    {
        return $this->request('DELETE', '/v3/checkins/' . rawurlencode($uuid));
    }

    // POST /v3/checkins/additional-charges — después del checkout.
    public function additionalCharge(string $uuid, int $amount, string $reason): array
    {
        return $this->request('POST', '/v3/checkins/additional-charges', ['uuid' => $uuid, 'amount' => $amount, 'charge_reason' => $reason]);
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

        // Los errores llegan casi siempre con HTTP 200: revisa también status y error en el cuerpo.
        if ($status >= 400 || ($data['status'] ?? null) === 'NOK' || isset($data['error'])) {
            throw new VirtualposException(
                (int) ($data['code'] ?? $status),
                $data['error']['error_code'] ?? null,
                $data['error']['message'] ?? $data['message'] ?? "HTTP $status",
            );
        }
        return $data;
    }
}
