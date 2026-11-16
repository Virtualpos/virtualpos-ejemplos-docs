<?php
// En tu sistema esto sería tu base de datos de clientes y suscripciones. Aquí, un archivo JSON.

namespace Ejemplo;

final class Subscriptions
{
    private const FILE = __DIR__ . '/../var/subscriptions.json';

    public static function get(string $subscriptionId): ?array
    {
        return self::all()[$subscriptionId] ?? null;
    }

    public static function save(array $subscription): void
    {
        $all = self::all();
        $all[$subscription['subscriptionId']] = $subscription;
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
