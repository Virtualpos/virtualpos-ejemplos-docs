package cl.virtualpos.ejemplos.pagounico;

import java.util.Locale;

/** Error de la API de Virtualpos, con una pista para los casos más frecuentes. */
public class VirtualposException extends RuntimeException {

	private final int httpStatus;
	private final String errorCode;
	private final String apiMessage;

	public VirtualposException(int httpStatus, String errorCode, String apiMessage) {
		super((errorCode != null ? errorCode : String.valueOf(httpStatus)) + ": " + apiMessage);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
		this.apiMessage = apiMessage;
	}

	public int getHttpStatus() { return httpStatus; }
	public String getErrorCode() { return errorCode; }
	public String getApiMessage() { return apiMessage; }
	public String getHint() { return hintFor(apiMessage); }

	/**
	 * Pistas para los errores más frecuentes que recibe soporte.
	 * Se busca por mensaje porque el mismo caso puede venir con distintos códigos según el endpoint.
	 */
	public static String hintFor(String message) {
		String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
		if (m.contains("missing required request parameters")) {
			return "Faltan los headers Authorization o Signature.";
		}
		if (m.contains("expirado") || m.contains("emitido en el futuro")) {
			return "La firma expiró. Este endpoint firma con la hora actual (ciat) y tolera 5 minutos: sincroniza el reloj de tu servidor (NTP).";
		}
		if (m.contains("no tiene permitido anular")) {
			return "Este pago no se puede anular por API: depende del tipo de pago (por ejemplo, cuotas sin interés) y de tu contrato. Revísalo con tu ejecutivo.";
		}
		if (m.contains("firma")) {
			return "Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {\"api_key\": \"<tu API KEY>\"} (para anular: {\"ciat\": <hora actual>, \"email\": \"...\"}).";
		}
		if (m.contains("no existe el comercio") || m.contains("no existe cuenta")) {
			return "Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?";
		}
		if (m.contains("contrato") || m.contains("no está habilitado") || m.contains("no esta habilitado")) {
			return "El producto no está activo para tu cuenta en este ambiente. Pídele a tu ejecutivo que lo habilite.";
		}
		return null;
	}
}
