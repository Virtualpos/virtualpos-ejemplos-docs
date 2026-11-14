package cl.virtualpos.ejemplos.pagounico;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * Guía: Cobrar un pago único con Web Checkout
 * https://docs.virtualpos.cl/guias/pago-unico-web-checkout/
 */
@RestController
public class TiendaController {

	private static final Logger log = LoggerFactory.getLogger(TiendaController.class);
	private static final Set<String> FINAL = Set.of("pagado", "rechazado", "expirado", "cancelado", "anulado");

	/** Un pedido de tu sistema. */
	static final class Order {
		final String orderId;
		final int amount;
		final String uuid;
		volatile String status;

		Order(String orderId, int amount, String uuid, String status) {
			this.orderId = orderId;
			this.amount = amount;
			this.uuid = uuid;
			this.status = status;
		}
	}

	// En tu sistema esto sería tu base de datos de pedidos.
	private final Map<String, Order> orders = new ConcurrentHashMap<>();
	private final VirtualposClient vp;
	private final String publicUrl;

	public TiendaController(VirtualposClient vp, @Value("${app.public-url}") String publicUrl) {
		this.vp = vp;
		this.publicUrl = publicUrl.replaceAll("/$", "");
	}

	// 1. Página de tu tienda con el botón de pago.
	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public String home() {
		return page("Tienda de ejemplo", """
				<p>Pedido de prueba por <strong>$9.990</strong>.</p>
				<form method="post" action="/pagar"><button>Pagar con Virtualpos</button></form>""");
	}

	// 2. Crea el Payment y redirige al cliente al Web Checkout.
	@PostMapping("/pagar")
	@SuppressWarnings("unchecked")
	public ResponseEntity<String> pagar() {
		String orderId = "ORD-" + System.currentTimeMillis();
		int amount = 9990;
		Map<String, Object> res = vp.createPayment(Map.ofEntries(
				Map.entry("amount", amount),
				Map.entry("email", "cliente@example.com"),
				Map.entry("social_id", "11111111-1"),
				Map.entry("first_name", "Ana"),
				Map.entry("last_name", "Pérez"),
				Map.entry("phone", "56912345678"),
				Map.entry("description", "Pedido " + orderId),
				Map.entry("merchant_internal_code", orderId),
				Map.entry("merchant_internal_channel", "tienda-ejemplo"),
				Map.entry("return_url", publicUrl + "/retorno"),
				Map.entry("callback_url", publicUrl + "/webhook")));
		Map<String, Object> order = (Map<String, Object>) ((Map<String, Object>) res.get("payment")).get("order");
		String uuid = (String) order.get("uuid");
		orders.put(orderId, new Order(orderId, amount, uuid, (String) order.get("status")));
		log.info("Payment {} creado para {}", uuid, orderId);
		return ResponseEntity.status(HttpStatus.SEE_OTHER)
				.header(HttpHeaders.LOCATION, (String) res.get("url_redirect"))
				.build();
	}

	// 3. Webhook: Virtualpos avisa que el pago cambió. Responde 200 de inmediato y consulta el resultado después.
	//    El uuid puede llegar como JSON o como formulario.
	@PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> webhookJson(@RequestBody Map<String, Object> body) {
		return webhook((String) body.get("uuid"));
	}

	@PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Void> webhookForm(@RequestParam(required = false) String uuid) {
		return webhook(uuid);
	}

	private ResponseEntity<Void> webhook(String uuid) {
		if (uuid != null) {
			CompletableFuture.runAsync(() -> syncPayment(uuid))
					.exceptionally(err -> { log.error("Webhook: {}", err.getMessage()); return null; });
		}
		return ResponseEntity.ok().build();
	}

	// 4. Retorno: Virtualpos devuelve al cliente a tu sitio con el uuid por POST.
	@PostMapping(value = "/retorno", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
	public String retornoForm(@RequestParam(required = false) String uuid) {
		return retorno(uuid);
	}

	@PostMapping(value = "/retorno", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_HTML_VALUE)
	public String retornoJson(@RequestBody Map<String, Object> body) {
		return retorno((String) body.get("uuid"));
	}

	private String retorno(String uuid) {
		Order order = syncPayment(uuid);
		boolean paid = "pagado".equals(order.status);
		return page(paid ? "¡Pago recibido!" : "El pago no se completó",
				"<p>Pedido <code>" + esc(order.orderId) + "</code>: <strong>" + esc(order.status) + "</strong></p>"
						+ (paid
								? "<form method=\"post\" action=\"/anular/" + esc(order.orderId) + "\"><button>Anular este pago</button></form>"
								: "<p><a href=\"/\">Intentar nuevamente</a></p>"));
	}

	// 5. Anulación total del pago (opcional).
	@PostMapping(value = "/anular/{orderId}", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> anular(@PathVariable String orderId) {
		Order order = orders.get(orderId);
		if (order == null || !"pagado".equals(order.status)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Pedido no encontrado", ""));
		}
		Map<String, Object> refund = vp.refundPayment(order.uuid, order.amount);
		order.status = "anulado";
		return ResponseEntity.ok(page("Pago anulado",
				"<p>Estado de la anulación: <strong>" + esc(String.valueOf(refund.get("status"))) + "</strong></p><p><a href=\"/\">Volver</a></p>"));
	}

	/**
	 * Fuente de verdad: siempre consulta el Payment en la API. Es idempotente:
	 * webhook y retorno pueden llegar en cualquier orden o repetirse.
	 */
	@SuppressWarnings("unchecked")
	private synchronized Order syncPayment(String uuid) {
		if (uuid == null || uuid.isBlank()) {
			throw new IllegalArgumentException("No llegó el uuid del pago");
		}
		Map<String, Object> payment = (Map<String, Object>) ((Map<String, Object>) vp.getPayment(uuid).get("payment")).get("order");
		String orderId = (String) payment.get("merchant_internal_code");
		Order order = orders.computeIfAbsent(orderId,
				id -> new Order(id, ((Number) payment.get("amount")).intValue(), uuid, null));
		if (order.status != null && FINAL.contains(order.status)) {
			return order; // ya procesado: no lo proceses dos veces
		}
		order.status = (String) payment.get("status");
		if ("pagado".equals(order.status)) {
			// Aquí confirmas el pedido en tu sistema: despacho, boleta, correo al cliente…
			log.info("Pedido {} pagado (autorización {})", orderId, payment.get("auth_code"));
		}
		return order;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<String> error(Exception err) {
		String hint = err instanceof VirtualposException vpe ? vpe.getHint() : null;
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
				.body(page("Ocurrió un error", "<p><code>" + esc(err.getMessage()) + "</code></p>"
						+ (hint != null ? "<p>" + esc(hint) + "</p>" : "") + "<p><a href=\"/\">Volver</a></p>"));
	}

	private static String esc(String s) {
		return HtmlUtils.htmlEscape(s == null ? "" : s);
	}

	private static String page(String title, String body) {
		return "<!doctype html><meta charset=\"utf-8\"><title>" + esc(title) + "</title>"
				+ "<body style=\"font-family:system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem\"><h1>"
				+ esc(title) + "</h1>" + body + "</body>";
	}
}
