// Verifica tus credenciales y las dos firmas sin crear nada: consulta una suscripción que no existe.
// Si la API responde "no existe" / "no se encontró", tu API KEY, tu SECRET KEY y tus firmas están bien.
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});
const FAKE_ID = 'subsc_00000000-0000-0000-0000-000000000000';

const checks = [
  ['Firma {"api_key"} (planes, suscripciones y cargos)', () => vp.getSubscription(FAKE_ID), /no existen suscripciones/i],
  ['Firma {"ciat"} (Pagar y suscribir)', () => vp.getPayAndSubscribe(FAKE_ID), /no se encontr/i],
];

let ok = true;
for (const [name, call, notFound] of checks) {
  try {
    await call();
    console.log(`✔ ${name}`);
  } catch (err) {
    if (err instanceof VirtualposError && notFound.test(err.apiMessage)) {
      console.log(`✔ ${name}`);
    } else {
      ok = false;
      console.error(`✘ ${name}: ${err.message}`);
      if (err.hint) console.error(`  ${err.hint}`);
    }
  }
}
if (!ok) process.exit(1);
console.log(`Credenciales válidas para ${vp.apiUrl}`);
if (!process.env.VIRTUALPOS_PLAN_ID) console.log('Falta VIRTUALPOS_PLAN_ID: créalo con npm run crear-plan');
