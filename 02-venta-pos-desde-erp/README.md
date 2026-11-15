# Cobrar una venta en el POS desde tu ERP

Ejemplo de la guía [Cobrar una venta en el POS desde tu ERP](https://docs.virtualpos.cl/guias/venta-pos-desde-erp/), con [POS Cloud to Cloud](https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/introduccion/).

Una caja mínima: el cajero ingresa el monto, presiona **Cobrar en el POS** y el cliente paga con su tarjeta en el equipo. Muestra el flujo completo:

1. Guarda la venta en tu sistema y la envía al POS con tu ID de venta como `externalPaymentId`.
2. Muestra una pantalla de espera que se actualiza sola.
3. Recibe el webhook en `/webhooks/virtualpos`, verifica su firma HMAC y lo procesa una sola vez.
4. Si el webhook no llega, consulta el estado en la API y el detalle de la tarjeta en `callback-event`.
5. Reintenta una venta rechazada o expirada con el mismo `externalPaymentId`, y nunca reenvía una venta en curso o aprobada.

## Antes de comenzar

POS Cloud to Cloud **no está disponible en Sandbox**. Pídele a tu ejecutivo:

- Tu **API key** y tu **secreto de webhook** del ambiente de integración. No son las llaves del portal de comercio.
- El **serial de tu POS virtual** y acceso al [simulador de POS](https://docs.virtualpos.cl/pos-cloud/ambientes-y-credenciales/#simulador-de-pos), donde apruebas o rechazas las ventas.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus credenciales.

| | Requisitos | Instalar | Verificar credenciales | Iniciar | URL |
| --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `./mvnw spring-boot:run` | http://localhost:8080 |

`verificar` lista tu última operación: confirma tu API key sin enviar nada al POS.

Luego cobra una venta en la caja, ábrela en el simulador y presiona **Responder** para aprobarla o rechazarla.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel la caja funciona igual: consulta el estado a la API cada 3 segundos mientras espera.

## Errores frecuentes

| Error | Qué revisar |
| --- | --- |
| `401` | Tu API key no es válida en ese ambiente. Revisa que la URL y la API key sean del mismo ambiente (integración o producción). No uses la API KEY del portal de comercio. |
| `403` *POS is not enabled for cloud to cloud operations* | El serial no corresponde a un POS habilitado para tu comercio. |
| `201` con `FAILED` | La venta no llegó al POS: revisa que esté encendido, con internet y con la app Virtualpos abierta. |
| `EXPIRED` | El POS no tomó la venta a tiempo: estaba apagado, sin conexión u ocupado. |
| Webhook rechazado: *firma no coincide* | Usa el secreto de webhook del mismo ambiente y calcula el HMAC sobre el body crudo, sin parsearlo. |
| Webhook rechazado: *timestamp fuera de los 5 minutos* | Sincroniza el reloj de tu servidor (NTP). |

## Antes de llevarlo a Producción

- Guarda las ventas en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Cambia `VIRTUALPOS_POS_API_URL` a `https://ms-payment-c2c.virtualpos.cl/ms-payment-c2c` y usa tus credenciales y seriales de Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/venta-pos-desde-erp/#pasar-a-producción).
