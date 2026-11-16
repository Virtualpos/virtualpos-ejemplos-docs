// Cliente mínimo de la API de Virtualpos para suscripciones: firmas JWT, requests y errores con pistas.
// Docs: https://docs.virtualpos.cl/general/autenticacion-y-firma/
import jwt from 'jsonwebtoken';

export class VirtualposError extends Error {
  constructor(httpStatus, errorCode, message) {
    super(`${errorCode ?? httpStatus}: ${message}`);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.apiMessage = message;
    this.hint = hintFor(message);
  }
}

// Pistas para los errores más frecuentes que recibe soporte.
// Se busca por mensaje porque el mismo caso puede venir con distintos códigos según el endpoint.
export function hintFor(message = '') {
  const m = message.toLowerCase();
  if (m.includes('missing required request parameters')) {
    return 'Faltan los headers Authorization o Signature.';
  }
  if (m.includes('firma no válida')) {
    return 'Pagar y suscribir se firma con la hora actual: payload {"ciat": <timestamp en segundos>}, no con {"api_key"}.';
  }
  if (m.includes('parámetros en la firma')) {
    return 'Planes, suscripciones y cargos se firman con payload {"api_key": "<tu API KEY>"}, no con {"ciat"}.';
  }
  if (m.includes('firma')) {
    return 'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.';
  }
  if (m.includes('no es de tipo monto_variable')) {
    return 'Pagar y suscribir solo acepta planes de tipo MONTO_VARIABLE. Crea uno con npm run crear-plan.';
  }
  if (m.includes('charge_date')) {
    return 'La fecha del cargo (charge_date) debe tener formato AAAA-MM-DD y ser hoy o una fecha futura.';
  }
  if (m.includes('condiciones para realizar el reintento')) {
    return 'Solo se puede reintentar un cargo en estado rechazado.';
  }
  if (m.includes('no existe el comercio') || m.includes('no existe cuenta')) {
    return 'Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?';
  }
  if (m.includes('contrato') || m.includes('no está habilitado') || m.includes('no esta habilitado')) {
    return 'El producto no está activo para tu cuenta en este ambiente. Pídele a tu ejecutivo que lo habilite.';
  }
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

  // Planes, suscripciones y cargos: JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
  signature() {
    return jwt.sign({ api_key: this.apiKey }, this.secretKey, { algorithm: 'HS256', noTimestamp: true });
  }

  // Pagar y suscribir: JWT HS256 con la hora actual (ciat, en segundos).
  ciatSignature() {
    return jwt.sign({ ciat: Math.floor(Date.now() / 1000) }, this.secretKey, { algorithm: 'HS256', noTimestamp: true });
  }

  async request(method, path, body, signature = this.signature()) {
    const res = await fetch(this.apiUrl + path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: this.apiKey,
        Signature: signature,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    // Los errores llegan con HTTP 200: revisa también status y error en el cuerpo.
    if (!res.ok || data.status === 'NOK' || data.error) {
      throw new VirtualposError(res.status, data.error?.error_code, data.error?.message ?? data.message ?? res.statusText);
    }
    return data;
  }

  // POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/
  createPlan(plan) {
    return this.request('POST', '/v3/plan', plan);
  }

  // POST /v3/pay-and-subscribe — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/iniciar-transaccion/
  startPayAndSubscribe(data) {
    return this.request('POST', '/v3/pay-and-subscribe', {
      ...data,
      // Ambas URLs van en Base64.
      return_url: toBase64(data.return_url),
      callback_url: toBase64(data.callback_url),
    }, this.ciatSignature());
  }

  // GET /v3/pay-and-subscribe/{id} — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/obtener-estado/
  getPayAndSubscribe(subscriptionId) {
    return this.request('GET', `/v3/pay-and-subscribe/${encodeURIComponent(subscriptionId)}`, null, this.ciatSignature());
  }

  // GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/
  getSubscription(subscriptionId) {
    return this.request('GET', `/v3/suscription/${encodeURIComponent(subscriptionId)}`);
  }

  // POST /v3/charge — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge/
  scheduleCharge({ subscriptionId, chargeDate, amount, description, internalCode }) {
    return this.request('POST', '/v3/charge', {
      suscription_id: subscriptionId,
      charge_date: chargeDate,
      amount,
      description,
      internal_code: internalCode,
    });
  }

  // POST /v3/charge/online — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge-online/
  chargeNow({ subscriptionId, amount, description, internalCode }) {
    return this.request('POST', '/v3/charge/online', {
      suscription_id: subscriptionId,
      amount,
      description,
      internal_code: internalCode,
    });
  }

  // GET /v3/suscription/{id}/charges — https://docs.virtualpos.cl/suscripciones/charge/listar-charges/
  listCharges(subscriptionId) {
    return this.request('GET', `/v3/suscription/${encodeURIComponent(subscriptionId)}/charges`);
  }

  // GET /v3/charge/{id}/retry — https://docs.virtualpos.cl/suscripciones/charge/reintentar-charge/
  retryCharge(chargeId) {
    return this.request('GET', `/v3/charge/${encodeURIComponent(chargeId)}/retry`);
  }

  // PUT /v3/suscription/{id}/changecard — https://docs.virtualpos.cl/suscripciones/suscription/generar-link-de-cambio-de-tarjeta/
  changeCardLink(subscriptionId) {
    return this.request('PUT', `/v3/suscription/${encodeURIComponent(subscriptionId)}/changecard`);
  }

  // DELETE /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/cancelar-una-suscription/
  cancelSubscription(subscriptionId) {
    return this.request('DELETE', `/v3/suscription/${encodeURIComponent(subscriptionId)}`);
  }
}

const toBase64 = (s) => Buffer.from(s, 'utf8').toString('base64');
