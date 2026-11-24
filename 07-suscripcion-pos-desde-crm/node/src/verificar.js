// Verifica tus credenciales sin crear nada ni enviar nada al POS: consulta una suscripción que no existe.
// Si la API responde "no existen suscripciones", tu API KEY y tu firma están bien.
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});

try {
  await vp.getSubscription('subsc_00000000-0000-0000-0000-000000000000');
  console.log('✔ Credenciales válidas');
} catch (err) {
  if (err instanceof VirtualposError && /no existen suscripciones/i.test(err.apiMessage)) {
    console.log(`✔ Credenciales válidas para ${vp.apiUrl}`);
  } else {
    console.error(`✘ ${err.message}`);
    if (err.hint) console.error(`  ${err.hint}`);
    process.exit(1);
  }
}
if (!process.env.VIRTUALPOS_PLAN_ID) console.log('Falta VIRTUALPOS_PLAN_ID: créalo con npm run crear-plan');
