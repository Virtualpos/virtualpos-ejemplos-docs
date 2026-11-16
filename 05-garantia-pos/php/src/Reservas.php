<?php
// En tu sistema esto sería tu base de datos de reservas. Aquí, un archivo JSON.
// Cada reserva guarda sus operaciones en el POS: checkin, checkout y prints (impresiones).

namespace Ejemplo;

final class Reservas
{
    private const FILE = __DIR__ . '/../var/reservas.json';

    public static function get(string $reservaId): ?array
    {
        return self::read()['reservas'][$reservaId] ?? null;
    }

    public static function all(): array
    {
        return self::read()['reservas'];
    }

    public static function save(array $reserva): void
    {
        self::update(function (array $db) use ($reserva) {
            $db['reservas'][$reserva['reservaId']] = $reserva;
            return $db;
        });
    }

    // Ubica la reserva y la operación de un externalPaymentId: RESERVA-1, RESERVA-1-CHECKOUT o RESERVA-1-COMPROBANTE-n.
    public static function findOperation(string $externalPaymentId): ?array
    {
        foreach (self::all() as $reserva) {
            foreach (['checkin', 'checkout'] as $kind) {
                if (($reserva[$kind]['externalPaymentId'] ?? null) === $externalPaymentId) {
                    return [$reserva['reservaId'], $kind];
                }
            }
            foreach ($reserva['prints'] as $i => $print) {
                if ($print['externalPaymentId'] === $externalPaymentId) {
                    return [$reserva['reservaId'], "prints.$i"];
                }
            }
        }
        return null;
    }

    // Registra un eventId de webhook. Devuelve false si ya estaba: es un reintento.
    public static function markEvent(string $eventId): bool
    {
        $new = false;
        self::update(function (array $db) use ($eventId, &$new) {
            $new = !in_array($eventId, $db['events'], true);
            $db['events'][] = $eventId;
            return $db;
        });
        return $new;
    }

    private static function read(): array
    {
        $db = is_file(self::FILE) ? json_decode(file_get_contents(self::FILE), true) : null;
        return $db ?? ['reservas' => [], 'events' => []];
    }

    private static function update(callable $change): void
    {
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        $fh = fopen(self::FILE, 'c+');
        flock($fh, LOCK_EX);
        $db = json_decode(stream_get_contents($fh), true) ?? ['reservas' => [], 'events' => []];
        $db = $change($db);
        ftruncate($fh, 0);
        rewind($fh);
        fwrite($fh, json_encode($db, JSON_PRETTY_PRINT));
        flock($fh, LOCK_UN);
        fclose($fh);
    }
}
