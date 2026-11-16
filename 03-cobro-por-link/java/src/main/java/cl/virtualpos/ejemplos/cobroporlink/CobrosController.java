package cl.virtualpos.ejemplos.cobroporlink;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Guía: Cobrar por link (email, WhatsApp o SMS)
 * https://docs.virtualpos.cl/guias/cobro-por-link/
 */
@RestController
@SuppressWarnings("unchecked")
public class CobrosController {

	private static final Logger log = LoggerFactory.getLogger(CobrosController.class);
	private static final Set<String> FINAL = Set.of("pagado", "rechazado", "expirado", "cancelado", "anulado");
	private static final Set<String> RETRY = Set.of("rechazado", "expirado", "cancelado");
	private static final NumberFormat CLP = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL"));

	/** Un cobro de tu sistema y el Payment que lo respalda. */
	static class Charge {
		String chargeId;
		int amount;
		String description;
		String uuid;
		String link;
		String status;
		Map<String, Object> customer;
		final List<Map<String, String>> sends = new ArrayList<>();

		String lastChannel() {
			return sends.isEmpty() ? "EMAIL" : sends.get(sends.size() - 1).get("channel");
		}
	}

	private final VirtualposClient vp;
	private final String publicUrl;
	// En tu sistema esto sería tu base de datos de cobros.
	private final Map<String, Charge> charges = new LinkedHashMap<>();

	public CobrosController(VirtualposClient vp, @Value("${app.public-url}") String publicUrl) {
		this.vp = vp;
		this.publicUrl = publicUrl.replaceAll("/$", "");
	}

	/** 1. Panel del comercio: formulario para cobrar y listado de cobros. */
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public synchronized String panel() {
		List<Charge> list = new ArrayList<>(charges.values());
		java.util.Collections.reverse(list);
		String rows = list.stream().map(c -> {
			String sends = c.sends.stream()
					.map(s -> e(s.get("channel") + " " + s.get("receiver")) + (s.containsKey("error") ? " ✘ " + e(s.get("error")) : " ✔"))
					.collect(Collectors.joining("<br>"));
			String actions = ("pendiente".equals(c.status) ? form("/cobros/" + c.chargeId + "/enviar", "Reenviar link") : "")
					+ (RETRY.contains(c.status) ? form("/cobros/" + c.chargeId + "/nuevo", "Cobrar de nuevo") : "")
					+ form("/cobros/" + c.chargeId + "/estado", "Consultar estado");
			return "<tr><td><code>" + c.chargeId + "</code><br><a href=\"" + e(c.link) + "\" target=\"_blank\">link de pago</a></td>"
					+ "<td>$" + CLP.format(c.amount) + "</td><td><strong>" + e(c.status) + "</strong></td><td>" + sends + "</td><td>" + actions + "</td></tr>";
		}).collect(Collectors.joining());
		return page("Cobrar por link", """
				<form method="post" action="/cobros" style="display:grid;gap:.5rem">
				  <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
				  <label>Descripción <input name="description" value="Reserva de mesa" required></label>
				  <fieldset><legend>Cliente</legend>
				    <label>Nombre <input name="first_name" value="Ana" required></label>
				    <label>Apellido <input name="last_name" value="Pérez" required></label>
				    <label>RUT <input name="social_id" value="11111111-1" required></label>
				    <label>Email <input name="email" type="email" value="cliente@example.com" required></label>
				    <label>Teléfono <input name="phone" value="56912345678" pattern="569[0-9]{8}" required></label>
				  </fieldset>
				  <label>Enviar por <select name="channel">
				    <option value="EMAIL">Email</option><option value="WHATSAPP">WhatsApp</option><option value="SMS">SMS</option>
				  </select></label>
				  <button>Crear cobro y enviar link</button>
				</form>
				<h2>Cobros</h2>
				<table border="1" cellpadding="4" style="border-collapse:collapse;width:100%">
				  <tr><th>Cobro</th><th>Monto</th><th>Estado</th><th>Envíos</th><th></th></tr>""" + rows + "</table>");
	}

	/** 2. Crea el Payment y envía el link al cliente por el canal elegido. */
	@PostMapping("/cobros")
	public ResponseEntity<String> crear(@RequestParam int amount, @RequestParam String description,
			@RequestParam("first_name") String firstName, @RequestParam("last_name") String lastName,
			@RequestParam("social_id") String socialId, @RequestParam String email,
			@RequestParam String phone, @RequestParam String channel) {
		Map<String, Object> customer = Map.of(
				"email", email,
				"social_id", socialId,
				"first_name", firstName,
				"last_name", lastName,
				"phone", phone); // formato 569NNNNNNNN
		createCharge(amount, description, customer, channel);
		return backToPanel();
	}

	/** 3. Reenvía el link de un cobro pendiente (por ejemplo, si el cliente no lo encuentra). */
	@PostMapping("/cobros/{chargeId}/enviar")
	public ResponseEntity<String> reenviar(@PathVariable String chargeId) {
		Charge charge = find(chargeId);
		send(charge, charge.lastChannel());
		return backToPanel();
	}

	/** 4. Consulta el estado en la API (útil si el webhook no llegó). */
	@PostMapping("/cobros/{chargeId}/estado")
	public ResponseEntity<String> estado(@PathVariable String chargeId) {
		syncPayment(find(chargeId).uuid);
		return backToPanel();
	}

	/**
	 * 5. Cobrar de nuevo: un pago rechazado, expirado o cancelado ya no se puede pagar ni reenviar.
	 * Crea otro Payment con los mismos datos y envía el link nuevo.
	 */
	@PostMapping("/cobros/{chargeId}/nuevo")
	public ResponseEntity<String> nuevo(@PathVariable String chargeId) {
		Charge old = find(chargeId);
		createCharge(old.amount, old.description, old.customer, old.lastChannel());
		return backToPanel();
	}

	/** 6. Webhook: Virtualpos avisa que el pago cambió. Responde 200 de inmediato y consulta el resultado después. */
	@PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> webhookJson(@RequestBody Map<String, Object> body) {
		return webhook((String) body.get("uuid"));
	}

	@PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Void> webhookForm(@RequestParam(required = false) String uuid) {
		return webhook(uuid);
	}

	private ResponseEntity<Void> webhook(String uuid) {
		log.info("Webhook recibido para {}", uuid);
		if (uuid != null) {
			CompletableFuture.runAsync(() -> syncPayment(uuid))
					.exceptionally(err -> { log.error("Webhook: {}", err.getMessage()); return null; });
		}
		return ResponseEntity.ok().build();
	}

	/**
	 * 7. Retorno: el cliente pagó desde el link y Virtualpos lo trae de vuelta con el uuid por POST.
	 * Es tu cliente, no tu equipo: muéstrale una confirmación simple.
	 */
	@PostMapping(value = "/gracias", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
	public String gracias(@RequestParam String uuid) {
		Charge charge = syncPayment(uuid);
		boolean paid = "pagado".equals(charge.status);
		return page(paid ? "¡Gracias por tu pago!" : "El pago no se completó", paid
				? "<p>Recibimos tu pago de $" + CLP.format(charge.amount) + ".</p>"
				: "<p>No se realizó ningún cargo. Si quieres intentarlo de nuevo, pídenos un nuevo link de pago.</p>");
	}

	/** Crea el Payment de un cobro y envía el link. merchant_internal_code une el pago con tu cobro. */
	private Charge createCharge(int amount, String description, Map<String, Object> customer, String channel) {
		String chargeId = "COB-" + System.currentTimeMillis();
		Map<String, Object> body = new LinkedHashMap<>(customer);
		body.put("amount", amount);
		body.put("description", description);
		body.put("merchant_internal_code", chargeId);
		body.put("merchant_internal_channel", "cobro-por-link");
		body.put("return_url", publicUrl + "/gracias");
		body.put("callback_url", publicUrl + "/webhook");
		Map<String, Object> res = vp.createPayment(body);
		Map<String, Object> order = (Map<String, Object>) ((Map<String, Object>) res.get("payment")).get("order");

		Charge charge = new Charge();
		charge.chargeId = chargeId;
		charge.amount = amount;
		charge.description = description;
		charge.uuid = (String) order.get("uuid");
		charge.link = (String) res.get("url_redirect"); // abre el Web Checkout de este pago: guárdalo por si quieres compartirlo a mano
		charge.status = (String) order.get("status");
		charge.customer = customer;
		synchronized (this) {
			charges.put(chargeId, charge);
		}
		log.info("Payment {} creado para {}", charge.uuid, chargeId);
		send(charge, channel);
		return charge;
	}

	/** Envía el link con POST /v3/payment/{uuid}/send. Para WhatsApp y SMS el destinatario es el teléfono con +56. */
	private void send(Charge charge, String channel) {
		String receiver = "EMAIL".equals(channel) ? (String) charge.customer.get("email") : "+" + charge.customer.get("phone");
		Map<String, String> entry = new LinkedHashMap<>(Map.of("channel", channel, "receiver", receiver));
		try {
			Map<String, Object> result = vp.sendPayment(charge.uuid, channel, receiver);
			entry.put("at", Instant.now().toString());
			log.info("Link de {} enviado por {} a {} ({})", charge.chargeId, channel, receiver, result.get("send_status"));
		} catch (VirtualposException err) {
			// El cobro ya existe: si el envío falla, corrige el dato y reenvía. No crees otro Payment.
			entry.put("error", err.getHint() != null ? err.getHint() : err.getMessage());
			log.error("No se pudo enviar {}: {}", charge.chargeId, err.getMessage());
		}
		synchronized (this) {
			charge.sends.add(entry);
		}
	}

	/** Fuente de verdad: siempre consulta el Payment en la API. Es idempotente: webhook y retorno pueden llegar en cualquier orden o repetirse. */
	private synchronized Charge syncPayment(String uuid) {
		Map<String, Object> payment = (Map<String, Object>)
				((Map<String, Object>) vp.request(HttpMethod.GET, "/v3/payment/" + uuid, null).get("payment")).get("order");
		Charge charge = charges.get((String) payment.get("merchant_internal_code"));
		if (charge == null) {
			throw new IllegalStateException("El pago " + uuid + " no corresponde a un cobro de este sistema");
		}
		if (charge.status != null && FINAL.contains(charge.status)) {
			return charge; // ya procesado: no lo proceses dos veces
		}
		charge.status = (String) payment.get("status");
		if ("pagado".equals(charge.status)) {
			// Aquí registras el pago en tu sistema: emite la boleta, confirma la reserva…
			log.info("Cobro {} pagado (autorización {})", charge.chargeId, payment.get("auth_code"));
		}
		return charge;
	}

	private synchronized Charge find(String chargeId) {
		Charge charge = charges.get(chargeId);
		if (charge == null) {
			throw new IllegalArgumentException("Cobro no encontrado");
		}
		return charge;
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<String> error(RuntimeException err) {
		log.error(err.getMessage());
		String hint = err instanceof VirtualposException v ? v.getHint() : null;
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
				.body(page("Ocurrió un error", "<p><code>" + e(err.getMessage()) + "</code></p>"
						+ (hint != null ? "<p>" + e(hint) + "</p>" : "") + "<p><a href=\"/\">Volver</a></p>"));
	}

	private static ResponseEntity<String> backToPanel() {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "/").build();
	}

	private static String form(String action, String label) {
		return "<form method=\"post\" action=\"" + action + "\"><button>" + label + "</button></form>";
	}

	private static String e(String s) {
		return s == null ? "" : HtmlUtils.htmlEscape(s);
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + e(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1rem\"><h1>" + e(title) + "</h1>" + body + "</body>";
	}
}
