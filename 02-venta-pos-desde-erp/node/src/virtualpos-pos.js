// Cliente mínimo de POS Cloud to Cloud (Ventas con tarjeta): requests, errores con pistas y firma de webhooks.
// Docs: https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/introduccion/
import crypto from 'node:crypto';

export const FINAL = new Set(['APPROVED', 'REJECTED', 'REVERSED', 'FAILED', 'EXPIRED', 'CANCELLED']);

export class PosError extends Error {
  constructor(httpStatus, message) {
    super(httpStatus ? `HTTP ${httpStatus}: ${message}` : message);
    this.httpStatus = httpStatus;
    this.apiMessage = message;
    this.hint = hintFor(httpStatus);
  }
}

// Pistas para los errores más frecuentes que recibe soporte.
export function hintFor(httpStatus) {
  switch (httpStatus) {
    case 401:
      return 'API key inválida. Revisa VIRTUALPOS_POS_API_KEY y que sea del mismo ambiente que VIRTUALPOS_POS_API_URL (integración o producción). No es la API KEY del portal de comercio.';
    case 403:
      return 'El POS no está habilitado para POS Cloud to Cloud en tu comercio. Revisa VIRTUALPOS_POS_SERIAL o pídele a tu ejecutivo que lo habilite.';
    case 404:
      return 'No existe esa operación para tu comercio. Revisa el paymentRequestId.';
    default:
      return httpStatus === 0 || httpStatus >= 500
        ? 'No sabes si la venta llegó al POS. Reintenta con el mismo externalPaymentId, nunca con uno nuevo: si ya se aprobó, Virtualpos no vuelve a cobrarla.'
        : undefined;
  }
}

export class VirtualposPos {
  constructor({ apiUrl, apiKey }) {
    if (!apiUrl || !apiKey) {
      throw new Error('Configura VIRTUALPOS_POS_API_URL y VIRTUALPOS_POS_API_KEY en tu archivo .env');
    }
    this.apiUrl = apiUrl.replace(/\/$/, '');
    this.apiKey = apiKey;
  }

  async request(method, path, body) {
    let res;
    try {
      res = await fetch(this.apiUrl + path, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-Api-Key': this.apiKey },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(15_000),
      });
    } catch (err) {
      throw new PosError(0, `Sin respuesta de Virtualpos: ${err.message}`); // timeout o red
    }
    const data = await res.json().catch(() => ({})); // 401 no trae body
    if (!res.ok) throw new PosError(res.status, data.message ?? res.statusText);
    return data;
  }

  // POST /api/v1/pos-operations — https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/crear-una-venta/
  // externalPaymentId es tu ID de la venta: repetirlo nunca cobra dos veces.
  createSale({ posSerial, externalPaymentId, amount, tipAmount = 0, callbackUrl }) {
    return this.request('POST', '/api/v1/pos-operations', {
      posSerial,
      operationType: 'SALE',
      externalPaymentId,
      amount,
      tipAmount,
      currency: 'CLP',
      callbackUrl,
    });
  }

  // GET /api/v1/pos-operations/{paymentRequestId} — estado actual, sin el detalle de la tarjeta.
  getOperation(paymentRequestId) {
    return this.request('GET', `/api/v1/pos-operations/${encodeURIComponent(paymentRequestId)}`);
  }

  // GET /api/v1/pos-operations/{paymentRequestId}/callback-event — el webhook armado con el estado actual,
  // incluido resultPayload (autorización, tarjeta). Útil si no recibiste el webhook.
  getCallbackEvent(paymentRequestId) {
    return this.request('GET', `/api/v1/pos-operations/${encodeURIComponent(paymentRequestId)}/callback-event`);
  }

  // GET /api/v1/pos-operations?limit=n — https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/listar-operaciones/
  listOperations(limit = 1) {
    return this.request('GET', `/api/v1/pos-operations?limit=${limit}`);
  }
}

// Verifica un webhook: HMAC-SHA256 del body crudo con tu secreto, y que no tenga más de 5 minutos.
// https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/webhooks/#verificar-la-firma
export function verifyWebhook(rawBody, headers, secret, now = Date.now()) {
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
  const received = String(headers['x-vp-signature'] ?? '');
  const signatureOk =
    received.length === expected.length && crypto.timingSafeEqual(Buffer.from(received), Buffer.from(expected));
  if (!signatureOk) return { ok: false, reason: 'firma no coincide' };

  const age = Math.abs(now - Date.parse(headers['x-vp-timestamp']));
  if (!(age <= 5 * 60 * 1000)) return { ok: false, reason: 'timestamp fuera de los 5 minutos' };
  return { ok: true };
}
