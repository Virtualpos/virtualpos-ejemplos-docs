// Guía: Suscribir clientes en el POS desde tu CRM
// https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/
import express from 'express';
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});
const PLAN_ID = process.env.VIRTUALPOS_PLAN_ID;
if (!PLAN_ID) throw new Error('Configura VIRTUALPOS_PLAN_ID en tu archivo .env (créalo con npm run crear-plan)');
const PUBLIC_URL = (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const PORT = Number(process.env.PORT ?? 3000);
const VIGENCIA_MS = 30 * 60 * 1000; // el cliente tiene 30 minutos para presentar su tarjeta

// POS de tu comercio: "serial:nombre,serial:nombre"
const POS = new Map(
  (process.env.POS_TERMINALES ?? '').split(',').filter(Boolean).map((p) => {
    const [serial, ...name] = p.split(':');
    return [serial.trim(), name.join(':').trim() || serial.trim()];
  }),
);
if (POS.size === 0) throw new Error('Configura POS_TERMINALES en tu archivo .env');

// En tu CRM esto sería tu base de datos: una fila por suscripción enviada al POS.
const subscriptions = new Map(); // subscriptionId -> { subscriptionId, customer, posSerial, status, sentAt, card }

const app = express();
app.use(express.urlencoded({ extended: false }));

// 1. Ficha del cliente: el vendedor completa los datos y elige el POS donde está el cliente.
app.get('/', async (_req, res) => {
  // Respaldo del webhook: mientras haya envíos pendientes, consulta la API cada vez que se recarga la página.
  await Promise.allSettled([...subscriptions.values()].filter(needsCheck).map((s) => syncSubscription(s.subscriptionId)));

  const options = [...POS].map(([serial, name]) => `<option value="${escape(serial)}">${escape(name)} (${serial})</option>`).join('');
  const rows = [...subscriptions.values()].reverse().map((s) => `<tr>
      <td>${escape(s.customer.first_name)} ${escape(s.customer.last_name)}</td>
      <td>${escape(POS.get(s.posSerial) ?? s.posSerial)}</td>
      <td>${label(s)}</td>
      <td>${isPending(s) ? `<form method="post" action="/suscripciones/${encodeURIComponent(s.subscriptionId)}/cancelar"><button>Cancelar envío</button></form>` : ''}</td>
    </tr>`).join('');
  res.send(page('CRM de ejemplo', `
    <form method="post" action="/suscripciones" style="display:grid;gap:.5rem;max-width:24rem">
      <input name="first_name" placeholder="Nombre" required>
      <input name="last_name" placeholder="Apellido" required>
      <input name="social_id" placeholder="RUT (11111111-1)" required>
      <input name="email" type="email" placeholder="Email" required>
      <input name="phone_number" placeholder="Teléfono (56912345678)" required>
      <select name="pos">${options}</select>
      <button>Suscribir en el POS</button>
    </form>
    ${rows ? `<h2>Suscripciones</h2><table cellpadding="6"><tr><th>Cliente</th><th>POS</th><th>Estado</th><th></th></tr>${rows}</table>
    <script>setTimeout(() => location.reload(), 5000)</script>` : ''}`));
});

// 2. Crea la suscripción y la envía al POS elegido.
app.post('/suscripciones', async (req, res) => {
  const posSerial = req.body.pos;
  if (!POS.has(posSerial)) return res.status(400).send(page('POS desconocido', '<p><a href="/">Volver</a></p>'));

  // Un POS atiende una suscripción a la vez: no envíes otra mientras haya una pendiente en ese equipo.
  const busy = [...subscriptions.values()].find((s) => s.posSerial === posSerial && isPending(s));
  if (busy) {
    return res.status(409).send(page('El POS está ocupado',
      `<p>${escape(POS.get(posSerial))} tiene una suscripción pendiente de ${escape(busy.customer.first_name)}. Espera a que termine o cancela el envío.</p><p><a href="/">Volver</a></p>`));
  }

  const customer = {
    first_name: req.body.first_name,
    last_name: req.body.last_name,
    social_id: req.body.social_id,
    email: req.body.email,
    phone_number: req.body.phone_number,
  };
  let subscriptionId;
  try {
    const { suscription } = await vp.createSubscription({
      ...customer,
      plan_id: PLAN_ID,
      channel: 'CRM',
      return_url: PUBLIC_URL,
      callback_url: `${PUBLIC_URL}/webhook`,
    });
    subscriptionId = suscription.id;
    subscriptions.set(subscriptionId, { subscriptionId, customer, posSerial, status: suscription.status, sentAt: Date.now() });
    console.log(`Suscripción ${subscriptionId} creada (${suscription.status})`);

    await vp.sendToPos(posSerial, subscriptionId);
    console.log(`Suscripción ${subscriptionId} enviada al POS ${posSerial}`);
    res.redirect(303, '/');
  } catch (err) {
    // Si la suscripción se creó pero no llegó al POS, no la reutilices: la próxima vez crea una nueva.
    if (subscriptionId) subscriptions.get(subscriptionId).status = 'NO_ENVIADA';
    res.status(502).send(page('No se pudo enviar al POS', errorHtml(err)));
  }
});

// 3. Webhook: Virtualpos avisa que la inscripción terminó, con el uuid de la suscripción.
// Lee el cuerpo como texto sin importar el Content-Type: puede llegar con un valor no estándar.
app.post('/webhook', express.text({ type: () => true }), async (req, res) => {
  const subscriptionId = readUuid(req.body);
  res.sendStatus(200); // responde de inmediato; la consulta a la API va después
  if (!subscriptionId) return;
  try {
    await syncSubscription(subscriptionId);
  } catch (err) {
    console.error(`Webhook ${subscriptionId}: ${err.message}`);
  }
});

// 4. Cancela el envío si el cliente se fue o se eligió el POS equivocado.
app.post('/suscripciones/:id/cancelar', async (req, res) => {
  const sub = subscriptions.get(req.params.id);
  if (!sub) return res.status(404).send(page('Suscripción no encontrada', '<p><a href="/">Volver</a></p>'));
  try {
    await vp.cancelSendToPos(sub.posSerial, sub.subscriptionId, { forcedBy: 'crm', reason: 'Cancelado desde el CRM' });
    sub.status = 'ENVIO_CANCELADO';
    console.log(`Envío de ${sub.subscriptionId} al POS ${sub.posSerial} cancelado`);
    res.redirect(303, '/');
  } catch (err) {
    res.status(502).send(page('No se pudo cancelar el envío', errorHtml(err)));
  }
});

// Fuente de verdad: siempre consulta la API. Es idempotente: el webhook puede repetirse o llegar después de la consulta.
async function syncSubscription(subscriptionId) {
  const sub = subscriptions.get(subscriptionId);
  if (!sub) return; // no es una suscripción de este CRM
  const { suscription } = await vp.getSubscription(subscriptionId);
  if (Date.now() - sub.sentAt >= VIGENCIA_MS) sub.finalCheck = true; // una última consulta después de vencer
  if (['ENVIO_CANCELADO', 'NO_ENVIADA'].includes(sub.status) && suscription.status === 'SUSCRIBIENDO') return;
  const previous = sub.status;
  sub.status = suscription.status;
  if (sub.status === 'ACTIVA' && previous !== 'ACTIVA') {
    // Aquí marcas al cliente como suscrito en tu CRM. Solo una vez, aunque el aviso llegue repetido.
    const card = suscription.payment_method;
    sub.card = card ? `${card.brand} ****${card.last4CardDigit}` : undefined;
    console.log(`Suscripción ${subscriptionId} activa con ${sub.card ?? 'tarjeta inscrita'}`);
  }
}

// Pendiente en el POS, o vencida pero aún sin la última consulta.
function needsCheck(s) {
  return isPending(s) || (s.status === 'SUSCRIBIENDO' && !s.finalCheck);
}

function isPending(s) {
  return s.status === 'SUSCRIBIENDO' && Date.now() - s.sentAt < VIGENCIA_MS;
}

function label(s) {
  if (s.status === 'ACTIVA') return `✔ Activa${s.card ? ` (${escape(s.card)})` : ''}`;
  if (s.status === 'SUSCRIPCION_FALLIDA') return '✘ Falló: crea una nueva suscripción';
  if (s.status === 'ENVIO_CANCELADO') return 'Envío cancelado';
  if (s.status === 'NO_ENVIADA') return '✘ No llegó al POS';
  if (s.status === 'SUSCRIBIENDO') return isPending(s) ? '⏳ Esperando la tarjeta en el POS' : '✘ Venció: crea una nueva suscripción';
  return escape(s.status);
}

function readUuid(raw) {
  if (!raw) return undefined;
  try {
    return JSON.parse(raw).uuid;
  } catch {
    return new URLSearchParams(raw).get('uuid') ?? undefined;
  }
}

function errorHtml(err) {
  const detail = err instanceof VirtualposError ? err.message : String(err.message ?? err);
  return `<p><code>${escape(detail)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}<p><a href="/">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${escape(title)}</title>
<body style="font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem"><h1>${escape(title)}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`CRM de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
