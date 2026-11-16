// Verifica tus credenciales sin crear nada: consulta un check-in que no existe.
// Si la API responde "No existe un checkin", tu API KEY, tu SECRET KEY y tu firma están bien.
import { Virtualpos, VirtualposError } from './virtualpos.js';

const vp = new Virtualpos({
  apiUrl: process.env.VIRTUALPOS_API_URL,
  apiKey: process.env.VIRTUALPOS_API_KEY,
  secretKey: process.env.VIRTUALPOS_SECRET_KEY,
});

try {
  await vp.getCheckin('chk_00000000-0000-0000-0000-000000000000');
  console.log('✔ Credenciales válidas');
} catch (err) {
  if (err instanceof VirtualposError && /no existe un checkin/i.test(err.apiMessage)) {
    console.log(`✔ Credenciales válidas para ${vp.apiUrl}`);
  } else {
    console.error(`✘ ${err.message}`);
    if (err.hint) console.error(`  ${err.hint}`);
    process.exit(1);
  }
}
