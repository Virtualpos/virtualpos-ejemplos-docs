<?php

namespace Ejemplo;

// Error de la API de POS Cloud to Cloud, con una pista para los casos más frecuentes.
final class PosException extends \RuntimeException
{
    public readonly ?string $hint;

    public function __construct(public readonly int $httpStatus, string $message)
    {
        parent::__construct("HTTP $httpStatus: $message");
        $this->hint = self::hintFor($httpStatus);
    }

    public static function hintFor(int $httpStatus): ?string
    {
        return match (true) {
            $httpStatus === 401 => 'API key inválida. Revisa VIRTUALPOS_POS_API_KEY y que sea del mismo ambiente que VIRTUALPOS_POS_API_URL (integración o producción). No es la API KEY del portal de comercio.',
            $httpStatus === 403 => 'El POS no está habilitado para POS Cloud to Cloud en tu comercio. Revisa VIRTUALPOS_POS_SERIAL o pídele a tu ejecutivo que lo habilite.',
            $httpStatus === 404 => 'No existe esa operación para tu comercio. Revisa el paymentRequestId.',
            $httpStatus === 0 || $httpStatus >= 500 => 'No sabes si la operación llegó al POS. Reintenta con el mismo externalPaymentId, nunca con uno nuevo: si ya se aprobó, Virtualpos no la vuelve a enviar.',
            default => null,
        };
    }
}
