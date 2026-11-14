# Cobrar un pago único con Web Checkout

Ejemplo de la guía [Cobrar un pago único con Web Checkout](https://docs.virtualpos.cl/guias/pago-unico-web-checkout/).

Una tienda mínima con un botón **Pagar**. Muestra el flujo completo:

1. Crea un Payment y redirige al cliente al Web Checkout de Virtualpos.
2. Recibe el webhook en `/webhook` y responde 200 de inmediato.
3. Recibe al cliente de vuelta en `/retorno`.
4. Consulta el Payment en la API para conocer el resultado. Lo procesa una sola vez aunque webhook y retorno lleguen repetidos o en otro orden.
5. Anula el pago (opcional).

## Ejecutar

Copia `.env.example` a `.env` en la carpeta del lenguaje y completa tus llaves de Sandbox.

| | Requisitos | Instalar | Verificar llaves | Iniciar | URL |
| --- | --- | --- | --- | --- | --- |
| [Node.js](node/) | Node.js 20.6+ | `npm install` | `npm run verificar` | `npm start` | http://localhost:3000 |
| [PHP](php/) | PHP 8.1+, Composer | `composer install` | `composer verificar` | `composer start` | http://localhost:8000 |
| [Java](java/) | Java 21+ | `./mvnw package` | `java -jar target/*.jar verificar` | `./mvnw spring-boot:run` | http://localhost:8080 |

Paga con una [tarjeta de prueba](https://docs.virtualpos.cl/general/datos-de-prueba-sandbox/): VISA crédito `4051 8856 0044 6623`, CVV `123`, para un pago aprobado.

## Recibir el webhook en tu computador

Virtualpos necesita llegar a tu servidor desde internet. Usa un túnel como [ngrok](https://ngrok.com/) y pon su URL en `PUBLIC_URL`:

```bash
ngrok http 3000          # o 8000 / 8080 según el lenguaje
# PUBLIC_URL=https://<tu-subdominio>.ngrok-free.app
```

Sin túnel, el pago funciona igual, pero solo te enterarás del resultado en `/retorno`.

## Errores frecuentes

El comando `verificar` y la tienda muestran una pista cuando reconocen el error:

| Mensaje de la API | Qué revisar |
| --- | --- |
| *La verificación de la firma es incorrecta* / *Firma es incorrecta* / *Parámetros en la firma tienen errores* | Firma con la SECRET KEY del **mismo ambiente** que tu API KEY, algoritmo HS256 y payload `{"api_key": "<tu API KEY>"}`. |
| *No existe el comercio asociado a la api key* / *No existe cuenta virtualPOS asociada a la api key* | Tu API KEY no existe en ese ambiente: probablemente usas llaves de Producción contra Sandbox, o al revés. |
| Producto no activo o sin contrato | Pídele a tu ejecutivo que habilite el producto en ese ambiente. |

> **PHP:** `firebase/php-jwt` 7 exige llaves HS256 de al menos 32 caracteres. Las SECRET KEY de Virtualpos tienen 32. Si la tuya es más corta, escríbenos a soporte.

## Antes de llevarlo a Producción

- Guarda los pedidos en tu base de datos, no en memoria (Node.js, Java) ni en un archivo (PHP).
- Usa datos reales del cliente en vez de los de ejemplo.
- Cambia `VIRTUALPOS_API_URL` a `https://api.virtualpos.cl` y usa tus llaves de Producción.
- Revisa la guía completa: [Pasar a Producción](https://docs.virtualpos.cl/guias/pago-unico-web-checkout/#pasar-a-producción).
