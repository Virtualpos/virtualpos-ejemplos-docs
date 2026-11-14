package cl.virtualpos.ejemplos.pagounico;

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
 * Cliente mínimo de la API de Virtualpos: firma JWT, requests y errores con pistas.
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

	/** La firma es un JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY. */
	public String signature() {
		return Jwts.builder().claim("api_key", apiKey).signWith(secretKey, Jwts.SIG.HS256).compact();
	}

	/** Anular usa otra firma: JWT HS256 con la hora actual (ciat, en segundos) y un email. Tolera 5 minutos. */
	public String refundSignature(String email) {
		return Jwts.builder()
				.claim("ciat", System.currentTimeMillis() / 1000)
				.claim("email", email)
				.signWith(secretKey, Jwts.SIG.HS256)
				.compact();
	}

	/** POST /v3/payment/ — https://docs.virtualpos.cl/payments/crear-un-objeto-payment/ */
	public Map<String, Object> createPayment(Map<String, Object> payment) {
		Map<String, Object> body = new HashMap<>(payment);
		// Ambas URLs van en Base64 y deben enviarse juntas: si falta una, Virtualpos no guarda ninguna.
		body.put("return_url", base64((String) payment.get("return_url")));
		body.put("callback_url", base64((String) payment.get("callback_url")));
		return request(HttpMethod.POST, "/v3/payment/", body);
	}

	/** GET /v3/payment/{uuid} — https://docs.virtualpos.cl/payments/recuperar-un-objeto-payment/ */
	public Map<String, Object> getPayment(String uuid) {
		return request(HttpMethod.GET, "/v3/payment/" + uuid, null);
	}

	/** POST /v3/payment/{uuid}/refund — https://docs.virtualpos.cl/payments/anular-un-payment/ */
	public Map<String, Object> refundPayment(String uuid, int amount, String email) {
		return request(HttpMethod.POST, "/v3/payment/" + uuid + "/refund", Map.of("amount", amount), refundSignature(email));
	}

	public Map<String, Object> request(HttpMethod method, String path, Object body) {
		return request(method, path, body, signature());
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> request(HttpMethod method, String path, Object body, String signature) {
		try {
			var spec = http.method(method).uri(path)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Authorization", apiKey)
					.header("Signature", signature);
			if (body != null) {
				spec.body(body);
			}
			Map<String, Object> data = spec.retrieve().body(JSON);
			// Algunos errores llegan con HTTP 200: revisa también status y error en el cuerpo.
			if (data != null && ("NOK".equals(data.get("status")) || data.get("error") != null)) {
				Map<String, Object> error = (Map<String, Object>) data.get("error");
				throw new VirtualposException(200, error == null ? null : (String) error.get("error_code"),
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
