# Cobrar por link (email, WhatsApp o SMS)

Ejemplo de la guía [Cobrar por link](https://docs.virtualpos.cl/guias/cobro-por-link/).

Un panel mínimo para tu equipo: ingresas el monto y los datos del cliente, y Virtualpos le envía el link de pago por email, WhatsApp o SMS. Muestra el flujo completo:

1. Crea un Payment y envía el link con `POST /v3/payment/{uuid}/send`.
2. Reenvía el link de un cobro pendiente.
3. Recibe el webhook en `/webhook` y responde 200 de inmediato.
4. Recibe al cliente en `/gracias` cuando termina de pagar.
5. Consulta el Payment en la API para conocer el resultado. Lo procesa una sola vez aunque webhook y retorno lleguen repetidos o en otro orden.
6. Si el pago se rechaza, crea un cobro nuevo: un link rechazado ya no se puede volver a pagar.

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus llaves de Sandbox.

| | Requisitos | Instalar | Verificar llaves | Iniciar | URL |
| --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `./mvnw spring-boot:run` | http://localhost:8080 |

En Sandbox, envía el link **por email** a una dirección tuya o de prueba. El panel también muestra el link para que lo abras directamente. Paga con una [tarjeta de prueba](https://docs.virtualpos.cl/general/datos-de-prueba-sandbox/): VISA crédito `4051 8856 0044 6623`, CVV `123`, para un pago aprobado.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet, tanto para el webhook como para devolver al cliente a `/gracias`. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel, usa **Consultar estado** en el panel para conocer el resultado.

## Errores frecuentes

El comando `verificar` y el panel muestran una pista cuando reconocen el error:

| Mensaje de la API | Qué revisar |
| --- | --- |
| *Error en el parametro receiver, no cumple con formato para el channel …* | El destinatario no corresponde al canal: `EMAIL` necesita un email; `WHATSAPP` y `SMS`, un teléfono con `+56`, por ejemplo `+56912345678`. |
| *Channel no permitido.* | Usa `EMAIL`, `WHATSAPP` o `SMS`. Si es uno de ellos, pídele a tu ejecutivo que lo habilite. |
| *No existe la orden con uuid …* al enviar | El pago ya no está pendiente (pagado, rechazado o expirado), o el uuid es de otro ambiente. Consulta su estado. |
| *La verificación de la firma es incorrecta* / *Firma es incorrecta* | Firma con la SECRET KEY del **mismo ambiente** que tu API KEY, algoritmo HS256 y payload `{"api_key": "<tu API KEY>"}`. |
| *No existe el comercio asociado a la api key* | Tu API KEY no existe en ese ambiente: probablemente usas llaves de Producción contra Sandbox, o al revés. |

> **PHP:** `firebase/php-jwt` 7 exige llaves HS256 de al menos 32 caracteres. Las SECRET KEY de Virtualpos tienen 32. Si la tuya es más corta, escríbenos a soporte.

## Antes de llevarlo a Producción

- Protege el panel con el login de tu sistema: cualquiera que llegue a él puede crear cobros y enviar mensajes.
- Guarda los cobros en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Cambia `VIRTUALPOS_API_URL` a `https://api.virtualpos.cl` y usa tus llaves de Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/cobro-por-link/#pasar-a-producción).
