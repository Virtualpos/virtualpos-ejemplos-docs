<?php
// En tu sistema esto sería tu base de datos de cobros. Aquí, un archivo JSON.

namespace Ejemplo;

final class Charges
{
    private const FILE = __DIR__ . '/../var/charges.json';

    public static function get(string $chargeId): ?array
    {
        return self::all()[$chargeId] ?? null;
    }

    public static function save(array $charge): void
    {
        $all = self::all();
        $all[$charge['chargeId']] = $charge;
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        file_put_contents(self::FILE, json_encode($all, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE), LOCK_EX);
    }

    public static function all(): array
    {
        return is_file(self::FILE) ? (json_decode(file_get_contents(self::FILE), true) ?? []) : [];
    }
}
