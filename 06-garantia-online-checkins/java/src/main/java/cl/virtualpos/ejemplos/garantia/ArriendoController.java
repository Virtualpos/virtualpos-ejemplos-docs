package cl.virtualpos.ejemplos.garantia;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guía: Tomar y cobrar una garantía online con Checkins
 * https://docs.virtualpos.cl/guias/garantia-online-checkins/
 */
@RestController
@SuppressWarnings("unchecked")
public class ArriendoController {

	private static final Logger log = LoggerFactory.getLogger(ArriendoController.class);

	/** En tu sistema esto sería tu base de datos de reservas. */
	static final class Reserva {
		String reservaId;
		String uuid;
		String status;
		int guarantee;
		int captured;
		String uuidCli;
		String cardReferenceId;
		String last4;
		boolean confirmed;
	}

	private final Map<String, Reserva> reservas = Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, String> byUuid = new ConcurrentHashMap<>(); // uuid del check-in -> reservaId
	private final VirtualposClient vp;
	private final JsonMapper json;
	private final String publicUrl;

	public ArriendoController(VirtualposClient vp, JsonMapper json, @Value("${app.public-url}") String publicUrl) {
		this.vp = vp;
		this.json = json;
		this.publicUrl = publicUrl.replaceAll("/$", "");
	}

	/** 1. Página de tu sistema: nueva reserva y listado. */
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String home() {
		StringBuilder rows = new StringBuilder();
		List<Reserva> list = new ArrayList<>(reservas.values());
		Collections.reverse(list);
		for (Reserva r : list) {
			rows.append("<tr><td><code>").append(r.reservaId).append("</code></td><td>").append(r.status)
					.append("</td><td>").append(clp(r.guarantee)).append("</td><td>")
					.append(r.last4 != null ? "•••• " + r.last4 : "").append("</td><td><a href=\"/reservas/")
					.append(r.reservaId).append("\">Ver</a></td></tr>");
		}
		return page("Arriendo de autos de ejemplo", """
				<form method="post" action="/reservas">
				  <p><label>Garantía <input name="guarantee" type="number" value="50000" min="1"></label></p>
				  <p><label><input type="radio" name="timing" value="immediate" checked> Tomarla ahora (inmediata)</label><br>
				     <label><input type="radio" name="timing" value="deferred"> Solo registrar la tarjeta (diferida)</label></p>
				  <button>Reservar</button>
				</form>""" + (rows.isEmpty() ? "" : "<h2>Reservas</h2><table cellpadding=\"6\"><tr><th>Reserva</th><th>Estado</th>"
				+ "<th>Garantía</th><th>Tarjeta</th><th></th></tr>" + rows + "</table>"));
	}

	/** 2. Crea el check-in y lleva al cliente al formulario de Virtualpos para ingresar su tarjeta. */
	@PostMapping("/reservas")
	public ResponseEntity<Void> reservar(@RequestParam(defaultValue = "50000") int guarantee,
			@RequestParam(defaultValue = "immediate") String timing) {
		String reservaId = "RES-" + System.currentTimeMillis();
		Map<String, Object> checkin = (Map<String, Object>) vp.createCheckin(Map.ofEntries(
				Map.entry("currency", "CLP"),
				Map.entry("description", "Arriendo " + reservaId),
				Map.entry("merchant_internal_code", reservaId),
				Map.entry("date_checkin_commerce", LocalDate.now().plusDays(1).toString()),
				Map.entry("date_checkout_commerce", LocalDate.now().plusDays(4).toString()),
				Map.entry("client", Map.of(
						"first_name", "Ana",
						"last_name", "Pérez",
						"email", "cliente@example.com",
						"social_id_type", "RUT",
						"social_id", "11111111-1",
						"type", "PERSONA")),
				Map.entry("payment_plan", Map.of(
						"guarantee", Map.of("timing", "deferred".equals(timing) ? "deferred" : "immediate", "amount", guarantee),
						"prepayment", Map.of("amount", 0))),
				Map.entry("callback_url", publicUrl + "/webhook"),
				Map.entry("return_url", publicUrl + "/retorno"),
				Map.entry("metadata", Map.of("reserva", reservaId)))).get("checkin");
		Reserva r = new Reserva();
		r.reservaId = reservaId;
		r.uuid = (String) checkin.get("uuid");
		r.status = (String) checkin.get("status");
		r.guarantee = guarantee;
		r.uuidCli = (String) ((Map<String, Object>) checkin.get("client")).get("uuid_cli");
		save(r);
		log.info("Check-in {} creado para {}", r.uuid, reservaId);
		return redirect((String) checkin.get("hosted_url"));
	}

	/**
	 * 3. Webhook: Virtualpos avisa que el check-in cambió. Responde 200 de inmediato y consulta el estado después.
	 * Se lee el cuerpo crudo porque el Content-Type que envía Virtualpos no siempre es un tipo válido.
	 */
	@PostMapping("/webhook")
	public ResponseEntity<Void> webhook(HttpServletRequest request) throws IOException {
		String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		log.info("Webhook recibido: {}", body);
		String uuid = webhookUuid(body);
		if (uuid != null) {
			CompletableFuture.runAsync(() -> {
				try {
					syncCheckin(uuid);
				} catch (RuntimeException e) {
					log.error("Webhook: {}", e.getMessage());
				}
			});
		}
		return ResponseEntity.ok().build();
	}

	/** 4. Retorno: el cliente vuelve desde el formulario con el uuid del check-in por POST. */
	@PostMapping("/retorno")
	public ResponseEntity<Void> retorno(@RequestParam String uuid) {
		Reserva r = syncCheckin(uuid);
		return redirect("/reservas/" + r.reservaId);
	}

	/** Detalle de la reserva con las operaciones disponibles según su estado. */
	@GetMapping(value = "/reservas/{id}", produces = MediaType.TEXT_HTML_VALUE)
	public String reserva(@PathVariable String id) {
		Reserva r = find(id);
		syncCheckin(r.uuid);
		String ops = switch (r.status) {
			case "card_captured" -> op(r, "autorizar", "Tomar la garantía", r.guarantee);
			case "authorized" -> op(r, "incremental", "Aumentar la garantía en", 20000)
					+ op(r, "checkout", "Cobrar el monto final", r.guarantee)
					+ op(r, "cancelar", "Liberar la garantía (sin cobro)", null);
			case "captured" -> op(r, "cargo-adicional", "Cargo adicional (multa, daño)", 8000);
			default -> "";
		};
		String nueva = r.cardReferenceId != null ? op(r, "nueva", "Nueva reserva con la misma tarjeta, garantía", 30000) : "";
		return page("Reserva " + r.reservaId, "<p>Estado del check-in: <strong>" + r.status + "</strong></p>"
				+ "<p>Garantía: " + clp(r.guarantee) + (r.captured > 0 ? " · Cobrado: " + clp(r.captured) : "")
				+ (r.last4 != null ? " · Tarjeta •••• " + r.last4 : "") + "</p>" + ops + nueva + "<p><a href=\"/\">Volver</a></p>");
	}

	/** 5 a 7. Operaciones sobre el check-in. */
	@PostMapping("/reservas/{id}/{operacion}")
	public ResponseEntity<Void> operar(@PathVariable String id, @PathVariable String operacion,
			@RequestParam(defaultValue = "0") int amount) {
		Reserva r = find(id);
		String back = "/reservas/" + r.reservaId;
		switch (operacion) {
			// 5a. Garantía diferida: tómala cuando la necesites (por ejemplo, el día del retiro).
			case "autorizar" -> vp.authorize(r.uuid, amount);
			// 5b. Aumenta la garantía tomada (por ejemplo, el cliente extiende el arriendo).
			case "incremental" -> vp.incremental(r.uuid, amount, "Extensión del arriendo");
			// 6a. Checkout: cobra el monto final. El resto de la garantía se libera.
			case "checkout" -> vp.checkout(r.uuid, amount);
			// 6b. O libera la garantía completa sin cobrar.
			case "cancelar" -> vp.cancel(r.uuid);
			// 7. Cargo adicional después del checkout (multas, daños).
			case "cargo-adicional" -> vp.additionalCharge(r.uuid, amount, "Multa de tránsito");
			// 8. Cliente que vuelve: nueva garantía con su tarjeta guardada, sin formulario.
			case "nueva" -> back = "/reservas/" + nuevaConTarjetaGuardada(r, amount);
			default -> throw new IllegalArgumentException("Operación desconocida: " + operacion);
		}
		log.info("{} OK en {}", operacion, r.reservaId);
		return redirect(back);
	}

	private String nuevaConTarjetaGuardada(Reserva anterior, int guarantee) {
		String reservaId = "RES-" + System.currentTimeMillis();
		Map<String, Object> checkin = (Map<String, Object>) vp.createCheckinWithSavedCard(anterior.uuidCli, anterior.cardReferenceId, Map.of(
				"currency", "CLP",
				"description", "Arriendo " + reservaId,
				"merchant_internal_code", reservaId,
				"date_checkin_commerce", LocalDate.now().plusDays(1).toString(),
				"date_checkout_commerce", LocalDate.now().plusDays(4).toString(),
				"payment_plan", Map.of("guarantee", Map.of("timing", "immediate", "amount", guarantee)),
				"callback_url", publicUrl + "/webhook")).get("checkin");
		Reserva r = new Reserva();
		r.reservaId = reservaId;
		r.uuid = (String) checkin.get("uuid");
		r.status = (String) checkin.get("status");
		r.guarantee = guarantee;
		r.uuidCli = anterior.uuidCli;
		save(r);
		log.info("Check-in {} con tarjeta guardada para {}: {}", r.uuid, reservaId, r.status);
		return reservaId;
	}

	/** Fuente de verdad: siempre consulta el check-in en la API. Webhook y retorno pueden llegar en cualquier orden o repetirse. */
	private synchronized Reserva syncCheckin(String uuid) {
		Map<String, Object> checkin = (Map<String, Object>) vp.getCheckin(uuid).get("checkin");
		String reservaId = byUuid.getOrDefault(uuid, (String) checkin.get("merchant_internal_code"));
		Reserva r = reservas.computeIfAbsent(reservaId, k -> {
			Reserva nueva = new Reserva();
			nueva.reservaId = k;
			nueva.uuid = uuid;
			return nueva;
		});
		r.status = (String) checkin.get("status");
		r.guarantee = ((Number) checkin.get("guarantee_amount")).intValue();
		r.captured = 0;
		for (Map<String, Object> t : (List<Map<String, Object>>) checkin.getOrDefault("transactions", List.of())) {
			if ("checkout".equals(t.get("transaction_type"))) {
				r.captured += ((Number) t.get("amount")).intValue();
			}
		}
		if (checkin.get("card") instanceof Map<?, ?> card) {
			r.cardReferenceId = (String) card.get("card_reference_id"); // guárdalo para cobrarle al cliente sin formulario la próxima vez
			r.last4 = (String) card.get("last4");
		}
		if ("authorized".equals(r.status) && !r.confirmed) {
			r.confirmed = true; // una sola vez, aunque el webhook se repita
			// Aquí confirmas la reserva en tu sistema: correo al cliente, asignación del auto…
			log.info("Reserva {} confirmada: garantía de {} tomada", reservaId, clp(r.guarantee));
		}
		save(r);
		return r;
	}

	/** El webhook trae el uuid del check-in. Acepta JSON o formulario. */
	private String webhookUuid(String body) {
		try {
			Object uuid = json.readValue(body, Map.class).get("uuid");
			return uuid == null ? null : uuid.toString();
		} catch (RuntimeException notJson) {
			for (String pair : body.split("&")) {
				String[] kv = pair.split("=", 2);
				if (kv.length == 2 && kv[0].equals("uuid")) {
					return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
				}
			}
			return null;
		}
	}

	@ExceptionHandler(VirtualposException.class)
	public ResponseEntity<String> error(VirtualposException err) {
		log.error("La operación no se completó: {}", err.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
				.body(page("La operación no se completó", "<p><code>" + escape(err.getMessage()) + "</code></p>"
						+ (err.getHint() != null ? "<p>" + escape(err.getHint()) + "</p>" : "") + "<p><a href=\"/\">Volver</a></p>"));
	}

	private Reserva find(String id) {
		Reserva r = reservas.get(id);
		if (r == null) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada");
		}
		return r;
	}

	private void save(Reserva r) {
		reservas.put(r.reservaId, r);
		byUuid.put(r.uuid, r.reservaId);
	}

	private static String op(Reserva r, String path, String label, Integer value) {
		return "<form method=\"post\" action=\"/reservas/" + r.reservaId + "/" + path + "\" style=\"margin:.5rem 0\">"
				+ (value != null ? "<input name=\"amount\" type=\"number\" value=\"" + value + "\" min=\"1\"> " : "")
				+ "<button>" + label + "</button></form>";
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
	}

	private static String clp(int n) {
		return "$" + String.format(java.util.Locale.forLanguageTag("es-CL"), "%,d", n);
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + title + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:40rem;margin:3rem auto;padding:0 1rem\"><h1>" + title + "</h1>" + body + "</body>";
	}
}
