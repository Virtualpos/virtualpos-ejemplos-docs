# Cobrar una suscripción mensual

Ejemplo de la guía [Cobrar una suscripción mensual](https://docs.virtualpos.cl/guias/suscripcion-mensual/).

Un sitio mínimo con un botón **Suscribirme**. Muestra el flujo completo:

1. Crea el plan mensual una sola vez (`crear-plan`).
2. Inicia **Pagar y suscribir**: el cliente paga el primer mes e inscribe su tarjeta en el mismo formulario.
3. Recibe el webhook en `/webhook` y al cliente de vuelta en `/retorno`, y consulta la suscripción en la API. Activa el servicio una sola vez aunque webhook y retorno lleguen repetidos o en otro orden.
4. Programa el cargo del próximo mes, sin repetirlo si ya está programado, o cobra el mes de inmediato.
5. Reintenta un cargo rechazado, genera el link de cambio de tarjeta y cancela la suscripción.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus llaves de Sandbox. Luego crea el plan y copia su ID en `VIRTUALPOS_PLAN_ID`.

| | Requisitos | Instalar | Verificar llaves | Crear plan | Iniciar | URL |
| --- | --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm run crear-plan` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer crear-plan` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `java -jar target/*.jar crear-plan` | `./mvnw spring-boot:run` | http://localhost:8080 |

Suscríbete con una [tarjeta de prueba](https://docs.virtualpos.cl/general/datos-de-prueba-sandbox/): VISA crédito `4051 8856 0044 6623`, CVV `123`, para una suscripción activa, o Mastercard crédito `5186 0595 5959 0568` para una inscripción fallida.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel, la suscripción funciona igual, pero solo te enterarás del resultado en `/retorno`.

## Errores frecuentes

El comando `verificar` y el sitio muestran una pista cuando reconocen el error:

| Mensaje de la API | Qué revisar |
| --- | --- |
| *Firma no válida* | Pagar y suscribir se firma con `{"ciat": <hora actual>}`, no con `{"api_key"}`. |
| *Parámetros en la firma tienen errores* | Planes, suscripciones y cargos se firman con `{"api_key": "<tu API KEY>"}`, no con `{"ciat"}`. |
| *El plan id … no es de tipo MONTO_VARIABLE* | Pagar y suscribir solo acepta planes `MONTO_VARIABLE`. Crea uno con `crear-plan`. |
| *Error en el parámetro charge_date: la fecha debe ser igual o mayor al día de hoy* | Programa los cargos con fecha `AAAA-MM-DD` de hoy en adelante. |
| *El cargo no cumple con las condiciones para realizar el reintento de cobro* | Solo se reintenta un cargo `rechazado`. |

> **Java:** el webhook llega con `Content-Type: application/json, application/x-www-form-urlencoded`. Spring lo rechaza con 415 si el método usa `@RequestBody` o `consumes`, así que el ejemplo lee el body directamente de la request.

## Antes de llevarlo a Producción

- Guarda clientes y suscripciones en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Usa datos reales del cliente en vez de los de ejemplo.
- Programa los cargos con una tarea diaria o mensual en tu servidor, no con un botón.
- Cambia `VIRTUALPOS_API_URL` a `https://api.virtualpos.cl`, usa tus llaves de Producción y crea el plan en Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/suscripcion-mensual/#pasar-a-producción).
