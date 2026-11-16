package cl.virtualpos.ejemplos.garantiapos;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guía: Garantía con el POS (preautorización y cierre)
 * https://docs.virtualpos.cl/guias/garantia-pos/
 */
@RestController
public class RecepcionController {

	private static final Logger log = LoggerFactory.getLogger(RecepcionController.class);
	private static final NumberFormat CLP = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL"));

	/** Una operación en el POS: el check-in, el check-out o una impresión. */
	static final class Operation {
		final String kind; // CHECKIN, CHECKOUT o PRINT
		final String externalPaymentId;
		final long amount;
		String paymentRequestId;
		String status = "CREATED";
		Integer attemptNumber;
		Map<?, ?> detail; // resultPayload: el del check-in se usa para el checkout
		long lastSync;

		Operation(String kind, String externalPaymentId, long amount) {
			this.kind = kind;
			this.externalPaymentId = externalPaymentId;
			this.amount = amount;
		}
	}

	/**
	 * En tu sistema esto sería tu base de datos de reservas. Cada reserva tiene hasta tres tipos de operación,
	 * cada una con su propio externalPaymentId: RESERVA-123, RESERVA-123-CHECKOUT y RESERVA-123-COMPROBANTE-n.
	 */
	static final class Reserva {
		final String reservaId;
		final long guarantee;
		final Operation checkin;
		Operation checkout;
		final List<Operation> prints = Collections.synchronizedList(new ArrayList<>());

		Reserva(String reservaId, long guarantee) {
			this.reservaId = reservaId;
			this.guarantee = guarantee;
			this.checkin = new Operation("CHECKIN", reservaId, guarantee);
		}

		List<Operation> operations() {
			List<Operation> ops = new ArrayList<>();
			ops.add(checkin);
			if (checkout != null) {
				ops.add(checkout);
			}
			ops.addAll(prints);
			return ops;
		}
	}

	private final Map<String, Reserva> reservas = Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, Operation> operations = new ConcurrentHashMap<>(); // externalPaymentId -> operación (para los webhooks)
	private final Set<String> seenEvents = ConcurrentHashMap.newKeySet(); // eventId de webhooks ya procesados
	private final PosClient pos;
	private final JsonMapper json;
	private final String posSerial;
	private final String webhookSecret;
	private final String callbackUrl;

	public RecepcionController(PosClient pos, JsonMapper json,
			@Value("${virtualpos.pos.serial}") String posSerial,
			@Value("${virtualpos.pos.webhook-secret}") String webhookSecret,
			@Value("${app.public-url}") String publicUrl) {
		if (posSerial.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_POS_SERIAL en tu archivo .env");
		}
		if (webhookSecret.isBlank()) {
			log.warn("Sin VIRTUALPOS_WEBHOOK_SECRET no podrás verificar webhooks: la recepción consultará el estado a la API.");
		}
		this.pos = pos;
		this.json = json;
		this.posSerial = posSerial;
		this.webhookSecret = webhookSecret;
		this.callbackUrl = publicUrl.replaceAll("/$", "") + "/webhooks/virtualpos";
	}

	/** 1. Recepción: ingresa el monto de la garantía y preautoriza en el POS. */
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String recepcion() {
		List<Reserva> list;
		synchronized (reservas) {
			list = new ArrayList<>(reservas.values());
		}
		Collections.reverse(list);
		StringBuilder rows = new StringBuilder();
		for (Reserva r : list) {
			rows.append("<tr><td><a href=\"/reservas/").append(r.reservaId).append("\">").append(r.reservaId).append("</a></td><td>")
					.append(clp(r.guarantee)).append("</td><td>").append(r.checkin.status).append("</td><td>")
					.append(r.checkout == null ? "—" : clp(r.checkout.amount) + " " + r.checkout.status).append("</td></tr>");
		}
		return page("Recepción de ejemplo", """
				<form method="post" action="/checkin">
				  <label>Garantía <input name="amount" type="number" min="1" value="50000" required></label>
				  <button>Preautorizar en el POS %s</button>
				</form>""".formatted(escape(posSerial))
				+ (rows.isEmpty() ? "" : "<h2>Reservas</h2><table><tr><th>Reserva</th><th>Garantía</th><th>Check-in</th><th>Check-out</th></tr>" + rows + "</table>"));
	}

	/** 2. Check-in: guarda la reserva y envía la preautorización (CHECKIN). El ID de la reserva es el externalPaymentId. */
	@PostMapping(value = "/checkin", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> checkin(@RequestParam long amount) {
		Reserva reserva = new Reserva("RESERVA-" + System.currentTimeMillis(), amount);
		reservas.put(reserva.reservaId, reserva); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
		operations.put(reserva.checkin.externalPaymentId, reserva.checkin);
		return send(reserva, reserva.checkin);
	}

	/** 3. Check-out: cierra la preautorización con el monto final. Requiere el resultPayload del check-in aprobado. */
	@PostMapping(value = "/reservas/{reservaId}/checkout", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> checkout(@PathVariable String reservaId, @RequestParam long amount) {
		Reserva reserva = reservas.get(reservaId);
		if (reserva == null) {
			return ResponseEntity.notFound().build();
		}
		if (!"APPROVED".equals(reserva.checkin.status) || reserva.checkin.detail == null
				|| reserva.checkin.detail.get("posTransactionId") == null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(page("Aún no puedes cerrar",
					"<p>El check-in debe estar aprobado y con su posTransactionId.</p>" + back(reserva)));
		}
		synchronized (reserva) {
			if (reserva.checkout != null) {
				return redirect(reserva); // ya se envió: usa Reintentar
			}
			reserva.checkout = new Operation("CHECKOUT", reserva.reservaId + "-CHECKOUT", amount);
		}
		operations.put(reserva.checkout.externalPaymentId, reserva.checkout);
		return send(reserva, reserva.checkout);
	}

	/** 4. Reintentar el check-in o el check-out con el MISMO externalPaymentId. */
	@PostMapping(value = "/reservas/{reservaId}/reintentar/{kind}", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> reintentar(@PathVariable String reservaId, @PathVariable String kind) {
		Reserva reserva = reservas.get(reservaId);
		Operation op = reserva == null ? null : switch (kind) {
			case "checkin" -> reserva.checkin;
			case "checkout" -> reserva.checkout;
			default -> null;
		};
		if (op == null) {
			return ResponseEntity.notFound().build();
		}
		// Nunca reenvíes una operación en curso: el huésped podría estar presentando su tarjeta.
		// Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
		boolean unknown = op.paymentRequestId == null;
		boolean retryable = PosClient.FINAL.contains(op.status) && !"APPROVED".equals(op.status);
		if (!unknown && !retryable) {
			return redirect(reserva);
		}
		return send(reserva, op);
	}

	/** 5. Imprime el comprobante de la estadía. Cada impresión usa un externalPaymentId nuevo. */
	@PostMapping(value = "/reservas/{reservaId}/imprimir", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> imprimir(@PathVariable String reservaId) {
		Reserva reserva = reservas.get(reservaId);
		if (reserva == null) {
			return ResponseEntity.notFound().build();
		}
		Operation op;
		synchronized (reserva.prints) {
			op = new Operation("PRINT", reserva.reservaId + "-COMPROBANTE-" + (reserva.prints.size() + 1), 0);
			reserva.prints.add(op);
		}
		operations.put(op.externalPaymentId, op);
		return send(reserva, op);
	}

	/** Pantalla de la reserva: el navegador consulta el estado cada 2 segundos. */
	@GetMapping(value = "/reservas/{reservaId}", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> reserva(@PathVariable String reservaId) {
		Reserva reserva = reservas.get(reservaId);
		if (reserva == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Reserva no encontrada", "<p><a href=\"/\">Volver</a></p>"));
		}
		return ResponseEntity.ok(page("Reserva " + reserva.reservaId, "<p>Garantía: <strong>" + clp(reserva.guarantee) + "</strong></p>"
				+ """
				<div id="estado">Consultando…</div>
				<script>
				  async function revisar() {
				    const r = await (await fetch(location.pathname + '/estado')).json();
				    document.getElementById('estado').innerHTML = r.html;
				    if (r.waiting) setTimeout(revisar, 2000);
				  }
				  revisar();
				</script>"""));
	}

	/** Estado de la reserva. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos por operación). */
	@GetMapping("/reservas/{reservaId}/estado")
	public ResponseEntity<Map<String, Object>> estado(@PathVariable String reservaId) {
		Reserva reserva = reservas.get(reservaId);
		if (reserva == null) {
			return ResponseEntity.notFound().build();
		}
		boolean waiting = false;
		for (Operation op : reserva.operations()) {
			boolean sync;
			synchronized (op) {
				sync = !PosClient.FINAL.contains(op.status) && op.paymentRequestId != null && System.currentTimeMillis() - op.lastSync > 3000;
				if (sync) {
					op.lastSync = System.currentTimeMillis();
				}
			}
			if (sync) {
				try {
					applyStatus(op, pos.getOperation(op.paymentRequestId));
				} catch (PosException err) {
					log.error("Consulta de {}: {}", op.externalPaymentId, err.getMessage());
				}
			}
			// El checkout necesita el resultPayload del check-in. Si no llegó el webhook, pídelo a callback-event.
			if ("APPROVED".equals(op.status) && !"PRINT".equals(op.kind) && op.detail == null) {
				try {
					if (pos.getCallbackEvent(op.paymentRequestId).get("resultPayload") instanceof Map<?, ?> result) {
						op.detail = result;
					}
				} catch (PosException err) {
					log.error("Detalle de {}: {}", op.externalPaymentId, err.getMessage());
				}
			}
			waiting |= !PosClient.FINAL.contains(op.status);
		}
		Map<String, Object> body = new HashMap<>();
		body.put("html", reservaHtml(reserva));
		body.put("waiting", waiting);
		return ResponseEntity.ok(body);
	}

	/** 6. Webhook: verifica la firma sobre el body crudo, procesa una sola vez y responde de inmediato. */
	@PostMapping("/webhooks/virtualpos")
	public ResponseEntity<Void> webhook(@RequestBody byte[] body,
			@RequestHeader(value = "X-VP-Signature", required = false) String signature,
			@RequestHeader(value = "X-VP-Timestamp", required = false) String timestamp) {
		String problem = webhookSecret.isBlank() ? "sin secreto" : PosClient.verifyWebhook(body, signature, timestamp, webhookSecret);
		if (problem != null) {
			log.warn("Webhook rechazado: {}", problem);
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> event = json.readValue(body, Map.class);
		if (seenEvents.add((String) event.get("eventId"))) { // false: reintento de un webhook ya procesado
			Operation op = operations.get((String) event.get("externalPaymentId"));
			if (op == null) {
				log.warn("Webhook de una operación desconocida: {}", event.get("externalPaymentId"));
			} else {
				applyStatus(op, event);
				if ("APPROVED".equals(event.get("status")) && event.get("resultPayload") instanceof Map<?, ?> result) {
					op.detail = result;
				}
			}
		}
		return ResponseEntity.noContent().build();
	}

	private ResponseEntity<String> send(Reserva reserva, Operation op) {
		try {
			Map<String, Object> result = switch (op.kind) {
				case "CHECKIN" -> pos.createCheckin(posSerial, op.externalPaymentId, op.amount, callbackUrl);
				// El checkout lleva el resultPayload del check-in aprobado.
				case "CHECKOUT" -> pos.createCheckout(posSerial, op.externalPaymentId, op.amount, reserva.checkin.detail, callbackUrl);
				default -> pos.print(posSerial, op.externalPaymentId, receiptLines(reserva), callbackUrl);
			};
			synchronized (op) {
				op.paymentRequestId = (String) result.get("paymentRequestId");
				op.attemptNumber = (Integer) result.get("attemptNumber");
				op.status = (String) result.get("status");
				op.lastSync = System.currentTimeMillis();
			}
			log.info("{} ({}) enviada al POS: {}{}", op.externalPaymentId, op.kind, op.status,
					op.attemptNumber != null ? ", intento " + op.attemptNumber : ""); // 201 con FAILED no trae attemptNumber
			if ("FAILED".equals(op.status)) {
				log.warn("201 con FAILED: no llegó al POS. ¿Está encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso?");
			}
			return redirect(reserva);
		} catch (PosException err) {
			// Timeout o 500: no sabes si la operación llegó al POS. Reintenta con el MISMO externalPaymentId, nunca con uno nuevo.
			String retry = err.isUnknownResult() && !"PRINT".equals(op.kind)
					? "<form method=\"post\" action=\"/reservas/" + reserva.reservaId + "/reintentar/" + op.kind.toLowerCase() + "\"><button>Reintentar</button></form>"
					: "";
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(page("No se pudo enviar al POS",
					"<p><code>" + escape(err.getMessage()) + "</code></p>"
							+ (err.getHint() != null ? "<p>" + escape(err.getHint()) + "</p>" : "")
							+ retry + back(reserva)));
		}
	}

	/** Aplica un estado recibido por webhook o por consulta. Idempotente: ignora intentos anteriores y no cambia una operación que ya terminó. */
	private void applyStatus(Operation op, Map<String, Object> update) {
		Integer attempt = (Integer) update.get("attemptNumber");
		String status = (String) update.get("status");
		synchronized (op) {
			if (attempt != null && op.attemptNumber != null && attempt < op.attemptNumber) {
				return;
			}
			if (PosClient.FINAL.contains(op.status) && Objects.equals(attempt, op.attemptNumber)) {
				return;
			}
			op.status = status;
			if (attempt != null) {
				op.attemptNumber = attempt;
			}
		}
		if ("APPROVED".equals(status) && "CHECKIN".equals(op.kind)) {
			log.info("{}: garantía preautorizada", op.externalPaymentId); // aquí entregas la llave de la habitación
		} else if ("APPROVED".equals(status) && "CHECKOUT".equals(op.kind)) {
			log.info("{}: estadía cobrada", op.externalPaymentId); // aquí emites la boleta y cierras la reserva
		} else if (PosClient.FINAL.contains(status)) {
			log.info("{} terminó en {}", op.externalPaymentId, status);
		}
	}

	/** Comprobante de la estadía, línea por línea. https://docs.virtualpos.cl/pos-cloud/ventas-con-tarjeta/imprimir/ */
	private static List<Map<String, Object>> receiptLines(Reserva reserva) {
		List<Map<String, Object>> lines = new ArrayList<>();
		lines.add(Map.of("type", "text", "content", "Comprobante de estadía", "font_size", "big", "align", "center"));
		lines.add(Map.of("type", "blank"));
		lines.add(Map.of("type", "left_right", "left", "Reserva", "right", reserva.reservaId, "font_size", "small"));
		lines.add(Map.of("type", "left_right", "left", "Garantía", "right", clp(reserva.guarantee), "font_size", "small"));
		if (reserva.checkout != null) {
			lines.add(Map.of("type", "left_right", "left", "Total cobrado", "right", clp(reserva.checkout.amount), "font_size", "normal"));
		}
		Map<?, ?> card = reserva.checkin.detail;
		if (card != null && card.get("cardLast4") != null) {
			lines.add(Map.of("type", "left_right", "left", "Tarjeta",
					"right", Objects.toString(card.get("cardBrand"), "") + " ****" + card.get("cardLast4"), "font_size", "small"));
		}
		lines.add(Map.of("type", "blank"));
		lines.add(Map.of("type", "text", "content", "Gracias por su visita", "align", "center"));
		return lines;
	}

	private static final Map<String, String> MESSAGES = Map.of(
			"CREATED", "Enviando al POS…",
			"DISPATCHED", "Esperando que el POS tome la operación…",
			"CLAIMED", "El huésped está presentando su tarjeta…",
			"APPROVED", "<strong>Aprobada</strong>",
			"REJECTED", "<strong>Rechazada.</strong> Pide otra tarjeta y reintenta.",
			"FAILED", "<strong>No llegó al POS.</strong> Revisa que esté encendido, con internet, con la app Virtualpos abierta y sin otra operación en curso.",
			"EXPIRED", "<strong>El POS no tomó la operación a tiempo.</strong> Revisa que esté encendido y libre.",
			"CANCELLED", "<strong>Se canceló en el POS.</strong>",
			"REVERSED", "<strong>Fue reversada.</strong>");

	private static String reservaHtml(Reserva r) {
		String id = r.reservaId;
		StringBuilder html = new StringBuilder("<h2>Check-in (" + clp(r.guarantee) + ")</h2><p>" + message(r.checkin.status) + "</p>");
		Map<?, ?> card = r.checkin.detail;
		if (card != null) {
			html.append("<p>Autorización <code>").append(escape(Objects.toString(card.get("authCode"), ""))).append("</code> · ")
					.append(escape(Objects.toString(card.get("cardBrand"), ""))).append(" terminada en ")
					.append(escape(Objects.toString(card.get("cardLast4"), ""))).append("</p>");
		}
		if (retryable(r.checkin)) {
			html.append(retryForm(id, "checkin"));
		}
		if ("APPROVED".equals(r.checkin.status) && card != null && r.checkout == null) {
			html.append("<h2>Check-out</h2><form method=\"post\" action=\"/reservas/").append(id).append("/checkout\">")
					.append("<label>Monto final <input name=\"amount\" type=\"number\" min=\"1\" value=\"").append(r.guarantee)
					.append("\" required></label> <button>Cobrar en el POS</button></form>");
		}
		if (r.checkout != null) {
			html.append("<h2>Check-out (").append(clp(r.checkout.amount)).append(")</h2><p>").append(message(r.checkout.status)).append("</p>");
			if (retryable(r.checkout)) {
				html.append(retryForm(id, "checkout"));
			}
			if ("APPROVED".equals(r.checkout.status)) {
				html.append("<form method=\"post\" action=\"/reservas/").append(id).append("/imprimir\"><button>Imprimir comprobante</button></form>");
			}
		}
		synchronized (r.prints) {
			for (Operation p : r.prints) {
				html.append("<p>Impresión ").append(p.externalPaymentId).append(": ")
						.append("APPROVED".equals(p.status) ? "impresa" : message(p.status)).append("</p>");
			}
		}
		return html.append("<p><a href=\"/\">Volver a la recepción</a></p>").toString();
	}

	private static boolean retryable(Operation op) {
		return PosClient.FINAL.contains(op.status) && !"APPROVED".equals(op.status);
	}

	private static String retryForm(String reservaId, String kind) {
		return "<form method=\"post\" action=\"/reservas/" + reservaId + "/reintentar/" + kind + "\"><button>Reintentar</button></form>";
	}

	private static String message(String status) {
		return MESSAGES.getOrDefault(status, escape(status));
	}

	private static String clp(long amount) {
		return "$" + CLP.format(amount);
	}

	private static String back(Reserva r) {
		return "<p><a href=\"/reservas/" + r.reservaId + "\">Volver a la reserva</a></p>";
	}

	private static ResponseEntity<String> redirect(Reserva r) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/reservas/" + r.reservaId).build();
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + escape(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:36rem;margin:3rem auto;padding:0 1rem\"><h1>" + escape(title) + "</h1>" + body + "</body>";
	}
}
