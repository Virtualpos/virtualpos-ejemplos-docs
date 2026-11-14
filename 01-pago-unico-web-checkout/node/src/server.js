// Guía: Cobrar un pago único con Web Checkout
// https://docs.virtualpos.cl/guias/pago-unico-web-checkout/
import express from 'express';
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});
const PUBLIC_URL = (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const PORT = Number(process.env.PORT ?? 3000);
const CUSTOMER_EMAIL = 'cliente@example.com'; // en tu sistema, el email de tu cliente

// En tu sistema esto sería tu base de datos de pedidos.
const orders = new Map(); // orderId -> { orderId, amount, uuid, status }
const FINAL = new Set(['pagado', 'rechazado', 'expirado', 'cancelado', 'anulado']);

const app = express();
app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// 1. Página de tu tienda con el botón de pago.
app.get('/', (_req, res) => {
  res.send(page('Tienda de ejemplo', `
    <p>Pedido de prueba por <strong>$9.990</strong>.</p>
    <form method="post" action="/pagar"><button>Pagar con Virtualpos</button></form>`));
});

// 2. Crea el Payment y redirige al cliente al Web Checkout.
app.post('/pagar', async (_req, res) => {
  const orderId = `ORD-${Date.now()}`;
  const amount = 9990;
  try {
    const { payment, url_redirect } = await vp.createPayment({
      amount,
      email: CUSTOMER_EMAIL,
      social_id: '11111111-1',
      first_name: 'Ana',
      last_name: 'Pérez',
      phone: '56912345678',
      description: `Pedido ${orderId}`,
      merchant_internal_code: orderId,
      merchant_internal_channel: 'tienda-ejemplo',
      return_url: `${PUBLIC_URL}/retorno`,
      callback_url: `${PUBLIC_URL}/webhook`,
    });
    orders.set(orderId, { orderId, amount, uuid: payment.order.uuid, status: payment.order.status });
    console.log(`Payment ${payment.order.uuid} creado para ${orderId}`);
    res.redirect(303, url_redirect);
  } catch (err) {
    res.status(502).send(page('No se pudo crear el pago', errorHtml(err)));
  }
});

// 3. Webhook: Virtualpos avisa que el pago cambió. Responde 200 de inmediato y consulta el resultado después.
app.post('/webhook', (req, res) => {
  const uuid = req.body?.uuid;
  res.sendStatus(200);
  if (uuid) syncPayment(uuid).catch((err) => console.error('Webhook:', err.message, err.hint ?? ''));
});

// 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
app.post('/retorno', async (req, res) => {
  try {
    const order = await syncPayment(req.body?.uuid);
    res.send(page(order.status === 'pagado' ? '¡Pago recibido!' : 'El pago no se completó', `
      <p>Pedido <code>${order.orderId}</code>: <strong>${order.status}</strong></p>
      ${order.status === 'pagado'
        ? `<form method="post" action="/anular/${order.orderId}"><button>Anular este pago</button></form>`
        : '<p><a href="/">Intentar nuevamente</a></p>'}`));
  } catch (err) {
    res.status(502).send(page('No se pudo confirmar el pago', errorHtml(err)));
  }
});

// 5. Anulación total del pago (opcional).
app.post('/anular/:orderId', async (req, res) => {
  const order = orders.get(req.params.orderId);
  if (!order || order.status !== 'pagado') return res.status(404).send(page('Pedido no encontrado', ''));
  try {
    const refund = await vp.refundPayment(order.uuid, order.amount, CUSTOMER_EMAIL);
    order.status = 'anulado';
    console.log(`Pedido ${order.orderId} anulado (${refund.status})`);
    res.send(page('Pago anulado', `<p>Estado de la anulación: <strong>${refund.status}</strong></p><p><a href="/">Volver</a></p>`));
  } catch (err) {
    console.error(`No se pudo anular ${order.orderId}: ${err.message}`);
    res.status(502).send(page('No se pudo anular', errorHtml(err)));
  }
});

// Fuente de verdad: siempre consulta el Payment en la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
async function syncPayment(uuid) {
  if (!uuid) throw new Error('No llegó el uuid del pago');
  const { payment } = await vp.getPayment(uuid);
  const orderId = payment.order.merchant_internal_code;
  const order = orders.get(orderId) ?? { orderId, amount: payment.order.amount, uuid };
  if (FINAL.has(order.status)) return order; // ya procesado: no lo proceses dos veces
  order.status = payment.order.status;
  orders.set(orderId, order);
  if (order.status === 'pagado') {
    // Aquí confirmas el pedido en tu sistema: despacho, boleta, correo al cliente…
    console.log(`Pedido ${orderId} pagado (autorización ${payment.order.auth_code})`);
  }
  return order;
}

function errorHtml(err) {
  const detail = err instanceof VirtualposError ? err.message : String(err.message ?? err);
  return `<p><code>${escape(detail)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}<p><a href="/">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Tienda de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
