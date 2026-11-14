<?php
// Cliente mínimo de la API de Virtualpos: firma JWT, requests y errores con pistas.
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

    // La firma es un JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
    public function signature(): string
    {
        return JWT::encode(['api_key' => $this->apiKey], $this->secretKey, 'HS256');
    }

    // Anular usa otra firma: JWT HS256 con la hora actual (ciat, en segundos) y un email. Tolera 5 minutos.
    public function refundSignature(string $email): string
    {
        return JWT::encode(['ciat' => time(), 'email' => $email], $this->secretKey, 'HS256');
    }

    // POST /v3/payment/ — https://docs.virtualpos.cl/payments/crear-un-objeto-payment/
    public function createPayment(array $payment): array
    {
        // Ambas URLs van en Base64 y deben enviarse juntas: si falta una, Virtualpos no guarda ninguna.
        $payment['return_url'] = base64_encode($payment['return_url']);
        $payment['callback_url'] = base64_encode($payment['callback_url']);
        return $this->request('POST', '/v3/payment/', $payment);
    }

    // GET /v3/payment/{uuid} — https://docs.virtualpos.cl/payments/recuperar-un-objeto-payment/
    public function getPayment(string $uuid): array
    {
        return $this->request('GET', '/v3/payment/' . rawurlencode($uuid));
    }

    // POST /v3/payment/{uuid}/refund — https://docs.virtualpos.cl/payments/anular-un-payment/
    public function refundPayment(string $uuid, int $amount, string $email): array
    {
        return $this->request('POST', '/v3/payment/' . rawurlencode($uuid) . '/refund', ['amount' => $amount], $this->refundSignature($email));
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
