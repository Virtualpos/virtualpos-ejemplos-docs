// Guía: Garantía con el POS (preautorización y cierre)
// https://docs.virtualpos.cl/guias/garantia-pos/
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
const CALLBACK_URL = `${PUBLIC_URL}/webhooks/virtualpos`;
if (!POS_SERIAL) throw new Error('Configura VIRTUALPOS_POS_SERIAL en tu archivo .env');
if (!WEBHOOK_SECRET) console.warn('Sin VIRTUALPOS_WEBHOOK_SECRET no podrás verificar webhooks: la recepción consultará el estado a la API.');

// En tu sistema esto sería tu base de datos de reservas.
// Cada reserva tiene hasta tres operaciones en el POS, cada una con su propio externalPaymentId:
//   checkin  -> RESERVA-123            (preautoriza la garantía)
//   checkout -> RESERVA-123-CHECKOUT   (cobra el monto final)
//   print    -> RESERVA-123-COMPROBANTE-1, -2… (imprime el comprobante)
const reservas = new Map(); // reservaId -> { reservaId, guarantee, checkin, checkout, prints: [] }
const operations = new Map(); // externalPaymentId -> operación (para ubicar los webhooks)
const seenEvents = new Set(); // eventId de webhooks ya procesados

const newOperation = (kind, externalPaymentId, amount) => {
  const op = { kind, externalPaymentId, amount, status: 'CREATED' };
  operations.set(externalPaymentId, op);
  return op;
};

const app = express();

// 1. Recepción: ingresa el monto de la garantía y preautoriza en el POS.
app.get('/', (_req, res) => {
  const rows = [...reservas.values()].reverse().map((r) =>
    `<tr><td><a href="/reservas/${r.reservaId}">${r.reservaId}</a></td><td>${clp(r.guarantee)}</td>` +
    `<td>${r.checkin.status}</td><td>${r.checkout ? `${clp(r.checkout.amount)} ${r.checkout.status}` : '—'}</td></tr>`).join('');
  res.send(page('Recepción de ejemplo', `
    <form method="post" action="/checkin">
      <label>Garantía <input name="amount" type="number" min="1" value="50000" required></label>
      <button>Preautorizar en el POS ${escape(POS_SERIAL)}</button>
    </form>
    ${rows ? `<h2>Reservas</h2><table><tr><th>Reserva</th><th>Garantía</th><th>Check-in</th><th>Check-out</th></tr>${rows}</table>` : ''}`));
});

// 2. Check-in: guarda la reserva y envía la preautorización (CHECKIN) al POS. El ID de la reserva es el externalPaymentId.
app.post('/checkin', express.urlencoded({ extended: false }), async (req, res) => {
  const reservaId = `RESERVA-${Date.now()}`;
  const reserva = { reservaId, guarantee: Number(req.body.amount), checkout: null, prints: [] };
  reserva.checkin = newOperation('CHECKIN', reservaId, reserva.guarantee);
  reservas.set(reservaId, reserva); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
  await sendOrShowError(res, reserva, reserva.checkin);
});

// 3. Check-out: cierra la preautorización con el monto final. Requiere el resultPayload del check-in aprobado.
app.post('/reservas/:reservaId/checkout', express.urlencoded({ extended: false }), async (req, res) => {
  const reserva = reservas.get(req.params.reservaId);
  if (!reserva) return res.sendStatus(404);
  if (reserva.checkin.status !== 'APPROVED' || !reserva.checkin.detail?.posTransactionId) {
    return res.status(409).send(page('Aún no puedes cerrar', `<p>El check-in debe estar aprobado y con su posTransactionId.</p>${back(reserva)}`));
  }
  if (reserva.checkout) return res.redirect(303, `/reservas/${reserva.reservaId}`); // ya se envió: usa Reintentar
  reserva.checkout = newOperation('CHECKOUT', `${reserva.reservaId}-CHECKOUT`, Number(req.body.amount));
  await sendOrShowError(res, reserva, reserva.checkout);
});

// 4. Reintentar el check-in o el check-out con el MISMO externalPaymentId.
app.post('/reservas/:reservaId/reintentar/:kind', async (req, res) => {
  const reserva = reservas.get(req.params.reservaId);
  const op = reserva?.[req.params.kind]; // 'checkin' o 'checkout'
  if (!op || !['checkin', 'checkout'].includes(req.params.kind)) return res.sendStatus(404);
  // Nunca reenvíes una operación en curso: el huésped podría estar presentando su tarjeta.
  // Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
  const unknown = !op.paymentRequestId;
  if (!unknown && (!FINAL.has(op.status) || op.status === 'APPROVED')) return res.redirect(303, `/reservas/${reserva.reservaId}`);
  await sendOrShowError(res, reserva, op);
});

// 5. Imprime el comprobante de la estadía. Cada impresión usa un externalPaymentId nuevo.
app.post('/reservas/:reservaId/imprimir', async (req, res) => {
  const reserva = reservas.get(req.params.reservaId);
  if (!reserva) return res.sendStatus(404);
  const op = newOperation('PRINT', `${reserva.reservaId}-COMPROBANTE-${reserva.prints.length + 1}`);
  reserva.prints.push(op);
  await sendOrShowError(res, reserva, op);
});

// Pantalla de la reserva: el navegador consulta el estado cada 2 segundos.
app.get('/reservas/:reservaId', (req, res) => {
  const reserva = reservas.get(req.params.reservaId);
  if (!reserva) return res.status(404).send(page('Reserva no encontrada', '<p><a href="/">Volver</a></p>'));
  res.send(page(`Reserva ${reserva.reservaId}`, `
    <p>Garantía: <strong>${clp(reserva.guarantee)}</strong></p>
    <div id="estado">Consultando…</div>
    <script>
      async function revisar() {
        const r = await (await fetch(location.pathname + '/estado')).json();
        document.getElementById('estado').innerHTML = r.html;
        if (r.waiting) setTimeout(revisar, 2000);
      }
      revisar();
    </script>`));
});

// Estado de la reserva. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos por operación).
app.get('/reservas/:reservaId/estado', async (req, res) => {
  const reserva = reservas.get(req.params.reservaId);
  if (!reserva) return res.sendStatus(404);
  const ops = [reserva.checkin, reserva.checkout, ...reserva.prints].filter(Boolean);
  for (const op of ops) {
    if (!FINAL.has(op.status) && op.paymentRequestId && Date.now() - (op.lastSync ?? 0) > 3000) {
      op.lastSync = Date.now();
      try {
        applyStatus(op, await pos.getOperation(op.paymentRequestId));
      } catch (err) {
        console.error(`Consulta de ${op.externalPaymentId}: ${err.message}`);
      }
    }
    // El checkout necesita el resultPayload del check-in. Si no llegó el webhook, pídelo a callback-event.
    if (op.status === 'APPROVED' && op.kind !== 'PRINT' && !op.detail) await loadDetail(op);
  }
  res.json({ html: reservaHtml(reserva), waiting: ops.some((op) => !FINAL.has(op.status)) });
});

// 6. Webhook: verifica la firma sobre el body crudo, responde de inmediato y procesa una sola vez.
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

  const op = operations.get(event.externalPaymentId);
  if (!op) return console.warn(`Webhook de una operación desconocida: ${event.externalPaymentId}`);
  applyStatus(op, event);
  if (event.status === 'APPROVED' && event.resultPayload) op.detail = event.resultPayload;
});

async function sendOrShowError(res, reserva, op) {
  try {
    await send(reserva, op);
    res.redirect(303, `/reservas/${reserva.reservaId}`);
  } catch (err) {
    // Timeout o 500: no sabes si la operación llegó al POS. Reintenta con el MISMO externalPaymentId.
    const unknown = !(err instanceof PosError) || err.httpStatus === 0 || err.httpStatus >= 500;
    const retry = unknown && op.kind !== 'PRINT'
      ? `<form method="post" action="/reservas/${reserva.reservaId}/reintentar/${op.kind.toLowerCase()}"><button>Reintentar ${op.externalPaymentId}</button></form>`
      : '';
    res.status(502).send(page('No se pudo enviar al POS',
      `<p><code>${escape(err.message)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}${retry}${back(reserva)}`));
  }
}

async function send(reserva, op) {
  let result;
  if (op.kind === 'CHECKIN') {
    result = await pos.createCheckin({ posSerial: POS_SERIAL, externalPaymentId: op.externalPaymentId, amount: op.amount, callbackUrl: CALLBACK_URL });
  } else if (op.kind === 'CHECKOUT') {
    result = await pos.createCheckout({
      posSerial: POS_SERIAL,
      externalPaymentId: op.externalPaymentId,
      amount: op.amount,
      preauth: reserva.checkin.detail, // resultPayload del check-in aprobado
      callbackUrl: CALLBACK_URL,
    });
  } else {
    result = await pos.print({ posSerial: POS_SERIAL, externalPaymentId: op.externalPaymentId, lines: receiptLines(reserva), callbackUrl: CALLBACK_URL });
  }
  op.paymentRequestId = result.paymentRequestId;
  op.attemptNumber = result.attemptNumber;
  op.status = result.status;
  op.lastSync = Date.now();
  console.log(`${op.externalPaymentId} (${op.kind}) enviada al POS: ${result.status}${result.attemptNumber ? `, intento ${result.attemptNumber}` : ''}`);
  if (result.status === 'FAILED') console.warn('201 con FAILED: no llegó al POS. ¿Está encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso?');
}

// Aplica un estado recibido por webhook o por consulta. Idempotente:
// ignora intentos anteriores y no cambia una operación que ya terminó.
function applyStatus(op, update) {
  if (update.attemptNumber && op.attemptNumber && update.attemptNumber < op.attemptNumber) return;
  if (FINAL.has(op.status) && update.attemptNumber === op.attemptNumber) return;
  op.status = update.status;
  op.attemptNumber = update.attemptNumber ?? op.attemptNumber;
  if (op.status === 'APPROVED' && op.kind === 'CHECKIN') {
    console.log(`${op.externalPaymentId}: garantía preautorizada`); // aquí entregas la llave de la habitación
  } else if (op.status === 'APPROVED' && op.kind === 'CHECKOUT') {
    console.log(`${op.externalPaymentId}: estadía cobrada`); // aquí emites la boleta y cierras la reserva
  } else if (FINAL.has(op.status)) {
    console.log(`${op.externalPaymentId} terminó en ${op.status}`);
  }
}

async function loadDetail(op) {
  try {
    const event = await pos.getCallbackEvent(op.paymentRequestId);
    if (event.resultPayload) op.detail = event.resultPayload;
  } catch (err) {
    console.error(`Detalle de ${op.externalPaymentId}: ${err.message}`);
  }
}

// Comprobante de la estadía, línea por línea. https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/imprimir/
function receiptLines(reserva) {
  const card = reserva.checkin.detail;
  return [
    { type: 'text', content: 'Comprobante de estadía', font_size: 'big', align: 'center' },
    { type: 'blank' },
    { type: 'left_right', left: 'Reserva', right: reserva.reservaId, font_size: 'small' },
    { type: 'left_right', left: 'Garantía', right: clp(reserva.guarantee), font_size: 'small' },
    ...(reserva.checkout ? [{ type: 'left_right', left: 'Total cobrado', right: clp(reserva.checkout.amount), font_size: 'normal' }] : []),
    ...(card?.cardLast4 ? [{ type: 'left_right', left: 'Tarjeta', right: `${card.cardBrand ?? ''} ****${card.cardLast4}`, font_size: 'small' }] : []),
    { type: 'blank' },
    { type: 'text', content: 'Gracias por su visita', align: 'center' },
  ];
}

const MESSAGES = {
  CREATED: 'Enviando al POS…',
  DISPATCHED: 'Esperando que el POS tome la operación…',
  CLAIMED: 'El huésped está presentando su tarjeta…',
  APPROVED: '<strong>Aprobada</strong>',
  REJECTED: '<strong>Rechazada.</strong> Pide otra tarjeta y reintenta.',
  FAILED: '<strong>No llegó al POS.</strong> Revisa que esté encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso.',
  EXPIRED: '<strong>El POS no tomó la operación a tiempo.</strong> Revisa que esté encendido y libre.',
  CANCELLED: '<strong>Se canceló en el POS.</strong>',
  REVERSED: '<strong>Fue reversada.</strong>',
};

function reservaHtml(r) {
  const id = r.reservaId;
  const retryable = (op) => FINAL.has(op.status) && op.status !== 'APPROVED';
  const retry = (kind) => `<form method="post" action="/reservas/${id}/reintentar/${kind}"><button>Reintentar</button></form>`;
  let html = `<h2>Check-in (${clp(r.guarantee)})</h2><p>${MESSAGES[r.checkin.status] ?? r.checkin.status}</p>`;
  const card = r.checkin.detail;
  if (card) html += `<p>Autorización <code>${escape(card.authCode)}</code> · ${escape(card.cardBrand)} terminada en ${escape(card.cardLast4)}</p>`;
  if (retryable(r.checkin)) html += retry('checkin');

  if (r.checkin.status === 'APPROVED' && card && !r.checkout) {
    html += `<h2>Check-out</h2><form method="post" action="/reservas/${id}/checkout">
      <label>Monto final <input name="amount" type="number" min="1" value="${r.guarantee}" required></label>
      <button>Cobrar en el POS</button></form>`;
  }
  if (r.checkout) {
    html += `<h2>Check-out (${clp(r.checkout.amount)})</h2><p>${MESSAGES[r.checkout.status] ?? r.checkout.status}</p>`;
    if (retryable(r.checkout)) html += retry('checkout');
  }
  if (r.checkout?.status === 'APPROVED') {
    html += `<form method="post" action="/reservas/${id}/imprimir"><button>Imprimir comprobante</button></form>`;
  }
  for (const p of r.prints) html += `<p>Impresión ${p.externalPaymentId}: ${p.status === 'APPROVED' ? 'impresa' : (MESSAGES[p.status] ?? p.status)}</p>`;
  return html + '<p><a href="/">Volver a la recepción</a></p>';
}

const clp = (n) => `$${Number(n).toLocaleString('es-CL')}`;
const back = (r) => `<p><a href="/reservas/${r.reservaId}">Volver a la reserva</a></p>`;
const escape = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:36rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Recepción de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
