// Guía: Cobrar una suscripción mensual
// https://docs.virtualpos.cl/guias/suscripcion-mensual/
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
const MONTHLY_AMOUNT = 9990;

// En tu sistema esto sería tu base de datos de clientes y suscripciones.
const subscriptions = new Map(); // subscriptionId -> { subscriptionId, customerId, status }

const app = express();
app.use(express.urlencoded({ extended: false }));

// 1. Página de tu sitio con el botón para suscribirse.
app.get('/', (_req, res) => {
  const rows = [...subscriptions.values()]
    .map((s) => `<li><a href="/suscripciones/${s.subscriptionId}">${s.customerId}</a>: ${s.status}</li>`)
    .join('');
  res.send(page('Suscripción de ejemplo', `
    <p>Plan mensual por <strong>$9.990</strong>. El primer mes se paga al suscribirse.</p>
    <form method="post" action="/suscribir"><button>Suscribirme</button></form>
    ${rows ? `<h2>Suscripciones</h2><ul>${rows}</ul>` : ''}`));
});

// 2. Inicia Pagar y suscribir y redirige al cliente al formulario de Virtualpos.
app.post('/suscribir', async (_req, res) => {
  const customerId = `CLI-${Date.now()}`;
  try {
    const { subscription, url_redirect } = await vp.startPayAndSubscribe({
      email: `${customerId.toLowerCase()}@example.com`, // en tu sistema, el email de tu cliente
      social_id: '11111111-1',
      first_name: 'Ana',
      last_name: 'Pérez',
      phone_number: '56912345678',
      plan_id: PLAN_ID,
      service_id: customerId,
      channel: 'WEB',
      activation_amount: MONTHLY_AMOUNT,
      payment_description: 'Primer mes',
      merchant_internal_code: `${customerId}-${period(new Date())}`,
      return_url: `${PUBLIC_URL}/retorno`,
      callback_url: `${PUBLIC_URL}/webhook`,
    });
    subscriptions.set(subscription.id, { subscriptionId: subscription.id, customerId, status: subscription.status });
    console.log(`Suscripción ${subscription.id} iniciada para ${customerId}`);
    res.redirect(303, url_redirect);
  } catch (err) {
    res.status(502).send(page('No se pudo iniciar la suscripción', errorHtml(err)));
  }
});

// 3. Webhook: Virtualpos avisa que terminó la inscripción. Responde 200 de inmediato y consulta el resultado después.
//    El body es JSON {"uuid": "subsc_..."}, pero el Content-Type puede no ser estándar: léelo como texto.
app.post('/webhook', express.text({ type: () => true }), (req, res) => {
  res.sendStatus(200);
  const id = readUuid(req.body);
  if (id) syncSubscription(id).catch((err) => console.error('Webhook:', err.message, err.hint ?? ''));
});

// 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
app.post('/retorno', async (req, res) => {
  try {
    const sub = await syncSubscription(req.body?.uuid);
    res.send(page(sub.status === 'ACTIVA' ? '¡Suscripción activa!' : 'La suscripción no se completó', `
      <p>Cliente <code>${sub.customerId}</code>: <strong>${sub.status}</strong></p>
      <p>${sub.status === 'ACTIVA' ? `<a href="/suscripciones/${sub.subscriptionId}">Ver suscripción y cargos</a>` : '<a href="/">Intentar nuevamente</a>'}</p>`));
  } catch (err) {
    res.status(502).send(page('No se pudo confirmar la suscripción', errorHtml(err)));
  }
});

// 5. Detalle: estado, tarjeta y cargos, siempre leídos desde la API.
//    charge_program trae los cargos en todos los estados, también después de cancelar.
app.get('/suscripciones/:id', async (req, res) => {
  try {
    const { suscription } = await vp.getSubscription(req.params.id);
    const charges = suscription.charge_program ?? [];
    const card = suscription.payment_method;
    const active = suscription.status === 'ACTIVA';
    const rows = charges.map((c) => `<tr><td>${c.charge_date}</td><td>${c.description}</td><td>$${c.amount}</td><td>${c.status}</td><td>${
      c.status === 'rechazado'
        ? `<form method="post" action="/cargos/${c.id}/reintentar"><input type="hidden" name="subscriptionId" value="${req.params.id}"><button>Reintentar</button></form>`
        : ''}</td></tr>`).join('');
    res.send(page(`Suscripción ${suscription.status}`, `
      <p>${card ? `Tarjeta ${card.brand} terminada en ${card.last4CardDigit}` : 'Sin tarjeta'}</p>
      <h2>Cargos</h2>
      ${rows ? `<table>${rows}</table>` : '<p>Aún no hay cargos.</p>'}
      ${active ? `
      <form method="post" action="/suscripciones/${req.params.id}/programar"><button>Programar el cargo del próximo mes</button></form>
      <form method="post" action="/suscripciones/${req.params.id}/cobrar-ahora"><button>Cobrar el mes ahora</button></form>
      <form method="post" action="/suscripciones/${req.params.id}/cambiar-tarjeta"><button>Cambiar tarjeta</button></form>
      <form method="post" action="/suscripciones/${req.params.id}/cancelar"><button>Cancelar suscripción</button></form>` : ''}
      <p><a href="/">Volver</a></p>`));
  } catch (err) {
    res.status(502).send(page('No se pudo leer la suscripción', errorHtml(err)));
  }
});

// 6a. Programa el cargo del próximo mes: Virtualpos lo cobra en charge_date.
//     La API no rechaza un internal_code repetido: revisa tú que el mes no esté programado, o lo cobrarás dos veces.
app.post('/suscripciones/:id/programar', async (req, res) => {
  const next = addMonths(new Date(), 1);
  try {
    const { suscription } = await vp.getSubscription(req.params.id);
    const internalCode = `${suscription.service_id}-${period(next)}`;
    const { charges } = await vp.listCharges(req.params.id);
    if (charges.some((c) => c.internal_code === internalCode && c.status !== 'cancelado')) {
      console.log(`El cargo ${internalCode} ya estaba programado`);
      return res.redirect(303, `/suscripciones/${req.params.id}`);
    }
    const { charge } = await vp.scheduleCharge({
      subscriptionId: req.params.id,
      chargeDate: next.toISOString().slice(0, 10),
      amount: MONTHLY_AMOUNT,
      description: `Mensualidad ${period(next)}`,
      internalCode,
    });
    console.log(`Cargo ${charge.id} programado para ${charge.charge_date} (${charge.status})`);
    res.redirect(303, `/suscripciones/${req.params.id}`);
  } catch (err) {
    res.status(502).send(page('No se pudo programar el cargo', errorHtml(err)));
  }
});

// 6b. Cobra el mes ahora: la respuesta trae el resultado del cobro.
//     Úsalo para cobros fuera de calendario. internal_code es obligatorio en este endpoint.
app.post('/suscripciones/:id/cobrar-ahora', async (req, res) => {
  try {
    const { suscription } = await vp.getSubscription(req.params.id);
    const { charge } = await vp.chargeNow({
      subscriptionId: req.params.id,
      amount: MONTHLY_AMOUNT,
      description: `Mensualidad ${period(new Date())}`,
      internalCode: `${suscription.service_id}-${period(new Date())}-${Date.now()}`,
    });
    console.log(`Cargo ${charge.id}: ${charge.status}`);
    res.redirect(303, `/suscripciones/${req.params.id}`);
  } catch (err) {
    res.status(502).send(page('No se pudo cobrar', errorHtml(err)));
  }
});

// 7. Reintenta un cargo rechazado.
app.post('/cargos/:chargeId/reintentar', async (req, res) => {
  try {
    const { message } = await vp.retryCharge(req.params.chargeId);
    console.log(message);
    res.redirect(303, `/suscripciones/${req.body.subscriptionId}`);
  } catch (err) {
    res.status(502).send(page('No se pudo reintentar', errorHtml(err)));
  }
});

// 8. Cambio de tarjeta: genera el link y envía al cliente a actualizar su tarjeta.
app.post('/suscripciones/:id/cambiar-tarjeta', async (req, res) => {
  try {
    const { change_card_url, change_card_expiration_date } = await vp.changeCardLink(req.params.id);
    console.log(`Link de cambio de tarjeta válido hasta ${change_card_expiration_date}`);
    res.redirect(303, change_card_url);
  } catch (err) {
    res.status(502).send(page('No se pudo generar el link', errorHtml(err)));
  }
});

// 9. Cancela la suscripción: Virtualpos deja de cobrar los cargos pendientes.
app.post('/suscripciones/:id/cancelar', async (req, res) => {
  try {
    const { suscription } = await vp.cancelSubscription(req.params.id);
    const sub = subscriptions.get(req.params.id);
    if (sub) sub.status = suscription.status;
    console.log(`Suscripción ${req.params.id}: ${suscription.status}`);
    res.redirect(303, `/suscripciones/${req.params.id}`);
  } catch (err) {
    res.status(502).send(page('No se pudo cancelar', errorHtml(err)));
  }
});

// Fuente de verdad: siempre consulta la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
async function syncSubscription(subscriptionId) {
  if (!subscriptionId) throw new Error('No llegó el id de la suscripción');
  const { suscription } = await vp.getSubscription(subscriptionId);
  const sub = subscriptions.get(subscriptionId) ?? { subscriptionId, customerId: suscription.service_id };
  const previous = sub.status;
  sub.status = suscription.status;
  subscriptions.set(subscriptionId, sub);
  if (sub.status === 'ACTIVA' && previous !== 'ACTIVA') {
    // Aquí activas el servicio de tu cliente. Solo una vez, aunque el aviso llegue repetido.
    const card = suscription.payment_method;
    console.log(`Suscripción ${subscriptionId} activa con ${card?.brand} ${card?.last4CardDigit}`);
  }
  return sub;
}

function readUuid(raw) {
  if (!raw) return undefined;
  try {
    return JSON.parse(raw).uuid;
  } catch {
    return new URLSearchParams(raw).get('uuid') ?? undefined;
  }
}

const period = (d) => d.toISOString().slice(0, 7); // AAAA-MM
// Mismo día del mes siguiente; si no existe (31 de enero), el último día del mes.
function addMonths(date, months) {
  const y = date.getUTCFullYear();
  const m = date.getUTCMonth() + months;
  const lastDay = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  return new Date(Date.UTC(y, m, Math.min(date.getUTCDate(), lastDay)));
}

function errorHtml(err) {
  const detail = err instanceof VirtualposError ? err.message : String(err.message ?? err);
  return `<p><code>${escape(detail)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}<p><a href="/">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:40rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Sitio de ejemplo en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
