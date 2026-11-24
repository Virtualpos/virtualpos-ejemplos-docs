# Suscribir clientes en el POS desde tu CRM

Ejemplo de la guía [Suscribir clientes en el POS desde tu CRM](https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/), con [Suscripciones presenciales](https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/introduccion/).

Un CRM mínimo: el vendedor registra al cliente, elige el POS donde está y presiona **Suscribir en el POS**. El cliente confirma en el POS y acerca su tarjeta. Muestra el flujo completo:

1. Crea la suscripción con los datos del cliente (`POST /v3/suscription`) y la envía al POS elegido (`POST /v3/suscription/push-to-terminal`).
2. No envía otra suscripción a un POS que tiene una pendiente.
3. Recibe el webhook en `/webhook` y consulta la suscripción para saber si quedó `ACTIVA` o `SUSCRIPCION_FALLIDA`.
4. Si el webhook no llega, consulta el estado a la API cada vez que se recarga la página.
5. Cancela el envío (`POST /v3/suscription/push-to-terminal/cancel`) si el cliente se fue o se eligió el POS equivocado.
6. Marca como vencida una suscripción que pasó 30 minutos sin tarjeta.

## Antes de comenzar

Las suscripciones presenciales **no están disponibles en Sandbox**. Pídele a tu ejecutivo:

- **Suscripciones** habilitado en tu comercio y el **ambiente de integración**: su URL base y tu API KEY y SECRET KEY de ese ambiente.
- El **serial de cada POS**, asignado a tu comercio.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus credenciales y tus POS en `POS_TERMINALES` (`serial:nombre,serial:nombre`).

| | Requisitos | Instalar | Verificar credenciales | Crear el plan | Iniciar | URL |
| --- | --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm run crear-plan` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer crear-plan` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `java -jar target/*.jar crear-plan` | `./mvnw spring-boot:run` | http://localhost:8080 |

`verificar` consulta una suscripción que no existe: confirma tus llaves y tu firma sin crear nada ni enviar nada al POS.

`crear-plan` crea un plan mensual con `show_in_terminal: "T"`. Guarda el `id` que muestra en `VIRTUALPOS_PLAN_ID`.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel el CRM funciona igual: conoce el resultado consultando la API al recargar la página.

## Errores frecuentes

| Error | Qué revisar |
| --- | --- |
| `E-PTT-003` | El plan no está habilitado para el POS. Créalo con `show_in_terminal: "T"`. |
| `E-PTT-006` | El POS no está asignado a tu comercio en ese ambiente. Revisa el serial. |
| `E-PTT-002` | La suscripción ya no está en `SUSCRIBIENDO`. Crea una nueva. |
| `E-046` | Tu comercio no tiene Suscripciones activo en ese ambiente. |
| *Firma es incorrecta* | Usa la SECRET KEY del mismo ambiente que tu API KEY. Ver [Autenticación y firma](https://docs.virtualpos.cl/general/autenticacion-y-firma/). |

Más errores en la [guía](https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/#errores-comunes).

## Antes de llevarlo a Producción

- Guarda los clientes y las suscripciones en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Cambia `VIRTUALPOS_API_URL` a `https://api.virtualpos.cl`, usa las llaves de tu portal de comercio de Producción, crea el plan en Producción y usa los seriales de tus POS reales.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/#pasar-a-producción).
