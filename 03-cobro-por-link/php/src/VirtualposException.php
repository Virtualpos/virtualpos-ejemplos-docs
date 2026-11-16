<?php

namespace Ejemplo;

final class VirtualposException extends \RuntimeException
{
    public readonly ?string $hint;

    public function __construct(
        public readonly int $httpStatus,
        public readonly ?string $errorCode,
        public readonly string $apiMessage,
    ) {
        parent::__construct(($errorCode ?? (string) $httpStatus) . ': ' . $apiMessage);
        $this->hint = self::hintFor($apiMessage);
    }

    // Pistas para los errores más frecuentes que recibe soporte.
    // Se busca por mensaje porque el mismo caso puede venir con distintos códigos según el endpoint.
    public static function hintFor(string $message): ?string
    {
        $m = mb_strtolower($message);
        return match (true) {
            str_contains($m, 'missing required request parameters') =>
                'Faltan los headers Authorization o Signature.',
            str_contains($m, 'no cumple con formato para el channel') =>
                'El destinatario no corresponde al canal: EMAIL necesita un email; WHATSAPP y SMS, un teléfono con +56, por ejemplo +56912345678.',
            str_contains($m, 'channel no permitido') =>
                'Canal no reconocido o no habilitado para tu cuenta. Usa EMAIL, WHATSAPP o SMS; si es uno de ellos, pídele a tu ejecutivo que lo habilite.',
            str_contains($m, 'parámetro channel'), str_contains($m, 'parametro channel') =>
                'Falta el canal: envía channel con EMAIL, WHATSAPP o SMS.',
            str_contains($m, 'parámetro receiver'), str_contains($m, 'parametro receiver') =>
                'Falta el destinatario (receiver).',
            str_contains($m, 'no existe la orden') =>
                'No hay un pago pendiente con ese uuid: ya se pagó o terminó (al enviar, la API responde así), o el uuid es de otro ambiente. Consulta su estado.',
            str_contains($m, 'firma') =>
                'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {"api_key": "<tu API KEY>"}.',
            str_contains($m, 'no existe el comercio'), str_contains($m, 'no existe cuenta') =>
                'Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?',
            str_contains($m, 'contrato'), str_contains($m, 'no está habilitado'), str_contains($m, 'no esta habilitado') =>
                'El producto no está activo para tu cuenta en este ambiente. Pídele a tu ejecutivo que lo habilite.',
            default => null,
        };
    }
}
