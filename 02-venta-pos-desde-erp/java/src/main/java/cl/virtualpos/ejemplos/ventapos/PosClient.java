package cl.virtualpos.ejemplos.ventapos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente mínimo de POS Cloud to Cloud (Ventas con tarjeta): requests, errores con pistas y firma de webhooks.
 * Docs: https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/introduccion/
 */
@Component
public class PosClient {

	public static final Set<String> FINAL = Set.of("APPROVED", "REJECTED", "REVERSED", "FAILED", "EXPIRED", "CANCELLED");
	private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

	private final String apiUrl;
	private final RestClient http;

	public PosClient(@Value("${virtualpos.pos.api-url}") String apiUrl, @Value("${virtualpos.pos.api-key}") String apiKey) {
		if (apiUrl.isBlank() || apiKey.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_POS_API_URL y VIRTUALPOS_POS_API_KEY en tu archivo .env");
		}
		this.apiUrl = apiUrl.replaceAll("/$", "");
		SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
		timeouts.setConnectTimeout(Duration.ofSeconds(5));
		timeouts.setReadTimeout(Duration.ofSeconds(15));
		this.http = RestClient.builder()
				.baseUrl(this.apiUrl)
				.requestFactory(timeouts)
				.defaultHeader("X-Api-Key", apiKey)
				.build();
	}

	public String getApiUrl() { return apiUrl; }

	/**
	 * POST /api/v1/pos-operations — https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/crear-una-venta/
	 * externalPaymentId es tu ID de la venta: repetirlo nunca cobra dos veces.
	 */
	public Map<String, Object> createSale(String posSerial, String externalPaymentId, long amount, long tipAmount, String callbackUrl) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("posSerial", posSerial);
		body.put("operationType", "SALE");
		body.put("externalPaymentId", externalPaymentId);
		body.put("amount", amount);
		body.put("tipAmount", tipAmount);
		body.put("currency", "CLP");
		body.put("callbackUrl", callbackUrl);
		return request(HttpMethod.POST, "/api/v1/pos-operations", body);
	}

	/** GET /api/v1/pos-operations/{paymentRequestId} — estado actual, sin el detalle de la tarjeta. */
	public Map<String, Object> getOperation(String paymentRequestId) {
		return request(HttpMethod.GET, "/api/v1/pos-operations/" + paymentRequestId, null);
	}

	/** GET .../callback-event — el webhook armado con el estado actual, incluido resultPayload. */
	public Map<String, Object> getCallbackEvent(String paymentRequestId) {
		return request(HttpMethod.GET, "/api/v1/pos-operations/" + paymentRequestId + "/callback-event", null);
	}

	/** GET /api/v1/pos-operations?limit=n */
	public Map<String, Object> listOperations(int limit) {
		return request(HttpMethod.GET, "/api/v1/pos-operations?limit=" + limit, null);
	}

	private Map<String, Object> request(HttpMethod method, String path, Object body) {
		try {
			RestClient.RequestBodySpec req = http.method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
			if (body != null) {
				req.body(body);
			}
			return req.retrieve().body(JSON);
		} catch (RestClientResponseException err) {
			Map<String, Object> data = null;
			try {
				data = err.getResponseBodyAs(JSON); // 401 no trae body
			} catch (RuntimeException ignored) {
			}
			Object message = data != null ? data.get("message") : null;
			throw new PosException(err.getStatusCode().value(), message != null ? message.toString() : err.getStatusText());
		} catch (ResourceAccessException err) {
			throw new PosException(0, "Sin respuesta de Virtualpos: " + err.getMessage());
		}
	}

	/**
	 * Verifica un webhook: HMAC-SHA256 del body crudo con tu secreto, y que no tenga más de 5 minutos.
	 * Devuelve null si es válido, o el motivo del rechazo.
	 * https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/webhooks/#verificar-la-firma
	 */
	public static String verifyWebhook(byte[] rawBody, String signature, String timestamp, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
			if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
				return "firma no coincide";
			}
			Duration age = Duration.between(Instant.parse(timestamp), Instant.now()).abs();
			return age.compareTo(Duration.ofMinutes(5)) > 0 ? "timestamp fuera de los 5 minutos" : null;
		} catch (DateTimeParseException | NullPointerException err) {
			return "timestamp inválido";
		} catch (Exception err) {
			throw new IllegalStateException(err);
		}
	}
}
