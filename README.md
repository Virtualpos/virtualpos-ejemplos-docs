# Ejemplos de integración Virtualpos

Código de ejemplo de las [guías de docs.virtualpos.cl](https://docs.virtualpos.cl/guias/). Cada guía tiene un ejemplo funcional en **Node.js**, **PHP** y **Java**, listo para ejecutar contra Sandbox.

| Guía | Ejemplo | Node.js | PHP | Java |
| --- | --- | --- | --- | --- |
| [Cobrar un pago único con Web Checkout](https://docs.virtualpos.cl/guias/pago-unico-web-checkout/) | [`01-pago-unico-web-checkout`](01-pago-unico-web-checkout/) | ✔ | ✔ | ✔ |

## Antes de comenzar

1. Crea tu cuenta y obtén tus llaves de Sandbox en [comercios.virtualpos-sandbox.com](https://comercios.virtualpos-sandbox.com), en **Perfil › Configuración de cuenta › Integración**.
2. Elige un ejemplo y un lenguaje, copia `.env.example` a `.env` y completa tus llaves.
3. Ejecuta el comando `verificar` del ejemplo: confirma que tus llaves y tu firma funcionan sin crear nada.

:warning: Los ejemplos están pensados para aprender y probar en Sandbox. Antes de llevarlos a Producción, reemplaza el almacenamiento de pedidos por tu base de datos y revisa la lista de [Pasar a Producción](https://docs.virtualpos.cl/general/ambientes-y-accesos/#pasar-a-producción).

## Seguridad

- Nunca subas tu archivo `.env` ni tu SECRET KEY a un repositorio. Este repositorio ya ignora `.env`.
- Si encuentras un problema de seguridad en estos ejemplos, escríbenos a [soporte@virtualpos.cl](mailto:soporte@virtualpos.cl) en vez de abrir un issue público.

## Soporte

Dudas sobre la API: [docs.virtualpos.cl](https://docs.virtualpos.cl) o [soporte@virtualpos.cl](mailto:soporte@virtualpos.cl).

Licencia [MIT](LICENSE).
