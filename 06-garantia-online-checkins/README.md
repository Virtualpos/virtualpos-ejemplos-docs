# Tomar y cobrar una garantía online con Checkins

Ejemplo de la guía [Tomar y cobrar una garantía online con Checkins](https://docs.virtualpos.cl/guias/garantia-online-checkins/).

Un sistema mínimo de arriendo de autos. Muestra el ciclo completo de una garantía:

1. Crea un check-in y lleva al cliente al formulario de Virtualpos, donde ingresa su tarjeta.
2. Recibe el webhook en `/webhook` y al cliente de vuelta en `/retorno`. Consulta el check-in en la API para conocer el estado y confirma la reserva una sola vez.
3. Toma la garantía ahora (inmediata) o más tarde (diferida), y la aumenta si el arriendo se extiende.
4. Al devolver el auto, cobra el monto final (checkout) o libera la garantía sin cobrar.
5. Después del checkout, cobra un cargo adicional (multas, daños).
6. Para un cliente que vuelve, crea una nueva garantía con su tarjeta guardada, sin formulario.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus llaves de Sandbox.

| | Requisitos | Instalar | Verificar llaves | Iniciar | URL |
| --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `./mvnw spring-boot:run` | http://localhost:8080 |

En el formulario usa una [tarjeta de prueba](https://docs.virtualpos.cl/general/datos-de-prueba-sandbox/): VISA crédito `4051 8856 0044 6623`, CVV `123` y cualquier fecha futura, para una garantía aprobada. La Mastercard `5186 0595 5959 0568` es rechazada.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel, el formulario no puede devolver al cliente a tu `return_url` ni enviarte el webhook.

> El webhook de Checkins llega con el header `Content-Type: application/json, application/x-www-form-urlencoded` y un cuerpo JSON. Muchos frameworks no lo reconocen como JSON: el ejemplo lee el cuerpo crudo y lo interpreta.

## Errores frecuentes

El comando `verificar` y el sistema muestran una pista cuando reconocen el error:

| Código y mensaje de la API | Qué revisar |
| --- | --- |
| `E-021` *Firma no válida.* | En Checkins el payload del JWT es `{"ciat": <hora actual en segundos>}`, no `{"api_key": ...}` como en Payments. |
| `E-025` *Firma JWT inválida* | Firma con la SECRET KEY del **mismo ambiente** que tu API KEY, algoritmo HS256. |
| `E-023` *El token se encuentra expirado.* / `E-022` *emitido en el futuro* | La firma tolera 5 minutos: genera una nueva en cada request y sincroniza el reloj de tu servidor. |
| `E-002` *API key no válida* | Tu API KEY no existe en ese ambiente: probablemente usas llaves de Producción contra Sandbox, o al revés. |
| `E-CHK-306` / `E-CHK-305` | El check-in no está en un estado que permita la operación. |
| `E-CHK-308` *Monto excede el máximo permitido* | El checkout supera la garantía: aumenta la garantía antes o usa un cargo adicional después. |

## Antes de llevarlo a Producción

- Guarda las reservas en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Usa los datos reales del cliente en vez de los de ejemplo.
- Cambia `VIRTUALPOS_API_URL` a `https://api.virtualpos.cl` y usa tus llaves de Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/garantia-online-checkins/#pasar-a-producción).
