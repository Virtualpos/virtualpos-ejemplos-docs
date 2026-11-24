package cl.virtualpos.ejemplos.suscripcionposdesdecrm;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Guía: Suscribir clientes en el POS desde tu CRM
 * https://docs.virtualpos.cl/guias/suscripcion-pos-desde-crm/
 */
@RestController
@ConditionalOnWebApplication // no se carga con java -jar ... verificar | crear-plan
public class CrmController {

	private static final Logger log = LoggerFactory.getLogger(CrmController.class);
	private static final long VIGENCIA_MS = Duration.ofMinutes(30).toMillis(); // el cliente tiene 30 minutos para presentar su tarjeta
	private static final Pattern JSON_UUID = Pattern.compile("\"uuid\"\\s*:\\s*\"([^\"]+)\"");
	private static final List<String> CUSTOMER_FIELDS = List.of("first_name", "last_name", "social_id", "email", "phone_number");

	/** Una suscripción enviada al POS desde tu CRM. */
	static final class Subscription {
		final String subscriptionId;
		final Map<String, String> customer;
		final String posSerial;
		final long sentAt;
		volatile String status;
		volatile String card;
		volatile boolean finalCheck; // ya se hizo la última consulta después de vencer

		Subscription(String subscriptionId, Map<String, String> customer, String posSerial, String status) {
			this.subscriptionId = subscriptionId;
			this.customer = customer;
			this.posSerial = posSerial;
			this.status = status;
			this.sentAt = System.currentTimeMillis();
		}

		boolean isPending() {
			return "SUSCRIBIENDO".equals(status) && !isExpired();
		}

		boolean isExpired() {
			return System.currentTimeMillis() - sentAt >= VIGENCIA_MS;
		}

		/** Pendiente en el POS, o vencida pero aún sin la última consulta. */
		boolean needsCheck() {
			return isPending() || ("SUSCRIBIENDO".equals(status) && !finalCheck);
		}
	}

	// En tu CRM esto sería tu base de datos: una fila por suscripción enviada al POS.
	private final Map<String, Subscription> subscriptions = Collections.synchronizedMap(new LinkedHashMap<>());
	// POS de tu comercio: "serial:nombre,serial:nombre"
	private final Map<String, String> pos = new LinkedHashMap<>();
	private final VirtualposClient vp;
	private final String planId;
	private final String publicUrl;

	public CrmController(VirtualposClient vp, @Value("${virtualpos.plan-id}") String planId,
			@Value("${app.pos-terminales}") String posTerminales, @Value("${app.public-url}") String publicUrl) {
		if (planId.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_PLAN_ID en tu archivo .env (créalo con java -jar target/*.jar crear-plan)");
		}
		for (String p : posTerminales.split(",")) {
			if (p.isBlank()) {
				continue;
			}
			String[] parts = p.split(":", 2);
			String serial = parts[0].trim();
			String name = parts.length > 1 ? parts[1].trim() : "";
			pos.put(serial, name.isEmpty() ? serial : name);
		}
		if (pos.isEmpty()) {
			throw new IllegalStateException("Configura POS_TERMINALES en tu archivo .env");
		}
		this.vp = vp;
		this.planId = planId;
		this.publicUrl = publicUrl.replaceAll("/$", "");
		log.info("CRM de ejemplo (URL pública: {})", this.publicUrl);
	}

	// 1. Ficha del cliente: el vendedor completa los datos y elige el POS donde está el cliente.
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String home() {
		// Respaldo del webhook: mientras haya envíos pendientes, consulta la API cada vez que se recarga la página.
		CompletableFuture.allOf(all().stream().filter(Subscription::needsCheck)
				.map(s -> CompletableFuture.runAsync(() -> syncSubscription(s.subscriptionId))
						.exceptionally(err -> { log.warn("Consulta de {}: {}", s.subscriptionId, err.getMessage()); return null; }))
				.toArray(CompletableFuture[]::new)).join();

		StringBuilder options = new StringBuilder();
		pos.forEach((serial, name) -> options.append("<option value=\"").append(esc(serial)).append("\">")
				.append(esc(name)).append(" (").append(esc(serial)).append(")</option>"));
		StringBuilder rows = new StringBuilder();
		for (Subscription s : all().reversed()) {
			rows.append("<tr>\n      <td>").append(esc(s.customer.get("first_name"))).append(' ').append(esc(s.customer.get("last_name"))).append("</td>")
					.append("\n      <td>").append(esc(pos.getOrDefault(s.posSerial, s.posSerial))).append("</td>")
					.append("\n      <td>").append(label(s)).append("</td>")
					.append("\n      <td>").append(s.isPending()
							? "<form method=\"post\" action=\"/suscripciones/" + esc(s.subscriptionId) + "/cancelar\"><button>Cancelar envío</button></form>"
							: "")
					.append("</td>\n    </tr>");
		}
		return page("CRM de ejemplo", """
				<form method="post" action="/suscripciones" style="display:grid;gap:.5rem;max-width:24rem">
				  <input name="first_name" placeholder="Nombre" required>
				  <input name="last_name" placeholder="Apellido" required>
				  <input name="social_id" placeholder="RUT (11111111-1)" required>
				  <input name="email" type="email" placeholder="Email" required>
				  <input name="phone_number" placeholder="Teléfono (56912345678)" required>
				  <select name="pos">%s</select>
				  <button>Suscribir en el POS</button>
				</form>""".formatted(options)
				+ (rows.isEmpty() ? "" : "<h2>Suscripciones</h2><table cellpadding=\"6\"><tr><th>Cliente</th><th>POS</th><th>Estado</th><th></th></tr>" + rows + "</table>"
						+ "\n<script>setTimeout(() => location.reload(), 5000)</script>"));
	}

	// 2. Crea la suscripción y la envía al POS elegido.
	@PostMapping(value = "/suscripciones", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> suscribir(@RequestParam Map<String, String> form) {
		String posSerial = form.getOrDefault("pos", "");
		if (!pos.containsKey(posSerial)) {
			return html(HttpStatus.BAD_REQUEST, page("POS desconocido", "<p><a href=\"/\">Volver</a></p>"));
		}

		// Un POS atiende una suscripción a la vez: no envíes otra mientras haya una pendiente en ese equipo.
		Subscription busy = all().stream().filter(s -> s.posSerial.equals(posSerial) && s.isPending()).findFirst().orElse(null);
		if (busy != null) {
			return html(HttpStatus.CONFLICT, page("El POS está ocupado", "<p>" + esc(pos.get(posSerial)) + " tiene una suscripción pendiente de "
					+ esc(busy.customer.get("first_name")) + ". Espera a que termine o cancela el envío.</p><p><a href=\"/\">Volver</a></p>"));
		}

		Map<String, String> customer = new LinkedHashMap<>();
		CUSTOMER_FIELDS.forEach(f -> customer.put(f, form.getOrDefault(f, "")));
		Subscription sub = null;
		try {
			Map<String, Object> data = new LinkedHashMap<>(customer);
			data.put("plan_id", planId);
			data.put("channel", "CRM");
			data.put("return_url", publicUrl);
			data.put("callback_url", publicUrl + "/webhook");
			Map<String, Object> suscription = vp.createSubscription(data);
			sub = new Subscription((String) suscription.get("id"), customer, posSerial, (String) suscription.get("status"));
			subscriptions.put(sub.subscriptionId, sub);
			log.info("Suscripción {} creada ({})", sub.subscriptionId, sub.status);

			vp.sendToPos(posSerial, sub.subscriptionId);
			log.info("Suscripción {} enviada al POS {}", sub.subscriptionId, posSerial);
			return redirect("/");
		} catch (RuntimeException err) {
			// Si la suscripción se creó pero no llegó al POS, no la reutilices: la próxima vez crea una nueva.
			if (sub != null) {
				sub.status = "NO_ENVIADA";
			}
			log.error("No se pudo enviar al POS: {}", err.getMessage());
			return html(HttpStatus.BAD_GATEWAY, page("No se pudo enviar al POS", errorHtml(err)));
		}
	}

	// 3. Webhook: Virtualpos avisa que la inscripción terminó, con el uuid de la suscripción.
	//    El body es JSON {"uuid": "subsc_..."}, pero llega con Content-Type "application/json, application/x-www-form-urlencoded",
	//    que Spring rechaza con 415 si usas @RequestBody o consumes: lee el body directamente de la request.
	@PostMapping("/webhook")
	public ResponseEntity<Void> webhook(HttpServletRequest request) throws IOException {
		String subscriptionId = readUuid(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		if (subscriptionId != null) {
			// Responde de inmediato; la consulta a la API va después.
			CompletableFuture.runAsync(() -> syncSubscription(subscriptionId))
					.exceptionally(err -> { log.error("Webhook {}: {}", subscriptionId, err.getMessage()); return null; });
		}
		return ResponseEntity.ok().build();
	}

	// 4. Cancela el envío si el cliente se fue o se eligió el POS equivocado.
	@PostMapping(value = "/suscripciones/{id}/cancelar", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> cancelar(@PathVariable String id) {
		Subscription sub = subscriptions.get(id);
		if (sub == null) {
			return html(HttpStatus.NOT_FOUND, page("Suscripción no encontrada", "<p><a href=\"/\">Volver</a></p>"));
		}
		try {
			vp.cancelSendToPos(sub.posSerial, sub.subscriptionId, "crm", "Cancelado desde el CRM");
			sub.status = "ENVIO_CANCELADO";
			log.info("Envío de {} al POS {} cancelado", sub.subscriptionId, sub.posSerial);
			return redirect("/");
		} catch (RuntimeException err) {
			log.error("No se pudo cancelar el envío: {}", err.getMessage());
			return html(HttpStatus.BAD_GATEWAY, page("No se pudo cancelar el envío", errorHtml(err)));
		}
	}

	/**
	 * Fuente de verdad: siempre consulta la API. Es idempotente:
	 * el webhook puede repetirse o llegar después de la consulta.
	 */
	@SuppressWarnings("unchecked")
	private void syncSubscription(String subscriptionId) {
		Subscription sub = subscriptions.get(subscriptionId);
		if (sub == null) {
			return; // no es una suscripción de este CRM
		}
		Map<String, Object> s = vp.getSubscription(subscriptionId);
		synchronized (sub) {
			if (sub.isExpired()) {
				sub.finalCheck = true; // una última consulta después de vencer
			}
			if (List.of("ENVIO_CANCELADO", "NO_ENVIADA").contains(sub.status) && "SUSCRIBIENDO".equals(s.get("status"))) {
				return;
			}
			String previous = sub.status;
			sub.status = (String) s.get("status");
			if ("ACTIVA".equals(sub.status) && !"ACTIVA".equals(previous)) {
				// Aquí marcas al cliente como suscrito en tu CRM. Solo una vez, aunque el aviso llegue repetido.
				Map<String, Object> card = (Map<String, Object>) s.get("payment_method");
				sub.card = card == null ? null : card.get("brand") + " ****" + card.get("last4CardDigit");
				log.info("Suscripción {} activa con {}", subscriptionId, sub.card != null ? sub.card : "tarjeta inscrita");
			}
		}
	}

	/** Copia de las suscripciones, en el orden en que se crearon. */
	private List<Subscription> all() {
		synchronized (subscriptions) {
			return new ArrayList<>(subscriptions.values());
		}
	}

	private static String label(Subscription s) {
		return switch (s.status == null ? "" : s.status) {
			case "ACTIVA" -> "✔ Activa" + (s.card != null ? " (" + esc(s.card) + ")" : "");
			case "SUSCRIPCION_FALLIDA" -> "✘ Falló: crea una nueva suscripción";
			case "ENVIO_CANCELADO" -> "Envío cancelado";
			case "NO_ENVIADA" -> "✘ No llegó al POS";
			case "SUSCRIBIENDO" -> s.isPending() ? "⏳ Esperando la tarjeta en el POS" : "✘ Venció: crea una nueva suscripción";
			default -> esc(s.status);
		};
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

	private static String errorHtml(Exception err) {
		String hint = err instanceof VirtualposException vpe ? vpe.getHint() : null;
		return "<p><code>" + esc(err.getMessage()) + "</code></p>" + (hint != null ? "<p>" + esc(hint) + "</p>" : "") + "<p><a href=\"/\">Volver</a></p>";
	}

	private static ResponseEntity<String> html(HttpStatus status, String body) {
		return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
	}

	private static ResponseEntity<String> redirect(String location) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location).build();
	}

	private static String esc(String s) {
		return HtmlUtils.htmlEscape(s == null ? "" : s);
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + esc(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem\"><h1>"
				+ esc(title) + "</h1>" + body + "</body>";
	}
}
