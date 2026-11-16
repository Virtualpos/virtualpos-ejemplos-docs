package cl.virtualpos.ejemplos.suscripcionmensual;

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
 * Cliente mínimo de la API de Virtualpos para suscripciones: firmas JWT, requests y errores con pistas.
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

	/** Planes, suscripciones y cargos: JWT HS256 con tu API KEY en el payload, firmado con tu SECRET KEY. */
	public String signature() {
		return Jwts.builder().claim("api_key", apiKey).signWith(secretKey, Jwts.SIG.HS256).compact();
	}

	/** Pagar y suscribir: JWT HS256 con la hora actual (ciat, en segundos). */
	public String ciatSignature() {
		return Jwts.builder().claim("ciat", System.currentTimeMillis() / 1000).signWith(secretKey, Jwts.SIG.HS256).compact();
	}

	/** POST /v3/plan — https://docs.virtualpos.cl/suscripciones/plan/crear-un-plan/ */
	public Map<String, Object> createPlan(Map<String, Object> plan) {
		return request(HttpMethod.POST, "/v3/plan", plan);
	}

	/** POST /v3/pay-and-subscribe — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/iniciar-transaccion/ */
	public Map<String, Object> startPayAndSubscribe(Map<String, Object> data) {
		Map<String, Object> body = new HashMap<>(data);
		// Ambas URLs van en Base64.
		body.put("return_url", base64((String) data.get("return_url")));
		body.put("callback_url", base64((String) data.get("callback_url")));
		return request(HttpMethod.POST, "/v3/pay-and-subscribe", body, ciatSignature());
	}

	/** GET /v3/pay-and-subscribe/{id} — https://docs.virtualpos.cl/suscripciones/pagar-y-suscribir/obtener-estado/ */
	public Map<String, Object> getPayAndSubscribe(String subscriptionId) {
		return request(HttpMethod.GET, "/v3/pay-and-subscribe/" + subscriptionId, null, ciatSignature());
	}

	/** GET /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/recuperar-una-suscription/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getSubscription(String subscriptionId) {
		return (Map<String, Object>) request(HttpMethod.GET, "/v3/suscription/" + subscriptionId, null).get("suscription");
	}

	/** POST /v3/charge — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> scheduleCharge(String subscriptionId, String chargeDate, int amount, String description, String internalCode) {
		return (Map<String, Object>) request(HttpMethod.POST, "/v3/charge", Map.of(
				"suscription_id", subscriptionId,
				"charge_date", chargeDate,
				"amount", amount,
				"description", description,
				"internal_code", internalCode)).get("charge");
	}

	/** POST /v3/charge/online — https://docs.virtualpos.cl/suscripciones/charge/crear-un-charge-online/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> chargeNow(String subscriptionId, int amount, String description, String internalCode) {
		return (Map<String, Object>) request(HttpMethod.POST, "/v3/charge/online", Map.of(
				"suscription_id", subscriptionId,
				"amount", amount,
				"description", description,
				"internal_code", internalCode)).get("charge");
	}

	/** GET /v3/suscription/{id}/charges — https://docs.virtualpos.cl/suscripciones/charge/listar-charges/ */
	@SuppressWarnings("unchecked")
	public java.util.List<Map<String, Object>> listCharges(String subscriptionId) {
		return (java.util.List<Map<String, Object>>) request(HttpMethod.GET, "/v3/suscription/" + subscriptionId + "/charges", null).get("charges");
	}

	/** GET /v3/charge/{id}/retry — https://docs.virtualpos.cl/suscripciones/charge/reintentar-charge/ */
	public Map<String, Object> retryCharge(String chargeId) {
		return request(HttpMethod.GET, "/v3/charge/" + chargeId + "/retry", null);
	}

	/** PUT /v3/suscription/{id}/changecard — https://docs.virtualpos.cl/suscripciones/suscription/generar-link-de-cambio-de-tarjeta/ */
	public Map<String, Object> changeCardLink(String subscriptionId) {
		return request(HttpMethod.PUT, "/v3/suscription/" + subscriptionId + "/changecard", null);
	}

	/** DELETE /v3/suscription/{id} — https://docs.virtualpos.cl/suscripciones/suscription/cancelar-una-suscription/ */
	@SuppressWarnings("unchecked")
	public Map<String, Object> cancelSubscription(String subscriptionId) {
		return (Map<String, Object>) request(HttpMethod.DELETE, "/v3/suscription/" + subscriptionId, null).get("suscription");
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
			// Los errores llegan con HTTP 200: revisa también status y error en el cuerpo.
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
