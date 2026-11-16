package cl.virtualpos.ejemplos.garantia;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
 * Cliente mínimo de Checkins de Virtualpos: firma JWT con ciat, requests y errores con pistas.
 * Docs: https://docs.virtualpos.cl/checkins/introduccion/
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

	/** Checkins firma con la hora actual: JWT HS256 con {"ciat": <segundos>}. Vale 5 minutos, así que se genera en cada request. */
	public String signature() {
		return Jwts.builder().claim("ciat", System.currentTimeMillis() / 1000).signWith(secretKey, Jwts.SIG.HS256).compact();
	}

	/** POST /v3/checkins — https://docs.virtualpos.cl/checkins/crear-checkin/ */
	public Map<String, Object> createCheckin(Map<String, Object> checkin) {
		Map<String, Object> body = new HashMap<>(checkin);
		// Ambas URLs van en Base64.
		body.put("callback_url", base64((String) checkin.get("callback_url")));
		body.put("return_url", base64((String) checkin.get("return_url")));
		return request(HttpMethod.POST, "/v3/checkins", body);
	}

	/** POST /v3/checkins/cof/{uuid_cli}/{card_reference_id} — cliente y tarjeta ya registrados, garantía inmediata y sin formulario. */
	public Map<String, Object> createCheckinWithSavedCard(String uuidCli, String cardReferenceId, Map<String, Object> checkin) {
		Map<String, Object> body = new HashMap<>(checkin);
		body.put("callback_url", base64((String) checkin.get("callback_url")));
		return request(HttpMethod.POST, "/v3/checkins/cof/" + uuidCli + "/" + cardReferenceId, body);
	}

	/** GET /v3/checkins/{uuid} — https://docs.virtualpos.cl/checkins/recuperar-checkin/ */
	public Map<String, Object> getCheckin(String uuid) {
		return request(HttpMethod.GET, "/v3/checkins/" + uuid, null);
	}

	/** POST /v3/checkins/authorize — garantía diferida. */
	public Map<String, Object> authorize(String uuid, int amount) {
		return request(HttpMethod.POST, "/v3/checkins/authorize", Map.of("uuid", uuid, "amount", amount));
	}

	/** POST /v3/checkins/incremental */
	public Map<String, Object> incremental(String uuid, int amount, String description) {
		return request(HttpMethod.POST, "/v3/checkins/incremental", Map.of("uuid", uuid, "amount", amount, "description", description));
	}

	/** POST /v3/checkins/checkout — cobra el monto final. */
	public Map<String, Object> checkout(String uuid, int finalAmount) {
		return request(HttpMethod.POST, "/v3/checkins/checkout", Map.of("uuid", uuid, "final_checkout_amount", finalAmount));
	}

	/** DELETE /v3/checkins/{uuid} — libera la garantía. */
	public Map<String, Object> cancel(String uuid) {
		return request(HttpMethod.DELETE, "/v3/checkins/" + uuid, null);
	}

	/** POST /v3/checkins/additional-charges — después del checkout. */
	public Map<String, Object> additionalCharge(String uuid, int amount, String reason) {
		return request(HttpMethod.POST, "/v3/checkins/additional-charges", Map.of("uuid", uuid, "amount", amount, "charge_reason", reason));
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> request(HttpMethod method, String path, Object body) {
		try {
			var spec = http.method(method).uri(path)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Authorization", apiKey)
					.header("Signature", signature());
			if (body != null) {
				spec.body(body);
			}
			Map<String, Object> data = spec.retrieve().body(JSON);
			// Los errores llegan casi siempre con HTTP 200: revisa también status y error en el cuerpo.
			if (data != null && ("NOK".equals(data.get("status")) || data.get("error") != null)) {
				Map<String, Object> error = (Map<String, Object>) data.get("error");
				int code = data.get("code") instanceof Number n ? n.intValue() : 200;
				throw new VirtualposException(code, error == null ? null : (String) error.get("error_code"),
						error == null ? "Respuesta NOK sin detalle" : (String) error.get("message"));
			}
			return data;
		} catch (RestClientResponseException e) {
			Map<String, Object> data = e.getResponseBodyAs(JSON);
			Map<String, Object> error = data == null ? null : (Map<String, Object>) data.get("error");
			String code = error == null ? null : (String) error.get("error_code");
			String message = error != null ? (String) error.get("message")
					: data != null && data.get("message") != null ? (String) data.get("message")
					: e.getStatusText();
			throw new VirtualposException(e.getStatusCode().value(), code, message);
		}
	}

	private static String base64(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
