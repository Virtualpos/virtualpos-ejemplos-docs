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
        $this->hint = self::hintFor($errorCode, $apiMessage);
    }

    // Pistas para los errores más frecuentes al enviar suscripciones al POS.
    public static function hintFor(?string $errorCode, string $message): ?string
    {
        $m = mb_strtolower($message);
        return match (true) {
            str_contains($m, 'missing required request parameters') =>
                'Faltan los headers Authorization o Signature.',
            str_contains($m, 'firma') =>
                'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {"api_key": "<tu API KEY>"}.',
            str_contains($m, 'no existe el comercio'), str_contains($m, 'no existe cuenta') =>
                'Tu API KEY no existe en este ambiente. Revisa que la URL y las llaves sean del mismo ambiente.',
            str_contains($m, 'sin contrato pat') =>
                'Tu cuenta no tiene Suscripciones activo en este ambiente. Pídele a tu ejecutivo que lo habilite.',
            $errorCode === 'E-PTT-002' =>
                'Solo se puede enviar una suscripción recién creada (SUSCRIBIENDO). Si falló o venció, crea una nueva.',
            $errorCode === 'E-PTT-003' =>
                'El plan no está habilitado para el POS: créalo con show_in_terminal "T" (composer crear-plan).',
            $errorCode === 'E-PTT-005' =>
                'La suscripción ya se envió a otro POS. Crea una nueva para enviarla a este.',
            $errorCode === 'E-PTT-006' =>
                'Ese POS no está asignado a tu comercio en este ambiente. Revisa el serial.',
            $errorCode === 'E-PTT-011' =>
                'El plan pide dirección de despacho: esos planes no se pueden suscribir en el POS.',
            $errorCode === 'E-PTT-012' =>
                'No hay un envío previo de esta suscripción a ese POS.',
            in_array($errorCode, ['E-PTT-008', 'E-PTT-009', 'E-PTT-013', 'E-PTT-014'], true) =>
                'No se pudo llegar al POS. Revisa que esté encendido y con internet, y vuelve a intentar.',
            default => null,
        };
    }
}
