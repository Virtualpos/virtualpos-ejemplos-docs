// Crea una sola vez el plan que ofrecerás en el POS y muestra su ID para que lo guardes en VIRTUALPOS_PLAN_ID.
// https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/
import { Virtualpos } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});

try {
  const { plan } = await vp.createPlan({
    name: 'Plan mensual',
    description: 'Suscripción mensual de ejemplo',
    type: 'MONTO_FIJO',
    currency: 'CLP',
    amount: 9990,
    frequency_type: 'Mensual',
    num_charges: 12,
    trial_days: 0,
    fixed_amount_day_charge: '05',
    return_url: process.env.PUBLIC_URL ?? 'http://localhost:3000', // texto plano, no Base64
    show_in_terminal: 'T', // sin esto, el POS rechaza la suscripción (E-PTT-003)
    shipping_address: 'F', // los planes con dirección de despacho no se pueden suscribir en el POS
  });
  console.log(`✔ Plan creado: ${plan.name} (${plan.type}, ${plan.frequency_type})`);
  console.log(`  Agrega esta línea a tu .env:\n  VIRTUALPOS_PLAN_ID=${plan.id}`);
} catch (err) {
  console.error(`✘ ${err.message}`);
  if (err.hint) console.error(`  ${err.hint}`);
  process.exit(1);
}
