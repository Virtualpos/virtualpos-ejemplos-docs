<?php
// En tu sistema esto sería tu base de datos de reservas. Aquí, un archivo JSON.

namespace Ejemplo;

final class Reservas
{
    private const FILE = __DIR__ . '/../var/reservas.json';

    public static function get(string $reservaId): ?array
    {
        return self::all()[$reservaId] ?? null;
    }

    public static function byUuid(string $uuid): ?array
    {
        foreach (self::all() as $r) {
            if ($r['uuid'] === $uuid) {
                return $r;
            }
        }
        return null;
    }

    public static function save(array $reserva): void
    {
        $all = self::all();
        $all[$reserva['reservaId']] = array_merge($all[$reserva['reservaId']] ?? [], $reserva);
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        file_put_contents(self::FILE, json_encode($all, JSON_PRETTY_PRINT), LOCK_EX);
    }

    public static function all(): array
    {
        return is_file(self::FILE) ? (json_decode(file_get_contents(self::FILE), true) ?? []) : [];
    }
}
