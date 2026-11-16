// Guía: Cobrar por link (email, WhatsApp o SMS)
// https://docs.virtualpos.cl/guias/cobro-por-link/
import express from 'express';
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});
const PUBLIC_URL = (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const PORT = Number(process.env.PORT ?? 3000);

// En tu sistema esto sería tu base de datos de cobros.
const charges = new Map(); // chargeId -> { chargeId, amount, description, uuid, link, status, customer, sends: [] }
const FINAL = new Set(['pagado', 'rechazado', 'expirado', 'cancelado', 'anulado']);

const app = express();
app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// 1. Panel del comercio: formulario para cobrar y listado de cobros.
app.get('/', (_req, res) => {
  const rows = [...charges.values()].reverse().map((c) => `
    <tr><td><code>${c.chargeId}</code><br><a href="${c.link}" target="_blank">link de pago</a></td><td>$${c.amount.toLocaleString('es-CL')}</td><td><strong>${c.status}</strong></td>
    <td>${c.sends.map((s) => `${s.channel} ${escape(s.receiver)}${s.error ? ` ✘ ${escape(s.error)}` : ' ✔'}`).join('<br>')}</td>
    <td>${c.status === 'pendiente' ? `<form method="post" action="/cobros/${c.chargeId}/enviar"><button>Reenviar link</button></form>` : ''}
        ${['rechazado', 'expirado', 'cancelado'].includes(c.status) ? `<form method="post" action="/cobros/${c.chargeId}/nuevo"><button>Cobrar de nuevo</button></form>` : ''}
        <form method="post" action="/cobros/${c.chargeId}/estado"><button>Consultar estado</button></form></td></tr>`).join('');
  res.send(page('Cobrar por link', `
    <form method="post" action="/cobros" style="display:grid;gap:.5rem">
      <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
      <label>Descripción <input name="description" value="Reserva de mesa" required></label>
      <fieldset><legend>Cliente</legend>
        <label>Nombre <input name="first_name" value="Ana" required></label>
        <label>Apellido <input name="last_name" value="Pérez" required></label>
        <label>RUT <input name="social_id" value="11111111-1" required></label>
        <label>Email <input name="email" type="email" value="cliente@example.com" required></label>
        <label>Teléfono <input name="phone" value="56912345678" pattern="569[0-9]{8}" required></label>
      </fieldset>
      <label>Enviar por <select name="channel">
        <option value="EMAIL">Email</option><option value="WHATSAPP">WhatsApp</option><option value="SMS">SMS</option>
      </select></label>
      <button>Crear cobro y enviar link</button>
    </form>
    <h2>Cobros</h2>
    <table border="1" cellpadding="4" style="border-collapse:collapse;width:100%">
      <tr><th>Cobro</th><th>Monto</th><th>Estado</th><th>Envíos</th><th></th></tr>${rows}
    </table>`));
});

// 2. Crea el Payment y envía el link al cliente por el canal elegido.
app.post('/cobros', async (req, res) => {
  const customer = {
    email: req.body.email,
    social_id: req.body.social_id,
    first_name: req.body.first_name,
    last_name: req.body.last_name,
    phone: req.body.phone, // formato 569NNNNNNNN
  };
  try {
    await createCharge({ amount: Number(req.body.amount), description: req.body.description, customer, channel: req.body.channel });
    res.redirect(303, '/');
  } catch (err) {
    res.status(502).send(page('No se pudo crear el cobro', errorHtml(err)));
  }
});

// 3. Reenvía el link de un cobro pendiente (por ejemplo, si el cliente no lo encuentra).
app.post('/cobros/:chargeId/enviar', async (req, res) => {
  const charge = charges.get(req.params.chargeId);
  if (!charge) return res.status(404).send(page('Cobro no encontrado', ''));
  await send(charge, charge.sends.at(-1)?.channel ?? 'EMAIL');
  res.redirect(303, '/');
});

// 4. Consulta el estado en la API (útil si el webhook no llegó).
app.post('/cobros/:chargeId/estado', async (req, res) => {
  const charge = charges.get(req.params.chargeId);
  if (!charge) return res.status(404).send(page('Cobro no encontrado', ''));
  try {
    await syncPayment(charge.uuid);
    res.redirect(303, '/');
  } catch (err) {
    res.status(502).send(page('No se pudo consultar el cobro', errorHtml(err)));
  }
});

// 5. Cobrar de nuevo: un pago rechazado, expirado o cancelado ya no se puede pagar ni reenviar.
//    Crea otro Payment con los mismos datos y envía el link nuevo.
app.post('/cobros/:chargeId/nuevo', async (req, res) => {
  const old = charges.get(req.params.chargeId);
  if (!old) return res.status(404).send(page('Cobro no encontrado', ''));
  try {
    await createCharge({ amount: old.amount, description: old.description, customer: old.customer, channel: old.sends.at(-1)?.channel ?? 'EMAIL' });
    res.redirect(303, '/');
  } catch (err) {
    res.status(502).send(page('No se pudo crear el cobro', errorHtml(err)));
  }
});

// 6. Webhook: Virtualpos avisa que el pago cambió. Responde 200 de inmediato y consulta el resultado después.
app.post('/webhook', (req, res) => {
  const uuid = req.body?.uuid;
  res.sendStatus(200);
  console.log(`Webhook recibido para ${uuid}`);
  if (uuid) syncPayment(uuid).catch((err) => console.error('Webhook:', err.message, err.hint ?? ''));
});

// 7. Retorno: el cliente pagó desde el link y Virtualpos lo trae de vuelta con el uuid por POST.
//    Es tu cliente, no tu equipo: muéstrale una confirmación simple.
app.post('/gracias', async (req, res) => {
  try {
    const charge = await syncPayment(req.body?.uuid);
    res.send(page(charge.status === 'pagado' ? '¡Gracias por tu pago!' : 'El pago no se completó',
      charge.status === 'pagado'
        ? `<p>Recibimos tu pago de $${charge.amount.toLocaleString('es-CL')}.</p>`
        : '<p>No se realizó ningún cargo. Si quieres intentarlo de nuevo, pídenos un nuevo link de pago.</p>'));
  } catch (err) {
    res.status(502).send(page('No pudimos confirmar tu pago', errorHtml(err)));
  }
});

// Crea el Payment de un cobro y envía el link. merchant_internal_code une el pago con tu cobro.
async function createCharge({ amount, description, customer, channel }) {
  const chargeId = `COB-${Date.now()}`;
  const { payment, url_redirect } = await vp.createPayment({
    amount,
    ...customer,
    description,
    merchant_internal_code: chargeId,
    merchant_internal_channel: 'cobro-por-link',
    return_url: `${PUBLIC_URL}/gracias`,
    callback_url: `${PUBLIC_URL}/webhook`,
  });
  // url_redirect abre el Web Checkout de este pago. Guárdalo por si quieres compartirlo a mano.
  const charge = { chargeId, amount, description, uuid: payment.order.uuid, link: url_redirect, status: payment.order.status, customer, sends: [] };
  charges.set(chargeId, charge);
  console.log(`Payment ${charge.uuid} creado para ${chargeId}`);
  await send(charge, channel);
  return charge;
}

// Envía el link con POST /v3/payment/{uuid}/send. Para WhatsApp y SMS el destinatario es el teléfono con +56.
async function send(charge, channel) {
  const receiver = channel === 'EMAIL' ? charge.customer.email : `+${charge.customer.phone}`;
  try {
    const result = await vp.sendPayment(charge.uuid, channel, receiver);
    charge.sends.push({ channel, receiver, at: new Date().toISOString() });
    console.log(`Link de ${charge.chargeId} enviado por ${channel} a ${receiver} (${result.send_status})`);
  } catch (err) {
    // El cobro ya existe: si el envío falla, corrige el dato y reenvía. No crees otro Payment.
    charge.sends.push({ channel, receiver, error: err.hint ?? err.message });
    console.error(`No se pudo enviar ${charge.chargeId}: ${err.message}`);
  }
}

// Fuente de verdad: siempre consulta el Payment en la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse.
async function syncPayment(uuid) {
  if (!uuid) throw new Error('No llegó el uuid del pago');
  const { payment } = await vp.getPayment(uuid);
  const charge = charges.get(payment.order.merchant_internal_code);
  if (!charge) throw new Error(`El pago ${uuid} no corresponde a un cobro de este sistema`);
  if (FINAL.has(charge.status)) return charge; // ya procesado: no lo proceses dos veces
  charge.status = payment.order.status;
  if (charge.status === 'pagado') {
    // Aquí registras el pago en tu sistema: emite la boleta, confirma la reserva…
    console.log(`Cobro ${charge.chargeId} pagado (autorización ${payment.order.auth_code})`);
  }
  return charge;
}

function errorHtml(err) {
  const detail = err instanceof VirtualposError ? err.message : String(err.message ?? err);
  return `<p><code>${escape(detail)}</code></p>${err.hint ? `<p>${escape(err.hint)}</p>` : ''}<p><a href="/">Volver</a></p>`;
}
const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const page = (title, body) => `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem"><h1>${title}</h1>${body}</body>`;

app.listen(PORT, () => console.log(`Cobros por link en http://localhost:${PORT} (URL pública: ${PUBLIC_URL})`));
