package cl.virtualpos.ejemplos.cobroporlink;

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
		if (m.contains("no cumple con formato para el channel")) {
			return "El destinatario no corresponde al canal: EMAIL necesita un email; WHATSAPP y SMS, un teléfono con +56, por ejemplo +56912345678.";
		}
		if (m.contains("channel no permitido")) {
			return "Canal no reconocido o no habilitado para tu cuenta. Usa EMAIL, WHATSAPP o SMS; si es uno de ellos, pídele a tu ejecutivo que lo habilite.";
		}
		if (m.contains("parámetro channel") || m.contains("parametro channel")) {
			return "Falta el canal: envía channel con EMAIL, WHATSAPP o SMS.";
		}
		if (m.contains("parámetro receiver") || m.contains("parametro receiver")) {
			return "Falta el destinatario (receiver).";
		}
		if (m.contains("no existe la orden")) {
			return "No hay un pago pendiente con ese uuid: ya se pagó o terminó (al enviar, la API responde así), o el uuid es de otro ambiente. Consulta su estado.";
		}
		if (m.contains("firma")) {
			return "Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {\"api_key\": \"<tu API KEY>\"}.";
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
