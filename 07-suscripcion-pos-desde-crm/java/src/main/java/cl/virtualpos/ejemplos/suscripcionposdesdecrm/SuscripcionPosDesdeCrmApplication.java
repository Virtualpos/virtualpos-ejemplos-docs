package cl.virtualpos.ejemplos.suscripcionposdesdecrm;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SuscripcionPosDesdeCrmApplication {

	public static void main(String[] args) {
		if (args.length > 0 && (args[0].equals("verificar") || args[0].equals("crear-plan"))) {
			System.exit(runCommand(args));
		}
		SpringApplication.run(SuscripcionPosDesdeCrmApplication.class, args);
	}

	private static int runCommand(String[] args) {
		SpringApplication app = new SpringApplication(SuscripcionPosDesdeCrmApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.setLogStartupInfo(false);
		app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
		try (ConfigurableApplicationContext ctx = app.run(args)) {
			VirtualposClient vp = ctx.getBean(VirtualposClient.class);
			return args[0].equals("verificar")
					? verificar(vp, ctx.getEnvironment().getProperty("virtualpos.plan-id", ""))
					: crearPlan(vp, ctx.getEnvironment().getProperty("app.public-url"));
		}
	}

	/**
	 * Verifica tus credenciales sin crear nada ni enviar nada al POS: consulta una suscripción que no existe.
	 * Si la API responde "no existen suscripciones", tu API KEY y tu firma están bien.
	 */
	private static int verificar(VirtualposClient vp, String planId) {
		try {
			vp.getSubscription("subsc_00000000-0000-0000-0000-000000000000");
			System.out.println("✔ Credenciales válidas");
		} catch (VirtualposException err) {
			if (err.getApiMessage() != null && err.getApiMessage().matches("(?is).*no existen suscripciones.*")) {
				System.out.println("✔ Credenciales válidas para " + vp.getApiUrl());
			} else {
				System.err.println("✘ " + err.getMessage());
				if (err.getHint() != null) {
					System.err.println("  " + err.getHint());
				}
				return 1;
			}
		}
		if (planId.isBlank()) {
			System.out.println("Falta VIRTUALPOS_PLAN_ID: créalo con java -jar target/*.jar crear-plan");
		}
		return 0;
	}

	/** Crea una sola vez el plan que ofrecerás en el POS y muestra su ID para que lo guardes en VIRTUALPOS_PLAN_ID. */
	private static int crearPlan(VirtualposClient vp, String publicUrl) {
		try {
			Map<String, Object> plan = vp.createPlan(Map.ofEntries(
					Map.entry("name", "Plan mensual"),
					Map.entry("description", "Suscripción mensual de ejemplo"),
					Map.entry("type", "MONTO_FIJO"),
					Map.entry("currency", "CLP"),
					Map.entry("amount", 9990),
					Map.entry("frequency_type", "Mensual"),
					Map.entry("num_charges", 12),
					Map.entry("trial_days", 0),
					Map.entry("fixed_amount_day_charge", "05"),
					Map.entry("return_url", publicUrl), // texto plano, no Base64
					Map.entry("show_in_terminal", "T"), // sin esto, el POS rechaza la suscripción (E-PTT-003)
					Map.entry("shipping_address", "F"))); // los planes con dirección de despacho no se pueden suscribir en el POS
			System.out.println("✔ Plan creado: " + plan.get("name") + " (" + plan.get("type") + ", " + plan.get("frequency_type") + ")");
			System.out.println("  Agrega esta línea a tu .env:\n  VIRTUALPOS_PLAN_ID=" + plan.get("id"));
			return 0;
		} catch (VirtualposException err) {
			System.err.println("✘ " + err.getMessage());
			if (err.getHint() != null) {
				System.err.println("  " + err.getHint());
			}
			return 1;
		}
	}
}
