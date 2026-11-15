package cl.virtualpos.ejemplos.ventapos;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Guía: Cobrar una venta en el POS desde tu ERP
 * https://docs.virtualpos.cl/guias/venta-pos-desde-erp/
 */
@RestController
public class CajaController {

	private static final Logger log = LoggerFactory.getLogger(CajaController.class);
	private static final NumberFormat CLP = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL"));

	/** En tu sistema esto sería tu base de datos de ventas. */
	static final class Sale {
		final String saleId;
		final long amount;
		final long tipAmount;
		String paymentRequestId;
		String status = "CREATED";
		Integer attemptNumber;
		Map<String, Object> detail;
		long lastSync;

		Sale(String saleId, long amount, long tipAmount) {
			this.saleId = saleId;
			this.amount = amount;
			this.tipAmount = tipAmount;
		}
	}

	private final Map<String, Sale> sales = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	private final Set<String> seenEvents = ConcurrentHashMap.newKeySet(); // eventId de webhooks ya procesados
	private final PosClient pos;
	private final JsonMapper json;
	private final String posSerial;
	private final String webhookSecret;
	private final String publicUrl;

	public CajaController(PosClient pos, JsonMapper json,
			@Value("${virtualpos.pos.serial}") String posSerial,
			@Value("${virtualpos.pos.webhook-secret}") String webhookSecret,
			@Value("${app.public-url}") String publicUrl) {
		if (posSerial.isBlank()) {
			throw new IllegalStateException("Configura VIRTUALPOS_POS_SERIAL en tu archivo .env");
		}
		if (webhookSecret.isBlank()) {
			log.warn("Sin VIRTUALPOS_WEBHOOK_SECRET no podrás verificar webhooks: la caja consultará el estado a la API.");
		}
		this.pos = pos;
		this.json = json;
		this.posSerial = posSerial;
		this.webhookSecret = webhookSecret;
		this.publicUrl = publicUrl.replaceAll("/$", "");
	}

	/** 1. Pantalla de la caja: monto, propina y botón para cobrar en el POS. */
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String caja() {
		StringBuilder rows = new StringBuilder();
		List<Sale> list;
		synchronized (sales) {
			list = new ArrayList<>(sales.values());
		}
		Collections.reverse(list);
		for (Sale s : list) {
			rows.append("<tr><td><a href=\"/ventas/").append(s.saleId).append("\">").append(s.saleId).append("</a></td><td>$")
					.append(CLP.format(s.amount)).append("</td><td>").append(s.status).append("</td></tr>");
		}
		return page("Caja de ejemplo", """
				<form method="post" action="/cobrar">
				  <label>Monto <input name="amount" type="number" min="1" value="9990" required></label>
				  <label>Propina <input name="tipAmount" type="number" min="0" value="0"></label>
				  <button>Cobrar en el POS %s</button>
				</form>""".formatted(escape(posSerial)) + (rows.isEmpty() ? "" : "<h2>Ventas</h2><table>" + rows + "</table>"));
	}

	/** 2. Envía la venta al POS. El ID de la venta en tu sistema es el externalPaymentId. */
	@PostMapping(value = "/cobrar", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> cobrar(@RequestParam long amount, @RequestParam(defaultValue = "0") long tipAmount) {
		Sale sale = new Sale("VENTA-" + System.currentTimeMillis(), amount, tipAmount);
		sales.put(sale.saleId, sale); // guárdala ANTES de llamar a la API: si hay timeout, sabes qué ID reintentar
		return send(sale, "No se pudo enviar al POS");
	}

	/** 3. Pantalla de espera: el navegador consulta el estado cada 2 segundos hasta que la venta termina. */
	@GetMapping(value = "/ventas/{saleId}", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> venta(@PathVariable String saleId) {
		Sale sale = sales.get(saleId);
		if (sale == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Venta no encontrada", "<p><a href=\"/\">Volver</a></p>"));
		}
		return ResponseEntity.ok(page("Venta " + sale.saleId,
				"<p>Monto: <strong>$" + CLP.format(sale.amount + sale.tipAmount) + "</strong></p>" + waitingScript()));
	}

	/** Estado para la pantalla de espera. Si el webhook no ha llegado, consulta la API (máximo cada 3 segundos). */
	@GetMapping("/ventas/{saleId}/estado")
	public ResponseEntity<Map<String, Object>> estado(@PathVariable String saleId) {
		Sale sale = sales.get(saleId);
		if (sale == null) {
			return ResponseEntity.notFound().build();
		}
		boolean sync;
		synchronized (sale) {
			sync = !PosClient.FINAL.contains(sale.status) && sale.paymentRequestId != null
					&& System.currentTimeMillis() - sale.lastSync > 3000;
			if (sync) {
				sale.lastSync = System.currentTimeMillis();
			}
		}
		if (sync) {
			try {
				applyStatus(sale, pos.getOperation(sale.paymentRequestId));
			} catch (PosException err) {
				log.error("Consulta de {}: {}", sale.saleId, err.getMessage());
			}
		}
		if ("APPROVED".equals(sale.status) && sale.detail == null) {
			loadDetail(sale);
		}
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("status", sale.status);
		body.put("final", PosClient.FINAL.contains(sale.status));
		body.put("detail", sale.detail);
		return ResponseEntity.ok(body);
	}

	/**
	 * 4. Reintentar una venta que terminó sin aprobarse. Usa el MISMO externalPaymentId:
	 * si la venta ya estuviera aprobada, Virtualpos responde con ella y no vuelve a cobrar.
	 */
	@PostMapping(value = "/ventas/{saleId}/reintentar", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> reintentar(@PathVariable String saleId) {
		Sale sale = sales.get(saleId);
		if (sale == null) {
			return ResponseEntity.notFound().build();
		}
		// Nunca reenvíes una venta en curso: el cliente podría estar pagando en el POS en este momento.
		// Excepción: si el envío falló por timeout o error 500 no tienes paymentRequestId, y debes reintentar con el mismo ID.
		boolean unknown = sale.paymentRequestId == null;
		boolean retryable = PosClient.FINAL.contains(sale.status) && !"APPROVED".equals(sale.status);
		if (!unknown && !retryable) {
			return redirect(sale);
		}
		return send(sale, "No se pudo reintentar");
	}

	/** 5. Webhook: verifica la firma sobre el body crudo, procesa una sola vez y responde de inmediato. */
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
			Sale sale = sales.get((String) event.get("externalPaymentId"));
			if (sale == null) {
				log.warn("Webhook de una venta desconocida: {}", event.get("externalPaymentId"));
			} else {
				applyStatus(sale, event);
				if ("APPROVED".equals(event.get("status")) && event.get("resultPayload") instanceof Map<?, ?> result) {
					sale.detail = cardDetail(result);
				}
			}
		}
		return ResponseEntity.noContent().build();
	}

	private ResponseEntity<String> send(Sale sale, String errorTitle) {
		try {
			Map<String, Object> op = pos.createSale(posSerial, sale.saleId, sale.amount, sale.tipAmount, publicUrl + "/webhooks/virtualpos");
			synchronized (sale) {
				sale.paymentRequestId = (String) op.get("paymentRequestId");
				sale.attemptNumber = (Integer) op.get("attemptNumber");
				sale.status = (String) op.get("status");
				sale.lastSync = System.currentTimeMillis();
			}
			log.info("{} enviada al POS (intento {}, {})", sale.saleId, sale.attemptNumber, sale.status);
			if ("FAILED".equals(sale.status)) {
				log.warn("201 con FAILED: la venta no llegó al POS. ¿Está encendido, con internet y con la app Virtualpos abierta?");
			}
			return redirect(sale);
		} catch (PosException err) {
			// Timeout o 500: no sabes si la venta llegó al POS. Reintenta con el MISMO externalPaymentId, nunca con uno nuevo.
			String retry = err.isUnknownResult()
					? "<form method=\"post\" action=\"/ventas/" + sale.saleId + "/reintentar\"><button>Reintentar " + sale.saleId + "</button></form>"
					: "";
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(page(errorTitle,
					"<p><code>" + escape(err.getMessage()) + "</code></p>"
							+ (err.getHint() != null ? "<p>" + escape(err.getHint()) + "</p>" : "")
							+ retry + "<p><a href=\"/\">Volver</a></p>"));
		}
	}

	/** Aplica un estado recibido por webhook o por consulta. Idempotente: ignora intentos anteriores y no cambia una venta que ya terminó. */
	private void applyStatus(Sale sale, Map<String, Object> op) {
		Integer attempt = (Integer) op.get("attemptNumber");
		String status = (String) op.get("status");
		synchronized (sale) {
			if (attempt != null && sale.attemptNumber != null && attempt < sale.attemptNumber) {
				return;
			}
			if (PosClient.FINAL.contains(sale.status) && java.util.Objects.equals(attempt, sale.attemptNumber)) {
				return;
			}
			sale.status = status;
			if (attempt != null) {
				sale.attemptNumber = attempt;
			}
		}
		if ("APPROVED".equals(status)) {
			// Aquí cierras la venta en tu ERP: boleta, descuento de stock, comprobante…
			log.info("{} APROBADA", sale.saleId);
		} else if (PosClient.FINAL.contains(status)) {
			log.info("{} terminó en {}", sale.saleId, status);
		}
	}

	/** El detalle de la tarjeta viene en el webhook. Si no lo recibiste, pídelo a callback-event. */
	private void loadDetail(Sale sale) {
		try {
			if (pos.getCallbackEvent(sale.paymentRequestId).get("resultPayload") instanceof Map<?, ?> result) {
				sale.detail = cardDetail(result);
			}
		} catch (PosException err) {
			log.error("Detalle de {}: {}", sale.saleId, err.getMessage());
		}
	}

	private static Map<String, Object> cardDetail(Map<?, ?> r) {
		Map<String, Object> d = new java.util.HashMap<>();
		for (String k : List.of("authCode", "cardBrand", "cardTypeLabel", "cardLast4", "posTransactionId")) {
			d.put(k, r.get(k));
		}
		return d;
	}

	private static ResponseEntity<String> redirect(Sale sale) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/ventas/" + sale.saleId).build();
	}

	private static String waitingScript() {
		return """
				<p id="estado">Esperando que el cliente pague en el POS…</p><div id="acciones"></div>
				<script>
				  const mensajes = {
				    CREATED: 'Enviando la venta al POS…',
				    DISPATCHED: 'Esperando que el POS tome la venta…',
				    CLAIMED: 'El cliente está pagando en el POS…',
				    APPROVED: '<strong>Venta aprobada</strong>',
				    REJECTED: '<strong>Pago rechazado.</strong> Pide otra tarjeta o reintenta.',
				    FAILED: '<strong>La venta no llegó al POS.</strong> Revisa que esté encendido, con internet y con la app Virtualpos abierta.',
				    EXPIRED: '<strong>El POS no tomó la venta a tiempo.</strong> Revisa que esté encendido y libre.',
				    CANCELLED: '<strong>La venta se canceló en el POS.</strong>',
				    REVERSED: '<strong>La venta fue reversada.</strong>',
				  };
				  async function revisar() {
				    const s = await (await fetch(location.pathname + '/estado')).json();
				    document.getElementById('estado').innerHTML = mensajes[s.status] ?? s.status;
				    if (!s.final) return setTimeout(revisar, 2000);
				    let html = '';
				    if (s.status === 'APPROVED') {
				      html = s.detail ? '<p>Autorización <code>' + s.detail.authCode + '</code> · ' + s.detail.cardBrand + ' ' + s.detail.cardTypeLabel + ' terminada en ' + s.detail.cardLast4 + '</p>' : '';
				    } else {
				      html = '<form method="post" action="' + location.pathname + '/reintentar"><button>Reintentar en el POS</button></form>';
				    }
				    document.getElementById('acciones').innerHTML = html + '<p><a href="/">Nueva venta</a></p>';
				  }
				  revisar();
				</script>""";
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + escape(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem\"><h1>" + escape(title) + "</h1>" + body + "</body>";
	}
}
