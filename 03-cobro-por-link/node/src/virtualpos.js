// Cliente mínimo de la API de Virtualpos: firma JWT, requests y errores con pistas.
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
  if (m.includes('no cumple con formato para el channel')) {
    return 'El destinatario no corresponde al canal: EMAIL necesita un email; WHATSAPP y SMS, un teléfono con +56, por ejemplo +56912345678.';
  }
  if (m.includes('channel no permitido')) {
    return 'Canal no reconocido o no habilitado para tu cuenta. Usa EMAIL, WHATSAPP o SMS; si es uno de ellos, pídele a tu ejecutivo que lo habilite.';
  }
  if (m.includes('parámetro channel') || m.includes('parametro channel')) {
    return 'Falta el canal: envía channel con EMAIL, WHATSAPP o SMS.';
  }
  if (m.includes('parámetro receiver') || m.includes('parametro receiver')) {
    return 'Falta el destinatario (receiver).';
  }
  if (m.includes('no existe la orden')) {
    return 'No hay un pago pendiente con ese uuid: ya se pagó o terminó (al enviar, la API responde así), o el uuid es de otro ambiente. Consulta su estado.';
  }
  if (m.includes('firma')) {
    return 'Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {"api_key": "<tu API KEY>"}.';
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

  // La firma es un JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY.
  signature() {
    return jwt.sign({ api_key: this.apiKey }, this.secretKey, { algorithm: 'HS256', noTimestamp: true });
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
    // Algunos errores llegan con HTTP 200: revisa también status y error en el cuerpo.
    if (!res.ok || data.status === 'NOK' || data.error) {
      throw new VirtualposError(res.status, data.error?.error_code, data.error?.message ?? data.message ?? res.statusText);
    }
    return data;
  }

  // POST /v3/payment/ — https://docs.virtualpos.cl/payments/crear-un-objeto-payment/
  createPayment(payment) {
    return this.request('POST', '/v3/payment/', {
      ...payment,
      // Ambas URLs van en Base64 y deben enviarse juntas: si falta una, Virtualpos no guarda ninguna.
      return_url: toBase64(payment.return_url),
      callback_url: toBase64(payment.callback_url),
    });
  }

  // GET /v3/payment/{uuid} — https://docs.virtualpos.cl/payments/recuperar-un-objeto-payment/
  getPayment(uuid) {
    return this.request('GET', `/v3/payment/${encodeURIComponent(uuid)}`);
  }

  // POST /v3/payment/{uuid}/send — https://docs.virtualpos.cl/payments/envio-de-payment-por-email-whatsapp-sms/
  // channel: EMAIL, WHATSAPP o SMS. receiver: un email, o un teléfono con +56 (por ejemplo +56912345678).
  sendPayment(uuid, channel, receiver) {
    return this.request('POST', `/v3/payment/${encodeURIComponent(uuid)}/send`, { channel, receiver });
  }
}

const toBase64 = (s) => Buffer.from(s, 'utf8').toString('base64');
