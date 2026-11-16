# Garantía con el POS: preautorización y cierre

Ejemplo de la guía [Garantía con el POS](https://docs.virtualpos.cl/guias/garantia-pos/), con [POS Cloud to Cloud](https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/introduccion/).

Una recepción mínima de hotel o de arriendo: al llegar el huésped, el recepcionista **preautoriza una garantía** en el POS; al irse, **cierra la preautorización** con el monto final e imprime el comprobante. Muestra el flujo completo:

1. Guarda la reserva y envía el check-in (`CHECKIN`) al POS con el ID de la reserva como `externalPaymentId`.
2. Recibe el webhook en `/webhooks/virtualpos`, verifica su firma HMAC y guarda el `resultPayload` del check-in aprobado.
3. Si el webhook no llega, consulta el estado en la API y el `resultPayload` en `callback-event`.
4. Cierra la preautorización (`CHECKOUT`) con un `externalPaymentId` propio y los datos del check-in.
5. Imprime un comprobante de la estadía en el POS (`PRINT`).
6. Reintenta un check-in o check-out rechazado con el mismo `externalPaymentId`, y nunca reenvía una operación en curso o aprobada.

## Antes de comenzar

POS Cloud to Cloud **no está disponible en Sandbox**. Pídele a tu ejecutivo:

- Tu **API key** y tu **secreto de webhook** del ambiente de integración. No son las llaves del portal de comercio.
- El **serial de tu POS virtual** y acceso al [simulador de POS](https://docs.virtualpos.cl/pos-cloud/ambientes-y-credenciales/#simulador-de-pos), donde apruebas o rechazas las operaciones.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus credenciales.

| | Requisitos | Instalar | Verificar credenciales | Iniciar | URL |
| --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `./mvnw spring-boot:run` | http://localhost:8080 |

`verificar` lista tu última operación: confirma tu API key sin enviar nada al POS.

Luego preautoriza una garantía en la recepción y apruébala en el simulador. Cuando el check-in esté aprobado, ingresa el monto final y cobra el check-out.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel la recepción funciona igual: consulta el estado a la API cada 3 segundos mientras espera.

## Errores frecuentes

| Error | Qué revisar |
| --- | --- |
| `401` | Tu API key no es válida en ese ambiente. Revisa que la URL y la API key sean del mismo ambiente (integración o producción). |
| `400` *checkout.preauthPosTransactionId is required for CHECKOUT* | El check-out no lleva el `posTransactionId` del check-in. Espera a que el check-in esté aprobado y usa su `resultPayload`. |
| `400` *tipAmount is only allowed for SALE operations* | El check-in y el check-out no aceptan propina. |
| `201` con `FAILED` | La operación no llegó al POS: revisa que esté encendido, con internet, con la app Virtualpos abierta y **sin otra operación en curso**. |
| Webhook rechazado: *firma no coincide* | Usa el secreto de webhook del mismo ambiente y calcula el HMAC sobre el body crudo, sin parsearlo. |

## Antes de llevarlo a Producción

- Guarda las reservas y el `resultPayload` del check-in en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Cambia `VIRTUALPOS_POS_API_URL` a `https://ms-payment-c2c.virtualpos.cl/ms-payment-c2c` y usa tus credenciales y seriales de Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/garantia-pos/#pasar-a-producción).
