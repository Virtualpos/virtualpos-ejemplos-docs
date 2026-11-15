// Guía: Cobrar una venta en el POS desde tu ERP
// https://docs.virtualpos.cl/guias/venta-pos-desde-erp/
import express from 'express';
import { VirtualposPos, PosError, FINAL, verifyWebhook } from './virtualpos-pos.js';

const pos = new VirtualposPos({
  apiUrl: process.env.VIRTUALPOS_POS_API_URL,
  apiKey: process.env.VIRTUALPOS_POS_API_KEY,
});
const POS_SERIAL = process.env.VIRTUALPOS_POS_SERIAL;
const WEBHOOK_SECRET = process.env.VIRTUALPOS_WEBHOOK_SECRET;
const PUBLIC_URL = (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const PORT = Number(process.env.PORT ?? 3000);
if (!POS_SERIAL) throw new Error('Configura VIRTUALPOS_POS_SERIAL en tu archivo .env');
if (!WEBHOOK_SECRET) console.warn('Sin VIRTUALPOS_WEBHOOK_SECRET no podrás verificar webhooks: la caja consultará el estado a la API.');

// En tu sistema esto sería tu base de datos de ventas.
const sales = new Map(); // saleId -> { saleId, amount, tipAmount, paymentRequestId, status, attemptNumber, detail, lastSync }
const seenEvents = new Set(); // eventId de webhooks ya procesados

const app = express();

// 1. Pantalla de la caja: monto, propina y botón para cobrar en el POS.
app.get('/', (_req, res) => {
  const rows = [...sales.values()].reverse().map((s) =>
    `<tr><td><a href="/ventas/${s.saleId}">${s.saleId}</a></td><td>$${s.amount.toLocaleString('es-CL')}</td><td>${s.status}</td></tr>`).join('');
  res.send(page('Caja de ejemplo', `
    <form method="post" action="/cobrar">
      <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
      <label>Propina <input name="tipAmount" type="number" min="0" value="0"></label>
      <button>Cobrar en el POS ${escape(POS_SERIAL)}</button>
    </form>
    ${rows ? `<h2>Ventas</h2><table>${rows}</table>` : ''}`));
});

// 2. Envía la venta al POS. El ID de la venta en tu sistema es el externalPaymentId.
app.post('/cobrar', express.urlencoded({ extended: false }), async (req, res) => {
  const sale = {
    saleId: `VENTA-${Date.now()}`,
    amount: Number(req.body.amount),
    tipAmount: Number(req.body.tipAmount || 0),
    status: 'CREATED',
  };
  sales.set(sale.saleId, sale); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
  try {
    await sendToPos(sale);
    res.redirect(303, `/ventas/${sale.saleId}`);
  } catch (err) {
    res.status(502).send(page('No se pudo enviar al POS', errorHtml(err, sale)));
  }
});

// 3. Pantalla de espera: el navegador consulta el estado cada 2 segundos hasta que la venta termina.
app.get('/ventas/:saleId', (req, res) => {
  const sale = sales.get(req.params.saleId);
  if (!sale) return res.status(404).send(page('Venta no encontrada', '<p><a href="/">Volver</a></p>'));
  res.send(page(`Venta ${sale.saleId}`, `
    <p>Monto: <strong>$${(sale.amount + sale.tipAmount).toLocaleString('es-CL')}</strong></p>
    <p id="estado">Esperando que el cliente pague en el POS…</p>
    <div id="acciones"></div>
    <script>
      const mensajes = ${JSON.stringify(MESSAGES)};
      async function revisar() {
        const s = await (await fetch(location.pathname + '/estado')).json();
        document.getElementById('estado').innerHTML = mensajes[s.status] ?? s.status;
        if (!s.final) return setTimeout(revisar, 2000);
        let html = '';
        if (s.status === 'APPROVED') {
          html = s.detail ? '<p>Autorización <code>' + s.detail.authCode + '</code> · ' + s.detail.cardBrand + ' ' + s.detail.cardTypeLabel + ' terminada en ' + s.detail.cardLast4 + '</p>' : '';
        } else {
          html = '<form method="post" action="' + location.pathname + '/reintentar"><button>Reintentar en el POS</button></form>';
        }
        document.getElementById('acciones').innerHTML = html + '<p><a href="/">Nueva venta</a></p>';
      }
      revisar();
    </script>`));
});

// Estado para la pantalla de espera. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos).
app.get('/ventas/:saleId/estado', async (req, res) => {
  const sale = sales.get(req.params.saleId);
  if (!sale) return res.sendStatus(404);
  if (!FINAL.has(sale.status) && sale.paymentRequestId && Date.now() - (sale.lastSync ?? 0) > 3000) {
    sale.lastSync = Date.now();
    try {
      applyStatus(sale, await pos.getOperation(sale.paymentRequestId));
    } catch (err) {
      console.error(`Consulta de ${sale.saleId}: ${err.message}`);
    }
  }
  if (sale.status === 'APPROVED' && !sale.detail) await loadDetail(sale);
  res.json({ status: sale.status, final: FINAL.has(sale.status), detail: sale.detail });
});

// 4. Reintentar una venta que terminó sin aprobarse. Usa el MISMO externalPaymentId:
// si la venta ya estuviera aprobada, Virtualpos responde con ella y no vuelve a cobrar.
app.post('/ventas/:saleId/reintentar', async (req, res) => {
  const sale = sales.get(req.params.saleId);
  if (!sale) return res.sendStatus(404);
  // Nunca reenvíes una venta en curso: el cliente podría estar pagando en el POS en este momento.
  // Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
  const unknown = !sale.paymentRequestId;
  if (!unknown && (!FINAL.has(sale.status) || sale.status === 'APPROVED')) return res.redirect(303, `/ventas/${sale.saleId}`);
  try {
    await sendToPos(sale);
    res.redirect(303, `/ventas/${sale.saleId}`);
  } catch (err) {
    res.status(502).send(page('No se pudo reintentar', errorHtml(err, sale)));
  }
});

// 5. Webhook: verifica la firma sobre el body crudo, responde de inmediato y procesa una sola vez.
app.post('/webhooks/virtualpos', express.raw({ type: '*/*' }), (req, res) => {
  const check = WEBHOOK_SECRET ? verifyWebhook(req.body, req.headers, WEBHOOK_SECRET) : { ok: false, reason: 'sin secreto' };
  if (!check.ok) {
    console.warn(`Webhook rechazado: ${check.reason}`);
    return res.sendStatus(401);
  }
  res.sendStatus(204);

  const event = JSON.parse(req.body);
  if (seenEvents.has(event.eventId)) return; // reintento de un webhook ya procesado
  seenEvents.add(event.eventId);

  const sale = sales.get(event.externalPaymentId);
  if (!sale) return console.warn(`Webhook de una venta desconocida: ${event.externalPaymentId}`);
  applyStatus(sale, event);
  if (event.status === 'APPROVED' && event.resultPayload) sale.detail = cardDetail(event.resultPayload);
});

async function sendToPos(sale) {
  const op = await pos.createSale({
    posSerial: POS_SERIAL,
    externalPaymentId: sale.saleId,
    amount: sale.amount,
    tipAmount: sale.tipAmount,
    callbackUrl: `${PUBLIC_URL}/webhooks/virtualpos`,
  });
  sale.paymentRequestId = op.paymentRequestId;
  sale.attemptNumber = op.attemptNumber;
  sale.status = op.status;
  sale.lastSync = Date.now();
  console.log(`${sale.saleId} enviada al POS (intento ${op.attemptNumber}, ${op.status})`);
  if (op.status === 'FAILED') console.warn('201 con FAILED: la venta no llegó al POS. ¿Está encendido, con internet y con la app Virtualpos abierta?');
}

// Aplica un estado recibido por webhook o por consulta. Idempotente:
// ignora intentos anteriores y no cambia una venta que ya terminó.
function applyStatus(sale, op) {
  if (op.attemptNumber && sale.attemptNumber && op.attemptNumber < sale.attemptNumber) return;
  if (FINAL.has(sale.status) && op.attemptNumber === sale.attemptNumber) return;
  sale.status = op.status;
  sale.attemptNumber = op.attemptNumber ?? sale.attemptNumber;
  if (sale.status === 'APPROVED') {
    // Aquí cierras la venta en tu ERP: boleta, descuento de stock, comprobante…
    console.log(`${sale.saleId} APROBADA`);
  } else if (FINAL.has(sale.status)) {
    console.log(`${sale.saleId} terminó en ${sale.status}`);
  }
}

// El detalle de la tarjeta viene en el webhook. Si no lo recibiste, pídelo a callback-event.
async function loadDetail(sale) {
  try {
    const event = await pos.getCallbackEvent(sale.paymentRequestId);
    if (event.resultPayload) sale.detail = cardDetail(event.resultPayload);
  } catch (err) {
    console.error(`Detalle de ${sale.saleId}: ${err.message}`);
  }
}

const cardDetail = (r) => ({
  authCode: r.authCode,
  cardBrand: r.cardBrand,
  cardTypeLabel: r.cardTypeLabel,
  cardLast4: r.cardLast4,
  posTransactionId: r.posTransactionId,
});

const MESSAGES = {
  CREATED: 'Enviando la venta al POS…',
  DISPATCHED: 'Esperando que el POS tome la venta…',
  CLAIMED: 'El cliente está pagando en el POS…',
  APPROVED: '<strong>Venta aprobada</strong>',
  REJECTED: '<strong>Pago rechazado.</strong> Pide otra tarjeta o reintenta.',
  FAILED: '<strong>La venta no llegó al POS.</strong> Revisa que esté encendido, con internet y con la app Virtualpos abierta.',
  EXPIRED: '<strong>El POS no tomó la venta a tiempo.</strong> Revisa que esté encendido y libre.',
  CANCELLED: '<strong>La venta se canceló en el POS.</strong>',
  REVERSED: '<strong>La venta fue reversada.</strong>',
};

function errorHtml(err, sale) {
  // Timeout o 500: no sabes si la venta llegó al POS. Reintenta con el MISMO externalPaymentId, nunca con uno nuevo.
  const retry = err instanceof PosError && err.httpStatus > 0 && err.httpStatus < 500 ? '' :
    `<form method="post" action="/ventas/${sale.saleId}/reintentar"><button>Reintentar ${sale.saleId}</button></form>`;
  return `<p><code>${escape(err.message)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}${retry}<p><a href="/">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Caja de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
