package cl.virtualpos.ejemplos.suscripcionmensual;

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
		if (m.contains("firma no válida")) {
			return "Pagar y suscribir se firma con la hora actual: payload {\"ciat\": <timestamp en segundos>}, no con {\"api_key\"}.";
		}
		if (m.contains("parámetros en la firma")) {
			return "Planes, suscripciones y cargos se firman con payload {\"api_key\": \"<tu API KEY>\"}, no con {\"ciat\"}.";
		}
		if (m.contains("firma")) {
			return "Firma rechazada. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.";
		}
		if (m.contains("no es de tipo monto_variable")) {
			return "Pagar y suscribir solo acepta planes de tipo MONTO_VARIABLE. Crea uno con java -jar target/*.jar crear-plan.";
		}
		if (m.contains("charge_date")) {
			return "La fecha del cargo (charge_date) debe tener formato AAAA-MM-DD y ser hoy o una fecha futura.";
		}
		if (m.contains("condiciones para realizar el reintento")) {
			return "Solo se puede reintentar un cargo en estado rechazado.";
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
