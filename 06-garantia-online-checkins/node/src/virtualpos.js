// Cliente mínimo de Checkins de Virtualpos: firma JWT con ciat, requests y errores con pistas.
// Docs: https://docs.virtualpos.cl/checkins/introduccion/
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

// Pistas para los errores más frecuentes al integrar Checkins.
export function hintFor(errorCode, message = '') {
  switch (errorCode) {
    case 'E-021':
      return 'Firma no válida. En Checkins el payload del JWT es {"ciat": <hora actual en segundos>}, no {"api_key": ...} como en Payments.';
    case 'E-025':
      return 'La firma no coincide. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.';
    case 'E-022':
    case 'E-023':
      return 'La firma usa la hora actual (ciat) y tolera 5 minutos. Genera una firma nueva en cada request y sincroniza el reloj de tu servidor (NTP).';
    case 'E-002':
      return 'Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?';
    case 'E-CHK-305':
    case 'E-CHK-306':
      return 'El check-in no está en un estado que permita esta operación. Consulta su estado con GET /v3/checkins/{uuid}.';
    case 'E-CHK-308':
      return 'El monto supera lo permitido. El checkout no puede exceder la garantía autorizada: usa un incremental antes o un cargo adicional después.';
  }
  if (message.toLowerCase().includes('missing required request parameters')) {
    return 'Faltan los headers Authorization o Signature.';
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

  // Checkins firma con la hora actual: JWT HS256 con {"ciat": <segundos>}. Vale 5 minutos, así que se genera en cada request.
  signature() {
    return jwt.sign({ ciat: Math.floor(Date.now() / 1000) }, this.secretKey, { algorithm: 'HS256', noTimestamp: true });
  }

  async request(method, path, body) {
    const res = await fetch(this.apiUrl + path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: this.apiKey,
        Signature: this.signature(),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    // Los errores llegan casi siempre con HTTP 200: revisa también status y error en el cuerpo.
    if (!res.ok || data.status === 'NOK' || data.error) {
      throw new VirtualposError(data.code ?? res.status, data.error?.error_code, data.error?.message ?? data.message ?? res.statusText);
    }
    return data;
  }

  // POST /v3/checkins — https://docs.virtualpos.cl/checkins/crear-checkin/
  createCheckin(checkin) {
    return this.request('POST', '/v3/checkins', {
      ...checkin,
      // Ambas URLs van en Base64.
      callback_url: toBase64(checkin.callback_url),
      return_url: toBase64(checkin.return_url),
    });
  }

  // POST /v3/checkins/cof/{uuid_cli}/{card_reference_id} — cliente y tarjeta ya registrados, garantía inmediata y sin formulario.
  // https://docs.virtualpos.cl/checkins/crear-checkin-cliente-existente/
  createCheckinWithSavedCard(uuidCli, cardReferenceId, checkin) {
    return this.request('POST', `/v3/checkins/cof/${encodeURIComponent(uuidCli)}/${encodeURIComponent(cardReferenceId)}`, {
      ...checkin,
      callback_url: toBase64(checkin.callback_url),
    });
  }

  // GET /v3/checkins/{uuid} — https://docs.virtualpos.cl/checkins/recuperar-checkin/
  getCheckin(uuid) {
    return this.request('GET', `/v3/checkins/${encodeURIComponent(uuid)}`);
  }

  // POST /v3/checkins/authorize — garantía diferida. https://docs.virtualpos.cl/checkins/autorizar-checkin/
  authorize(uuid, amount) {
    return this.request('POST', '/v3/checkins/authorize', { uuid, amount });
  }

  // POST /v3/checkins/incremental — https://docs.virtualpos.cl/checkins/crear-incremental/
  incremental(uuid, amount, description) {
    return this.request('POST', '/v3/checkins/incremental', { uuid, amount, description });
  }

  // POST /v3/checkins/checkout — https://docs.virtualpos.cl/checkins/crear-checkout/
  checkout(uuid, finalAmount) {
    return this.request('POST', '/v3/checkins/checkout', { uuid, final_checkout_amount: finalAmount });
  }

  // DELETE /v3/checkins/{uuid} — libera la garantía. https://docs.virtualpos.cl/checkins/cancelar-garantia/
  cancel(uuid) {
    return this.request('DELETE', `/v3/checkins/${encodeURIComponent(uuid)}`);
  }

  // POST /v3/checkins/additional-charges — después del checkout. https://docs.virtualpos.cl/checkins/crear-cargos-adicionales/
  additionalCharge(uuid, amount, reason) {
    return this.request('POST', '/v3/checkins/additional-charges', { uuid, amount, charge_reason: reason });
  }
}

const toBase64 = (s) => Buffer.from(s, 'utf8').toString('base64');
