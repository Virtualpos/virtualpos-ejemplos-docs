// Verifica tu API key de POS Cloud to Cloud sin crear operaciones: lista tu última operación.
import { VirtualposPos } from './virtualpos-pos.js';

const pos = new VirtualposPos({
  apiUrl: process.env.VIRTUALPOS_POS_API_URL,
  apiKey: process.env.VIRTUALPOS_POS_API_KEY,
});

try {
  const { items } = await pos.listOperations(1);
  console.log(`✔ API key válida en ${pos.apiUrl}`);
  console.log(items.length ? `  Última operación: ${items[0].externalPaymentId} (${items[0].status})` : '  Aún no tienes operaciones.');
  console.log(process.env.VIRTUALPOS_POS_SERIAL ? `  Serial del POS: ${process.env.VIRTUALPOS_POS_SERIAL}` : '✘ Falta VIRTUALPOS_POS_SERIAL en tu .env');
  console.log(process.env.VIRTUALPOS_WEBHOOK_SECRET ? '  Secreto de webhook configurado.' : '! Falta VIRTUALPOS_WEBHOOK_SECRET: no podrás verificar webhooks.');
} catch (err) {
  console.error(`✘ ${err.message}`);
  if (err.hint) console.error(`  ${err.hint}`);
  process.exitCode = 1;
}
