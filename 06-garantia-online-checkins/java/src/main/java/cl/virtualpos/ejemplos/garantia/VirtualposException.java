package cl.virtualpos.ejemplos.garantia;

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

	/** Pistas para los errores más frecuentes al integrar Checkins. */
	public static String hintFor(String errorCode, String message) {
		if (errorCode != null) {
			switch (errorCode) {
				case "E-021":
					return "Firma no válida. En Checkins el payload del JWT es {\"ciat\": <hora actual en segundos>}, no {\"api_key\": ...} como en Payments.";
				case "E-025":
					return "La firma no coincide. Firma con la SECRET KEY del mismo ambiente que tu API KEY y algoritmo HS256.";
				case "E-022", "E-023":
					return "La firma usa la hora actual (ciat) y tolera 5 minutos. Genera una firma nueva en cada request y sincroniza el reloj de tu servidor (NTP).";
				case "E-002":
					return "Tu API KEY no existe en este ambiente. ¿Estás usando llaves de Producción contra Sandbox, o al revés?";
				case "E-CHK-305", "E-CHK-306":
					return "El check-in no está en un estado que permita esta operación. Consulta su estado con GET /v3/checkins/{uuid}.";
				case "E-CHK-308":
					return "El monto supera lo permitido. El checkout no puede exceder la garantía autorizada: usa un incremental antes o un cargo adicional después.";
				default:
			}
		}
		String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
		return m.contains("missing required request parameters") ? "Faltan los headers Authorization o Signature." : null;
	}
}
