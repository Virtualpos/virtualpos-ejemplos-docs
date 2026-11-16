// Guía: Tomar y cobrar una garantía online con Checkins
// https://docs.virtualpos.cl/guias/garantia-online-checkins/
import express from 'express';
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});
const PUBLIC_URL = (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const PORT = Number(process.env.PORT ?? 3000);

// En tu sistema esto sería tu base de datos de reservas.
const reservas = new Map(); // reservaId -> { reservaId, uuid, status, guarantee, captured, uuidCli, cardReferenceId, last4, confirmed }
const byUuid = new Map(); // uuid del check-in -> reservaId

const app = express();
app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// 1. Página de tu sistema: nueva reserva y listado.
app.get('/', (_req, res) => {
  const rows = [...reservas.values()].reverse().map((r) => `
    <tr><td><code>${r.reservaId}</code></td><td>${r.status}</td><td>${clp(r.guarantee)}</td>
    <td>${r.last4 ? `•••• ${r.last4}` : ''}</td><td><a href="/reservas/${r.reservaId}">Ver</a></td></tr>`).join('');
  res.send(page('Arriendo de autos de ejemplo', `
    <form method="post" action="/reservas">
      <p><label>Garantía <input name="guarantee" type="number" value="50000" min="1"></label></p>
      <p><label><input type="radio" name="timing" value="immediate" checked> Tomarla ahora (inmediata)</label><br>
         <label><input type="radio" name="timing" value="deferred"> Solo registrar la tarjeta (diferida)</label></p>
      <button>Reservar</button>
    </form>
    ${rows ? `<h2>Reservas</h2><table cellpadding="6"><tr><th>Reserva</th><th>Estado</th><th>Garantía</th><th>Tarjeta</th><th></th></tr>${rows}</table>` : ''}`));
});

// 2. Crea el check-in y lleva al cliente al formulario de Virtualpos para ingresar su tarjeta.
app.post('/reservas', async (req, res) => {
  const reservaId = `RES-${Date.now()}`;
  const guarantee = Number(req.body.guarantee ?? 50000);
  try {
    const { checkin } = await vp.createCheckin({
      currency: 'CLP',
      description: `Arriendo ${reservaId}`,
      merchant_internal_code: reservaId,
      date_checkin_commerce: isoDate(1),
      date_checkout_commerce: isoDate(4),
      client: {
        first_name: 'Ana',
        last_name: 'Pérez',
        email: 'cliente@example.com',
        social_id_type: 'RUT',
        social_id: '11111111-1',
        type: 'PERSONA',
      },
      payment_plan: {
        guarantee: { timing: req.body.timing === 'deferred' ? 'deferred' : 'immediate', amount: guarantee },
        prepayment: { amount: 0 },
      },
      callback_url: `${PUBLIC_URL}/webhook`,
      return_url: `${PUBLIC_URL}/retorno`,
      metadata: { reserva: reservaId },
    });
    save({ reservaId, uuid: checkin.uuid, status: checkin.status, guarantee, uuidCli: checkin.client.uuid_cli });
    console.log(`Check-in ${checkin.uuid} creado para ${reservaId}`);
    res.redirect(303, checkin.hosted_url);
  } catch (err) {
    res.status(502).send(page('No se pudo crear la reserva', errorHtml(err)));
  }
});

// 3. Webhook: Virtualpos avisa que el check-in cambió. Responde 200 de inmediato y consulta el estado después.
app.post('/webhook', express.text({ type: () => true }), (req, res) => {
  console.log('Webhook recibido:', req.headers['content-type'], JSON.stringify(req.body));
  const uuid = webhookUuid(req.body);
  res.sendStatus(200);
  if (uuid) syncCheckin(uuid).catch((err) => console.error('Webhook:', err.message, err.hint ?? ''));
});

// 4. Retorno: el cliente vuelve desde el formulario con el uuid del check-in.
app.all('/retorno', async (req, res) => {
  const uuid = req.body?.uuid ?? req.query.uuid;
  console.log(`Retorno (${req.method}):`, JSON.stringify({ ...req.query, ...req.body }));
  try {
    const r = await syncCheckin(uuid);
    res.redirect(303, `/reservas/${r.reservaId}`);
  } catch (err) {
    res.status(502).send(page('No se pudo confirmar la reserva', errorHtml(err)));
  }
});

// Detalle de la reserva con las operaciones disponibles según su estado.
app.get('/reservas/:id', async (req, res) => {
  const r = reservas.get(req.params.id);
  if (!r) return res.status(404).send(page('Reserva no encontrada', '<p><a href="/">Volver</a></p>'));
  await syncCheckin(r.uuid).catch(() => {});
  const op = (path, label, field, value) => `
    <form method="post" action="/reservas/${r.reservaId}/${path}" style="margin:.5rem 0">
      ${field ? `<input name="${field}" type="number" value="${value}" min="1"> ` : ''}<button>${label}</button></form>`;
  const ops = {
    card_captured: op('autorizar', 'Tomar la garantía', 'amount', r.guarantee),
    authorized: op('incremental', 'Aumentar la garantía en', 'amount', 20000)
      + op('checkout', 'Cobrar el monto final', 'amount', r.guarantee)
      + op('cancelar', 'Liberar la garantía (sin cobro)'),
    captured: op('cargo-adicional', 'Cargo adicional (multa, daño)', 'amount', 8000),
  }[r.status] ?? '';
  const nueva = r.cardReferenceId ? op('nueva', 'Nueva reserva con la misma tarjeta, garantía', 'amount', 30000) : '';
  res.send(page(`Reserva ${r.reservaId}`, `
    <p>Estado del check-in: <strong>${r.status}</strong></p>
    <p>Garantía: ${clp(r.guarantee)}${r.captured ? ` · Cobrado: ${clp(r.captured)}` : ''}${r.last4 ? ` · Tarjeta •••• ${r.last4}` : ''}</p>
    ${ops}${nueva}<p><a href="/">Volver</a></p>`));
});

// 5a. Garantía diferida: tómala cuando la necesites (por ejemplo, el día del retiro).
app.post('/reservas/:id/autorizar', operation((r, amount) => vp.authorize(r.uuid, amount)));
// 5b. Aumenta la garantía tomada (por ejemplo, el cliente extiende el arriendo).
app.post('/reservas/:id/incremental', operation((r, amount) => vp.incremental(r.uuid, amount, 'Extensión del arriendo')));
// 6a. Checkout: cobra el monto final. El resto de la garantía se libera.
app.post('/reservas/:id/checkout', operation((r, amount) => vp.checkout(r.uuid, amount)));
// 6b. O libera la garantía completa sin cobrar.
app.post('/reservas/:id/cancelar', operation((r) => vp.cancel(r.uuid)));
// 7. Cargo adicional después del checkout (multas, daños).
app.post('/reservas/:id/cargo-adicional', operation((r, amount) => vp.additionalCharge(r.uuid, amount, 'Multa de tránsito')));

// 8. Cliente que vuelve: nueva garantía con su tarjeta guardada, sin formulario.
app.post('/reservas/:id/nueva', async (req, res) => {
  const anterior = reservas.get(req.params.id);
  if (!anterior?.cardReferenceId) return res.status(404).send(page('Reserva no encontrada', '<p><a href="/">Volver</a></p>'));
  const reservaId = `RES-${Date.now()}`;
  const guarantee = Number(req.body.amount);
  try {
    const { checkin } = await vp.createCheckinWithSavedCard(anterior.uuidCli, anterior.cardReferenceId, {
      currency: 'CLP',
      description: `Arriendo ${reservaId}`,
      merchant_internal_code: reservaId,
      date_checkin_commerce: isoDate(1),
      date_checkout_commerce: isoDate(4),
      payment_plan: { guarantee: { timing: 'immediate', amount: guarantee } },
      callback_url: `${PUBLIC_URL}/webhook`,
    });
    save({ reservaId, uuid: checkin.uuid, status: checkin.status, guarantee, uuidCli: anterior.uuidCli });
    console.log(`Check-in ${checkin.uuid} con tarjeta guardada para ${reservaId}: ${checkin.status}`);
    res.redirect(303, `/reservas/${reservaId}`);
  } catch (err) {
    res.status(502).send(page('No se pudo crear la reserva', errorHtml(err)));
  }
});

// Fuente de verdad: siempre consulta el check-in en la API. Webhook y retorno pueden llegar en cualquier orden o repetirse.
async function syncCheckin(uuid) {
  if (!uuid) throw new Error('No llegó el uuid del check-in');
  const { checkin } = await vp.getCheckin(uuid);
  const reservaId = byUuid.get(uuid) ?? checkin.merchant_internal_code;
  const r = { reservaId, uuid, ...reservas.get(reservaId) };
  r.status = checkin.status;
  r.guarantee = checkin.guarantee_amount;
  r.captured = (checkin.transactions ?? []).filter((t) => t.transaction_type === 'checkout').reduce((s, t) => s + t.amount, 0);
  if (checkin.card) {
    r.cardReferenceId = checkin.card.card_reference_id; // guárdalo para cobrarle al cliente sin formulario la próxima vez
    r.last4 = checkin.card.last4;
  }
  if (checkin.client?.uuid_cli) r.uuidCli = checkin.client.uuid_cli;
  if (r.status === 'authorized' && !r.confirmed) {
    r.confirmed = true; // una sola vez, aunque el webhook se repita
    // Aquí confirmas la reserva en tu sistema: correo al cliente, asignación del auto…
    console.log(`Reserva ${reservaId} confirmada: garantía de ${clp(r.guarantee)} tomada`);
  }
  save(r);
  return r;
}

// Ejecuta una operación sobre el check-in y vuelve al detalle de la reserva.
function operation(fn) {
  return async (req, res) => {
    const r = reservas.get(req.params.id);
    if (!r) return res.status(404).send(page('Reserva no encontrada', '<p><a href="/">Volver</a></p>'));
    try {
      await fn(r, Number(req.body.amount));
      console.log(`${req.path.split('/').pop()} OK en ${r.reservaId}`);
      res.redirect(303, `/reservas/${r.reservaId}`);
    } catch (err) {
      console.error(`${req.path.split('/').pop()} falló en ${r.reservaId}: ${err.message}`);
      res.status(502).send(page('La operación no se completó', errorHtml(err, `/reservas/${r.reservaId}`)));
    }
  };
}

function save(r) {
  reservas.set(r.reservaId, { ...reservas.get(r.reservaId), ...r });
  byUuid.set(r.uuid, r.reservaId);
}

// El webhook trae el uuid del check-in. Acepta JSON o formulario.
function webhookUuid(body) {
  if (body && typeof body === 'object') return body.uuid;
  const text = String(body ?? '').trim();
  try {
    return JSON.parse(text).uuid;
  } catch {
    return new URLSearchParams(text).get('uuid');
  }
}
const isoDate = (days) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
const clp = (n) => `$${Number(n ?? 0).toLocaleString('es-CL')}`;
function errorHtml(err, back = '/') {
  const detail = err instanceof VirtualposError ? err.message : String(err.message ?? err);
  return `<p><code>${escape(detail)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}<p><a href="${back}">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:40rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Arriendo de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
