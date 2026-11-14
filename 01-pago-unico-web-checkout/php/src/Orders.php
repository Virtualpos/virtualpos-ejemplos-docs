<?php
// En tu sistema esto sería tu base de datos de pedidos. Aquí, un archivo JSON.

namespace Ejemplo;

final class Orders
{
    private const FILE = __DIR__ . '/../var/orders.json';

    public static function get(string $orderId): ?array
    {
        return self::all()[$orderId] ?? null;
    }

    public static function save(array $order): void
    {
        $all = self::all();
        $all[$order['orderId']] = $order;
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        file_put_contents(self::FILE, json_encode($all, JSON_PRETTY_PRINT), LOCK_EX);
    }

    private static function all(): array
    {
        return is_file(self::FILE) ? (json_decode(file_get_contents(self::FILE), true) ?? []) : [];
    }
}
