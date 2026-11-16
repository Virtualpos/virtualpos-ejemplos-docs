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
            str_contains($m, 'firma no válida') =>
                'Pagar y suscribir se firma con la hora actual: payload {"ciat": <timestamp en segundos>}, no con {"api_key"}.',
            str_contains($m, 'parámetros en la firma') =>
                'Planes, suscripciones y cargos se firman con payload {"api_key": "<tu API KEY>"}, no con {"ciat"}.',
            str_contains($m, 'firma') =>
                'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.',
            str_contains($m, 'no es de tipo monto_variable') =>
                'Pagar y suscribir solo acepta planes de tipo MONTO_VARIABLE. Crea uno con composer crear-plan.',
            str_contains($m, 'charge_date') =>
                'La fecha del cargo (charge_date) debe tener formato AAAA-MM-DD y ser hoy o una fecha futura.',
            str_contains($m, 'condiciones para realizar el reintento') =>
                'Solo se puede reintentar un cargo en estado rechazado.',
            str_contains($m, 'no existe el comercio'), str_contains($m, 'no existe cuenta') =>
                'Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?',
            str_contains($m, 'contrato'), str_contains($m, 'no está habilitado'), str_contains($m, 'no esta habilitado') =>
                'El producto no está activo para tu cuenta en este ambiente. Pídele a tu ejecutivo que lo habilite.',
            default => null,
        };
    }
}
