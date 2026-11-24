package cl.virtualpos.ejemplos.suscripcionposdesdecrm;

import java.util.List;
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
	public String getHint() { return hintFor(errorCode, apiMessage); }

	/** Pistas para los errores más frecuentes al enviar suscripciones al POS. */
	public static String hintFor(String errorCode, String message) {
		String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
		String code = errorCode == null ? "" : errorCode;
		if (m.contains("missing required request parameters")) {
			return "Faltan los headers Authorization o Signature.";
		}
		if (m.contains("firma")) {
			return "Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY, algoritmo HS256 y payload {\"api_key\": \"<tu API KEY>\"}.";
		}
		if (m.contains("no existe el comercio") || m.contains("no existe cuenta")) {
			return "Tu API KEY no existe en este ambiente. Revisa que la URL y las llaves sean del mismo ambiente.";
		}
		if (m.contains("sin contrato pat")) {
			return "Tu cuenta no tiene Suscripciones activo en este ambiente. Pídele a tu ejecutivo que lo habilite.";
		}
		return switch (code) {
			case "E-PTT-002" -> "Solo se puede enviar una suscripción recién creada (SUSCRIBIENDO). Si falló o venció, crea una nueva.";
			case "E-PTT-003" -> "El plan no está habilitado para el POS: créalo con show_in_terminal \"T\" (java -jar target/*.jar crear-plan).";
			case "E-PTT-005" -> "La suscripción ya se envió a otro POS. Crea una nueva para enviarla a este.";
			case "E-PTT-006" -> "Ese POS no está asignado a tu comercio en este ambiente. Revisa el serial.";
			case "E-PTT-011" -> "El plan pide dirección de despacho: esos planes no se pueden suscribir en el POS.";
			case "E-PTT-012" -> "No hay un envío previo de esta suscripción a ese POS.";
			default -> List.of("E-PTT-008", "E-PTT-009", "E-PTT-013", "E-PTT-014").contains(code)
					? "No se pudo llegar al POS. Revisa que esté encendido y con internet, y vuelve a intentar."
					: null;
		};
	}
}
