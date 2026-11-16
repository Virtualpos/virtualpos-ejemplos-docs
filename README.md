# Ejemplos de integración Virtualpos

Código de ejemplo de las [guías de docs.virtualpos.cl](https://docs.virtualpos.cl/guias/). Cada guía tiene un ejemplo funcional en **Node.js**, **PHP** y **Java**, listo para ejecutar contra Sandbox o, en POS Cloud to Cloud, contra el ambiente de integración.

| Guía | Ejemplo | Node.js | PHP | Java |
| --- | --- | --- | --- | --- |
| [Cobrar un pago único con Web Checkout](https://docs.virtualpos.cl/guias/pago-unico-web-checkout/) | [`01-pago-unico-web-checkout`](01-pago-unico-web-checkout/) | ✔ | ✔ | ✔ |
| [Cobrar una venta en el POS desde tu ERP](https://docs.virtualpos.cl/guias/venta-pos-desde-erp/) | [`02-venta-pos-desde-erp`](02-venta-pos-desde-erp/) | ✔ | ✔ | ✔ |
| [Cobrar por link (email, WhatsApp o SMS)](https://docs.virtualpos.cl/guias/cobro-por-link/) | [`03-cobro-por-link`](03-cobro-por-link/) | ✔ | ✔ | ✔ |
| [Cobrar una suscripción mensual](https://docs.virtualpos.cl/guias/suscripcion-mensual/) | [`04-suscripcion-mensual`](04-suscripcion-mensual/) | ✔ | ✔ | ✔ |
| [Garantía con el POS: preautorización y cierre](https://docs.virtualpos.cl/guias/garantia-pos/) | [`05-garantia-pos`](05-garantia-pos/) | ✔ | ✔ | ✔ |
| [Tomar y cobrar una garantía online con Checkins](https://docs.virtualpos.cl/guias/garantia-online-checkins/) | [`06-garantia-online-checkins`](06-garantia-online-checkins/) | ✔ | ✔ | ✔ |

## Antes de comenzar

1. Crea tu cuenta y obtén tus llaves de Sandbox en [comercios.virtualpos-sandbox.com](https://comercios.virtualpos-sandbox.com), en **Perfil › Configuración de cuenta › Integración**. Para POS Cloud to Cloud, tu ejecutivo te entrega las credenciales del ambiente de integración.
2. Elige un ejemplo y un lenguaje, copia `.env.example` a `.env` y completa tus llaves.
3. Ejecuta el comando `verificar` del ejemplo: confirma que tus credenciales funcionan sin crear nada.

:warning: Los ejemplos están pensados para aprender y probar. Antes de llevarlos a Producción, reemplaza el almacenamiento de pedidos por tu base de datos y revisa la sección **Pasar a Producción** de cada guía.

## Seguridad

- Nunca subas tu archivo `.env`, tu SECRET KEY, tu API key de POS ni tu secreto de webhook a un repositorio. Este repositorio ya ignora `.env`.
- Si encuentras un problema de seguridad en estos ejemplos, escríbenos a [soporte@virtualpos.cl](mailto:soporte@virtualpos.cl) en vez de abrir un issue público.

## Soporte

Dudas sobre la API: [docs.virtualpos.cl](https://docs.virtualpos.cl) o [soporte@virtualpos.cl](mailto:soporte@virtualpos.cl).

Licencia [MIT](LICENSE).
