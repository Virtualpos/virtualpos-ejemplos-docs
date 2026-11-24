// Cliente mínimo de la API de Virtualpos para suscripciones presenciales: firma JWT, requests y errores con pistas.
// Docs: https://docs.virtualpos.cl/general/autenticacion-y-firma/
import jwt from 'jsonwebtoken';

export class VirtualposError extends Error {
  constructor(httpStatus, errorCode, message) {
    super(`${errorCode ?? httpStatus}: ${message}`);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.apiMessage = message;
    this.hint = hintFor(errorCode, message);
  }
}

// Pistas para los errores más frecuentes al enviar suscripciones al POS.
export function hintFor(errorCode = '', message = '') {
  const m = String(message).toLowerCase();
  if (m.includes('missing required request parameters')) return 'Faltan los headers Authorization o Signature.';
  if (m.includes('firma')) return 'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {"api_key": "<tu API KEY>"}.';
  if (m.includes('no existe el comercio') || m.includes('no existe cuenta')) return 'Tu API KEY no existe en este ambiente. Revisa que la URL y las llaves sean del mismo ambiente.';
  if (m.includes('sin contrato pat')) return 'Tu cuenta no tiene Suscripciones activo en este ambiente. Pídele a tu ejecutivo que lo habilite.';
  if (errorCode === 'E-PTT-002') return 'Solo se puede enviar una suscripción recién creada (SUSCRIBIENDO). Si falló o venció, crea una nueva.';
  if (errorCode === 'E-PTT-003') return 'El plan no está habilitado para el POS: créalo con show_in_terminal "T" (npm run crear-plan).';
  if (errorCode === 'E-PTT-005') return 'La suscripción ya se envió a otro POS. Crea una nueva para enviarla a este.';
  if (errorCode === 'E-PTT-006') return 'Ese POS no está asignado a tu comercio en este ambiente. Revisa el serial.';
  if (errorCode === 'E-PTT-011') return 'El plan pide dirección de despacho: esos planes no se pueden suscribir en el POS.';
  if (errorCode === 'E-PTT-012') return 'No hay un envío previo de esta suscripción a ese POS.';
  if (['E-PTT-008', 'E-PTT-009', 'E-PTT-013', 'E-PTT-014'].includes(errorCode)) return 'No se pudo llegar al POS. Revisa que esté encendido y con internet, y vuelve a intentar.';
  return undefined;
}

export class Virtualpos {
  constructor({ apiUrl, apiKey, secretKey }) {
    if (!apiUrl || !apiKey || !secretKey) {
      throw new Error('Configura VIRTUALPOS_API_URL, VIRTUALPOS_API_KEY y VIRTUALPOS_SECRET_KEY en tu archivo .env');
    }
    this.apiUrl = apiUrl.replace(/\/$/, '');
    this.apiKey = apiKey;
    this.secretKey = secretKey;
  }

  // JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
  signature() {
    return jwt.sign({ api_key: this.apiKey }, this.secretKey, { algorithm: 'HS256', noTimestamp: true });
  }

  async request(method, path, body) {
    const res = await fetch(this.apiUrl + path, {
      method,
      headers: { 'Content-Type': 'application/json', Authorization: this.apiKey, Signature: this.signature() },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    // Algunos errores llegan con HTTP 200: revisa también status y error en el cuerpo.
    if (!res.ok || data.status === 'NOK' || data.error) {
      throw new VirtualposError(res.status, data.error?.error_code, data.error?.message ?? data.message ?? res.statusText);
    }
    return data;
  }

  // POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/
  createPlan(plan) {
    return this.request('POST', '/v3/plan', plan);
  }

  // POST /v3/suscription — https://docs.virtualpos.cl/suscripciones/suscription/crear-una-suscription/
  createSubscription(data) {
    return this.request('POST', '/v3/suscription', {
      ...data,
      // Ambas URLs van en Base64.
      return_url: toBase64(data.return_url),
      callback_url: toBase64(data.callback_url),
    });
  }

  // POST /v3/suscription/push-to-terminal — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/enviar-suscripcion-al-pos/
  sendToPos(posSerial, subscriptionId) {
    return this.request('POST', '/v3/suscription/push-to-terminal', {
      terminal_serial_number: posSerial,
      suscription_uuid: subscriptionId,
    });
  }

  // POST /v3/suscription/push-to-terminal/cancel — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/cancelar-envio-al-pos/
  cancelSendToPos(posSerial, subscriptionId, { forcedBy, reason }) {
    return this.request('POST', '/v3/suscription/push-to-terminal/cancel', {
      terminal_serial_number: posSerial,
      suscription_uuid: subscriptionId,
      forced_by: forcedBy,
      reason,
    });
  }

  // GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/
  getSubscription(subscriptionId) {
    return this.request('GET', `/v3/suscription/${encodeURIComponent(subscriptionId)}`);
  }
}

const toBase64 = (s) => Buffer.from(s, 'utf8').toString('base64');
