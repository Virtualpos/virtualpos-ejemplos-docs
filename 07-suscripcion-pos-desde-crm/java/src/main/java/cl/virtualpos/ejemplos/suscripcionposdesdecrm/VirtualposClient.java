package cl.virtualpos.ejemplos.suscripcionposdesdecrm;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente mínimo de la API de Virtualpos para suscripciones presenciales: firma JWT, requests y errores con pistas.
 * Docs: https://docs.virtualpos.cl/general/autenticacion-y-firma/
 */
@Component
public class VirtualposClient {

	private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

	private final String apiUrl;
	private final String apiKey;
	private final SecretKey secretKey;
	private final RestClient http;

	public VirtualposClient(
			@Value("${virtualpos.api-url}") String apiUrl,
			@Value("${virtualpos.api-key}") String apiKey,
			@Value("${virtualpos.secret-key}") String secretKey) {
		if (apiUrl.isBlank() || apiKey.isBlank() || secretKey.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_API_URL, VIRTUALPOS_API_KEY y VIRTUALPOS_SECRET_KEY en tu archivo .env");
		}
		this.apiUrl = apiUrl.replaceAll("/$", "");
		this.apiKey = apiKey;
		this.secretKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
		this.http = RestClient.builder().baseUrl(this.apiUrl).build();
	}

	public String getApiUrl() { return apiUrl; }

	/** JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY. */
	public String signature() {
		return Jwts.builder().claim("api_key", apiKey).signWith(secretKey, Jwts.SIG.HS256).compact();
	}

	/** POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> createPlan(Map<String, Object> plan) {
		return (Map<String, Object>) request(HttpMethod.POST, "/v3/plan", plan).get("plan");
	}

	/** POST /v3/suscription — https://docs.virtualpos.cl/suscripciones/suscription/crear-una-suscription/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> createSubscription(Map<String, Object> data) {
		Map<String, Object> body = new LinkedHashMap<>(data);
		// Ambas URLs van en Base64.
		body.put("return_url", base64((String) data.get("return_url")));
		body.put("callback_url", base64((String) data.get("callback_url")));
		return (Map<String, Object>) request(HttpMethod.POST, "/v3/suscription", body).get("suscription");
	}

	/** POST /v3/suscription/push-to-terminal — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/enviar-suscripcion-al-pos/ */
	public Map<String, Object> sendToPos(String posSerial, String subscriptionId) {
		return request(HttpMethod.POST, "/v3/suscription/push-to-terminal", Map.of(
				"terminal_serial_number", posSerial,
				"suscription_uuid", subscriptionId));
	}

	/** POST /v3/suscription/push-to-terminal/cancel — https://docs.virtualpos.cl/pos-cloud/suscripciones-presenciales/cancelar-envio-al-pos/ */
	public Map<String, Object> cancelSendToPos(String posSerial, String subscriptionId, String forcedBy, String reason) {
		return request(HttpMethod.POST, "/v3/suscription/push-to-terminal/cancel", Map.of(
				"terminal_serial_number", posSerial,
				"suscription_uuid", subscriptionId,
				"forced_by", forcedBy,
				"reason", reason));
	}

	/** GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getSubscription(String subscriptionId) {
		return (Map<String, Object>) request(HttpMethod.GET, "/v3/suscription/{id}", null, subscriptionId).get("suscription");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> request(HttpMethod method, String path, Object body, Object... uriVariables) {
		try {
			var spec = http.method(method).uri(path, uriVariables)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Authorization", apiKey)
					.header("Signature", signature());
			if (body != null) {
				spec.body(body);
			}
			Map<String, Object> data = spec.retrieve().body(JSON);
			// Algunos errores llegan con HTTP 200: revisa también status y error en el cuerpo.
			if (data != null && ("NOK".equals(data.get("status")) || data.get("error") != null)) {
				Map<String, Object> error = data.get("error") instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
				throw new VirtualposException(200, error == null ? null : (String) error.get("error_code"),
						error != null ? (String) error.get("message")
								: data.get("message") != null ? String.valueOf(data.get("message")) : "Respuesta NOK sin detalle");
			}
			return data == null ? Map.of() : data;
		} catch (RestClientResponseException e) {
			Map<String, Object> data;
			try {
				data = e.getResponseBodyAs(JSON);
			} catch (RuntimeException notJson) {
				data = null;
			}
			Map<String, Object> error = data != null && data.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
			String code = error == null ? null : (String) error.get("error_code");
			String message = error != null ? (String) error.get("message")
					: data != null && data.get("message") != null ? String.valueOf(data.get("message"))
					: e.getStatusText();
			throw new VirtualposException(e.getStatusCode().value(), code, message);
		}
	}

	private static String base64(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
