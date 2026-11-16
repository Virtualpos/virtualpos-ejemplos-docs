package cl.virtualpos.ejemplos.garantiapos;

/** Error de la API de POS Cloud to Cloud, con una pista para los casos más frecuentes. */
public class PosException extends RuntimeException {

	private final int httpStatus;
	private final String hint;

	public PosException(int httpStatus, String message) {
		super("HTTP " + httpStatus + ": " + message);
		this.httpStatus = httpStatus;
		this.hint = hintFor(httpStatus);
	}

	public int getHttpStatus() { return httpStatus; }

	public String getHint() { return hint; }

	/** true si no sabes si la operación llegó al POS (timeout o error de Virtualpos). */
	public boolean isUnknownResult() { return httpStatus == 0 || httpStatus >= 500; }

	static String hintFor(int httpStatus) {
		if (httpStatus == 401) {
			return "API key inválida. Revisa VIRTUALPOS_POS_API_KEY y que sea del mismo ambiente que VIRTUALPOS_POS_API_URL (integración o producción). No es la API KEY del portal de comercio.";
		}
		if (httpStatus == 403) {
			return "El POS no está habilitado para POS Cloud to Cloud en tu comercio. Revisa VIRTUALPOS_POS_SERIAL o pídele a tu ejecutivo que lo habilite.";
		}
		if (httpStatus == 404) {
			return "No existe esa operación para tu comercio. Revisa el paymentRequestId.";
		}
		if (httpStatus == 0 || httpStatus >= 500) {
			return "No sabes si la operación llegó al POS. Reintenta con el mismo externalPaymentId, nunca con uno nuevo: si ya se aprobó, Virtualpos no la vuelve a enviar.";
		}
		return null;
	}
}
