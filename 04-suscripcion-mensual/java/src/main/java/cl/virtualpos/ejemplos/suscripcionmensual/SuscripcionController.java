package cl.virtualpos.ejemplos.suscripcionmensual;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/**
 * Guía: Cobrar una suscripción mensual
 * https://docs.virtualpos.cl/guias/suscripcion-mensual/
 */
@RestController
public class SuscripcionController {

	private static final Logger log = LoggerFactory.getLogger(SuscripcionController.class);
	private static final int MONTHLY_AMOUNT = 9990;
	private static final Pattern JSON_UUID = Pattern.compile("\"uuid\"\\s*:\\s*\"([^\"]+)\"");

	/** Una suscripción de tu sistema. */
	static final class Subscription {
		final String subscriptionId;
		final String customerId;
		volatile String status;

		Subscription(String subscriptionId, String customerId, String status) {
			this.subscriptionId = subscriptionId;
			this.customerId = customerId;
			this.status = status;
		}
	}

	// En tu sistema esto sería tu base de datos de clientes y suscripciones.
	private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
	private final VirtualposClient vp;
	private final String planId;
	private final String publicUrl;

	public SuscripcionController(VirtualposClient vp, @Value("${virtualpos.plan-id}") String planId,
			@Value("${app.public-url}") String publicUrl) {
		if (planId.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_PLAN_ID en tu archivo .env (créalo con java -jar target/*.jar crear-plan)");
		}
		this.vp = vp;
		this.planId = planId;
		this.publicUrl = publicUrl.replaceAll("/$", "");
	}

	// 1. Página de tu sitio con el botón para suscribirse.
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String home() {
		StringBuilder rows = new StringBuilder();
		subscriptions.values().forEach(s -> rows.append("<li><a href=\"/suscripciones/").append(esc(s.subscriptionId)).append("\">")
				.append(esc(s.customerId)).append("</a>: ").append(esc(s.status)).append("</li>"));
		return page("Suscripción de ejemplo", """
				<p>Plan mensual por <strong>$9.990</strong>. El primer mes se paga al suscribirse.</p>
				<form method="post" action="/suscribir"><button>Suscribirme</button></form>"""
				+ (rows.isEmpty() ? "" : "<h2>Suscripciones</h2><ul>" + rows + "</ul>"));
	}

	// 2. Inicia Pagar y suscribir y redirige al cliente al formulario de Virtualpos.
	@PostMapping("/suscribir")
	@SuppressWarnings("unchecked")
	public ResponseEntity<String> suscribir() {
		String customerId = "CLI-" + System.currentTimeMillis();
		Map<String, Object> res = vp.startPayAndSubscribe(Map.ofEntries(
				Map.entry("email", customerId.toLowerCase() + "@example.com"), // en tu sistema, el email de tu cliente
				Map.entry("social_id", "11111111-1"),
				Map.entry("first_name", "Ana"),
				Map.entry("last_name", "Pérez"),
				Map.entry("phone_number", "56912345678"),
				Map.entry("plan_id", planId),
				Map.entry("service_id", customerId),
				Map.entry("channel", "WEB"),
				Map.entry("activation_amount", MONTHLY_AMOUNT),
				Map.entry("payment_description", "Primer mes"),
				Map.entry("merchant_internal_code", customerId + "-" + YearMonth.now()),
				Map.entry("return_url", publicUrl + "/retorno"),
				Map.entry("callback_url", publicUrl + "/webhook")));
		Map<String, Object> sub = (Map<String, Object>) res.get("subscription");
		String id = (String) sub.get("id");
		subscriptions.put(id, new Subscription(id, customerId, (String) sub.get("status")));
		log.info("Suscripción {} iniciada para {}", id, customerId);
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, (String) res.get("url_redirect")).build();
	}

	// 3. Webhook: Virtualpos avisa que terminó la inscripción. Responde 200 de inmediato y consulta el resultado después.
	//    El body es JSON {"uuid": "subsc_..."}, pero llega con Content-Type "application/json, application/x-www-form-urlencoded",
	//    que Spring rechaza con 415 si usas @RequestBody o consumes: lee el body directamente de la request.
	@PostMapping("/webhook")
	public ResponseEntity<Void> webhook(HttpServletRequest request) throws IOException {
		String id = readUuid(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		if (id != null) {
			CompletableFuture.runAsync(() -> syncSubscription(id))
					.exceptionally(err -> { log.error("Webhook: {}", err.getMessage()); return null; });
		}
		return ResponseEntity.ok().build();
	}

	// 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
	@PostMapping(value = "/retorno", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
	public String retorno(@RequestParam(required = false) String uuid) {
		Subscription sub = syncSubscription(uuid);
		boolean active = "ACTIVA".equals(sub.status);
		return page(active ? "¡Suscripción activa!" : "La suscripción no se completó",
				"<p>Cliente <code>" + esc(sub.customerId) + "</code>: <strong>" + esc(sub.status) + "</strong></p><p>"
						+ (active
								? "<a href=\"/suscripciones/" + esc(sub.subscriptionId) + "\">Ver suscripción y cargos</a>"
								: "<a href=\"/\">Intentar nuevamente</a>") + "</p>");
	}

	// 5. Detalle: estado, tarjeta y cargos, siempre leídos desde la API.
	//    charge_program trae los cargos en todos los estados, también después de cancelar.
	@GetMapping(value = "/suscripciones/{id}", produces = MediaType.TEXT_HTML_VALUE)
	@SuppressWarnings("unchecked")
	public String detalle(@PathVariable String id) {
		Map<String, Object> s = vp.getSubscription(id);
		Map<String, Object> card = (Map<String, Object>) s.get("payment_method");
		List<Map<String, Object>> charges = (List<Map<String, Object>>) s.getOrDefault("charge_program", List.of());
		StringBuilder rows = new StringBuilder();
		for (Map<String, Object> c : charges) {
			String retry = "rechazado".equals(c.get("status"))
					? "<form method=\"post\" action=\"/cargos/" + esc((String) c.get("id")) + "/reintentar\"><input type=\"hidden\" name=\"subscriptionId\" value=\"" + esc(id) + "\"><button>Reintentar</button></form>"
					: "";
			rows.append("<tr><td>").append(esc(String.valueOf(c.get("charge_date")))).append("</td><td>").append(esc(String.valueOf(c.get("description"))))
					.append("</td><td>$").append(c.get("amount")).append("</td><td>").append(esc(String.valueOf(c.get("status")))).append("</td><td>").append(retry).append("</td></tr>");
		}
		String base = "/suscripciones/" + esc(id);
		return page("Suscripción " + s.get("status"),
				"<p>" + (card != null ? "Tarjeta " + esc(String.valueOf(card.get("brand"))) + " terminada en " + esc(String.valueOf(card.get("last4CardDigit"))) : "Sin tarjeta") + "</p>"
						+ "<h2>Cargos</h2>" + (rows.isEmpty() ? "<p>Aún no hay cargos.</p>" : "<table>" + rows + "</table>")
						+ ("ACTIVA".equals(s.get("status")) ? """
								<form method="post" action="%1$s/programar"><button>Programar el cargo del próximo mes</button></form>
								<form method="post" action="%1$s/cobrar-ahora"><button>Cobrar el mes ahora</button></form>
								<form method="post" action="%1$s/cambiar-tarjeta"><button>Cambiar tarjeta</button></form>
								<form method="post" action="%1$s/cancelar"><button>Cancelar suscripción</button></form>""".formatted(base) : "")
						+ "<p><a href=\"/\">Volver</a></p>");
	}

	// 6a. Programa el cargo del próximo mes: Virtualpos lo cobra en charge_date.
	//     La API no rechaza un internal_code repetido: revisa tú que el mes no esté programado, o lo cobrarás dos veces.
	@PostMapping("/suscripciones/{id}/programar")
	public ResponseEntity<Void> programar(@PathVariable String id) {
		LocalDate next = LocalDate.now().plusMonths(1); // si el día no existe, usa el último del mes
		String internalCode = vp.getSubscription(id).get("service_id") + "-" + YearMonth.from(next);
		boolean already = vp.listCharges(id).stream()
				.anyMatch(c -> internalCode.equals(c.get("internal_code")) && !"cancelado".equals(c.get("status")));
		if (already) {
			log.info("El cargo {} ya estaba programado", internalCode);
		} else {
			Map<String, Object> charge = vp.scheduleCharge(id, next.toString(), MONTHLY_AMOUNT, "Mensualidad " + YearMonth.from(next), internalCode);
			log.info("Cargo {} programado para {} ({})", charge.get("id"), charge.get("charge_date"), charge.get("status"));
		}
		return redirect("/suscripciones/" + id);
	}

	// 6b. Cobra el mes ahora: la respuesta trae el resultado del cobro.
	//     Úsalo para cobros fuera de calendario. internal_code es obligatorio en este endpoint.
	@PostMapping("/suscripciones/{id}/cobrar-ahora")
	public ResponseEntity<Void> cobrarAhora(@PathVariable String id) {
		String internalCode = vp.getSubscription(id).get("service_id") + "-" + YearMonth.now() + "-" + System.currentTimeMillis();
		Map<String, Object> charge = vp.chargeNow(id, MONTHLY_AMOUNT, "Mensualidad " + YearMonth.now(), internalCode);
		log.info("Cargo {}: {}", charge.get("id"), charge.get("status"));
		return redirect("/suscripciones/" + id);
	}

	// 7. Reintenta un cargo rechazado.
	@PostMapping("/cargos/{chargeId}/reintentar")
	public ResponseEntity<Void> reintentar(@PathVariable String chargeId, @RequestParam String subscriptionId) {
		log.info("{}", vp.retryCharge(chargeId).get("message"));
		return redirect("/suscripciones/" + subscriptionId);
	}

	// 8. Cambio de tarjeta: genera el link y envía al cliente a actualizar su tarjeta.
	@PostMapping("/suscripciones/{id}/cambiar-tarjeta")
	public ResponseEntity<Void> cambiarTarjeta(@PathVariable String id) {
		Map<String, Object> res = vp.changeCardLink(id);
		log.info("Link de cambio de tarjeta válido hasta {}", res.get("change_card_expiration_date"));
		return redirect((String) res.get("change_card_url"));
	}

	// 9. Cancela la suscripción: Virtualpos deja de cobrar los cargos pendientes.
	@PostMapping("/suscripciones/{id}/cancelar")
	public ResponseEntity<Void> cancelar(@PathVariable String id) {
		String status = (String) vp.cancelSubscription(id).get("status");
		Subscription sub = subscriptions.get(id);
		if (sub != null) {
			sub.status = status;
		}
		log.info("Suscripción {}: {}", id, status);
		return redirect("/suscripciones/" + id);
	}

	/**
	 * Fuente de verdad: siempre consulta la API. Es idempotente:
	 * webhook y retorno pueden llegar en cualquier orden o repetirse.
	 */
	@SuppressWarnings("unchecked")
	private synchronized Subscription syncSubscription(String subscriptionId) {
		if (subscriptionId == null || subscriptionId.isBlank()) {
			throw new IllegalArgumentException("No llegó el id de la suscripción");
		}
		Map<String, Object> s = vp.getSubscription(subscriptionId);
		Subscription sub = subscriptions.computeIfAbsent(subscriptionId,
				id -> new Subscription(id, (String) s.get("service_id"), null));
		String previous = sub.status;
		sub.status = (String) s.get("status");
		if ("ACTIVA".equals(sub.status) && !"ACTIVA".equals(previous)) {
			// Aquí activas el servicio de tu cliente. Solo una vez, aunque el aviso llegue repetido.
			Map<String, Object> card = (Map<String, Object>) s.get("payment_method");
			log.info("Suscripción {} activa con {} {}", subscriptionId,
					card == null ? "" : card.get("brand"), card == null ? "" : card.get("last4CardDigit"));
		}
		return sub;
	}

	/** El uuid llega como JSON o como formulario. */
	private static String readUuid(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		Matcher json = JSON_UUID.matcher(body);
		if (json.find()) {
			return json.group(1);
		}
		for (String pair : body.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv.length == 2 && kv[0].equals("uuid")) {
				return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<String> error(Exception err) {
		log.error("Error: {}", err.getMessage());
		String hint = err instanceof VirtualposException vpe ? vpe.getHint() : null;
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
				.body(page("Ocurrió un error", "<p><code>" + esc(err.getMessage()) + "</code></p>"
						+ (hint != null ? "<p>" + esc(hint) + "</p>" : "") + "<p><a href=\"/\">Volver</a></p>"));
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location).build();
	}

	private static String esc(String s) {
		return HtmlUtils.htmlEscape(s == null ? "" : s);
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + esc(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:40rem;margin:3rem auto;padding:0 1rem\"><h1>"
				+ esc(title) + "</h1>" + body + "</body>";
	}
}
