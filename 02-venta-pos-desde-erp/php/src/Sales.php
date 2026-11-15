<?php
// En tu sistema esto sería tu base de datos de ventas. Aquí, un archivo JSON.

namespace Ejemplo;

final class Sales
{
    private const FILE = __DIR__ . '/../var/sales.json';

    public static function get(string $saleId): ?array
    {
        return self::read()['sales'][$saleId] ?? null;
    }

    public static function all(): array
    {
        return self::read()['sales'];
    }

    public static function save(array $sale): void
    {
        self::update(function (array $db) use ($sale) {
            $db['sales'][$sale['saleId']] = $sale;
            return $db;
        });
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
        return $db ?? ['sales' => [], 'events' => []];
    }

    private static function update(callable $change): void
    {
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        $fh = fopen(self::FILE, 'c+');
        flock($fh, LOCK_EX);
        $db = json_decode(stream_get_contents($fh), true) ?? ['sales' => [], 'events' => []];
        $db = $change($db);
        ftruncate($fh, 0);
        rewind($fh);
        fwrite($fh, json_encode($db, JSON_PRETTY_PRINT));
        flock($fh, LOCK_UN);
        fclose($fh);
    }
}
