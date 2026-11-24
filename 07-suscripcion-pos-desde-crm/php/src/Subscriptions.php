<?php
// En tu CRM esto sería tu base de datos: una fila por suscripción enviada al POS. Aquí, un archivo JSON.
// Cada fila: subscriptionId, customer, posSerial, status, sentAt (segundos), finalCheck y card.

namespace Ejemplo;

final class Subscriptions
{
    private const FILE = __DIR__ . '/../var/subscriptions.json';

    public static function get(string $subscriptionId): ?array
    {
        return self::all()[$subscriptionId] ?? null;
    }

    // En el orden en que se crearon.
    public static function all(): array
    {
        return is_file(self::FILE) ? (json_decode(file_get_contents(self::FILE), true) ?? []) : [];
    }

    // Cambia una fila con el archivo bloqueado: el webhook y la recarga de la página pueden escribir a la vez.
    public static function update(string $subscriptionId, callable $change): void
    {
        if (!is_dir(dirname(self::FILE))) {
            mkdir(dirname(self::FILE), 0777, true);
        }
        $fh = fopen(self::FILE, 'c+');
        flock($fh, LOCK_EX);
        $all = json_decode(stream_get_contents($fh), true) ?? [];
        $all[$subscriptionId] = $change($all[$subscriptionId] ?? null);
        ftruncate($fh, 0);
        rewind($fh);
        fwrite($fh, json_encode($all, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
        flock($fh, LOCK_UN);
        fclose($fh);
    }

    public static function save(array $subscription): void
    {
        self::update($subscription['subscriptionId'], fn () => $subscription);
    }
}
