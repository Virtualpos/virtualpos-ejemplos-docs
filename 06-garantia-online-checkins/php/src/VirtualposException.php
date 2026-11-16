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

    // Pistas para los errores más frecuentes al integrar Checkins.
    public static function hintFor(?string $errorCode, string $message): ?string
    {
        return match ($errorCode) {
            'E-021' => 'Firma no válida. En Checkins el payload del JWT es {"ciat": <hora actual en segundos>}, no {"api_key": ...} como en Payments.',
            'E-025' => 'La firma no coincide. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.',
            'E-022', 'E-023' => 'La firma usa la hora actual (ciat) y tolera 5 minutos. Genera una firma nueva en cada request y sincroniza el reloj de tu servidor (NTP).',
            'E-002' => 'Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?',
            'E-CHK-305', 'E-CHK-306' => 'El check-in no está en un estado que permita esta operación. Consulta su estado con GET /v3/checkins/{uuid}.',
            'E-CHK-308' => 'El monto supera lo permitido. El checkout no puede exceder la garantía autorizada: usa un incremental antes o un cargo adicional después.',
            default => str_contains(mb_strtolower($message), 'missing required request parameters')
                ? 'Faltan los headers Authorization o Signature.'
                : null,
        };
    }
}
