package cl.virtualpos.ejemplos.suscripcionmensual;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SuscripcionMensualApplication {

	public static void main(String[] args) {
		if (args.length > 0 && (args[0].equals("verificar") || args[0].equals("crear-plan"))) {
			System.exit(runCommand(args));
		}
		SpringApplication.run(SuscripcionMensualApplication.class, args);
	}

	private static int runCommand(String[] args) {
		SpringApplication app = new SpringApplication(SuscripcionMensualApplication.class);
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
	 * Verifica tus credenciales y las dos firmas sin crear nada: consulta una suscripción que no existe.
	 * Si la API responde "no existe" / "no se encontró", tu API KEY, tu SECRET KEY y tus firmas están bien.
	 */
	private static int verificar(VirtualposClient vp, String planId) {
		String fakeId = "subsc_00000000-0000-0000-0000-000000000000";
		boolean ok = check("Firma {\"api_key\"} (planes, suscripciones y cargos)", () -> vp.getSubscription(fakeId), "(?is).*no existen suscripciones.*")
				& check("Firma {\"ciat\"} (Pagar y suscribir)", () -> vp.getPayAndSubscribe(fakeId), "(?is).*no se encontr.*");
		if (!ok) {
			return 1;
		}
		System.out.println("Credenciales válidas para " + vp.getApiUrl());
		if (planId.isBlank()) {
			System.out.println("Falta VIRTUALPOS_PLAN_ID: créalo con java -jar target/*.jar crear-plan");
		}
		return 0;
	}

	private static boolean check(String name, Runnable call, String notFound) {
		try {
			call.run();
		} catch (VirtualposException err) {
			if (err.getApiMessage() == null || !err.getApiMessage().matches(notFound)) {
				System.err.println("✘ " + name + ": " + err.getMessage());
				if (err.getHint() != null) {
					System.err.println("  " + err.getHint());
				}
				return false;
			}
		}
		System.out.println("✔ " + name);
		return true;
	}

	/** Crea el plan mensual una sola vez y muestra su ID para que lo guardes en VIRTUALPOS_PLAN_ID. */
	@SuppressWarnings("unchecked")
	private static int crearPlan(VirtualposClient vp, String publicUrl) {
		try {
			Map<String, Object> plan = (Map<String, Object>) vp.createPlan(Map.ofEntries(
					Map.entry("name", "Plan mensual"),
					Map.entry("description", "Suscripción mensual de ejemplo"),
					Map.entry("type", "MONTO_VARIABLE"), // Pagar y suscribir exige este tipo: tú informas el monto de cada cargo
					Map.entry("currency", "CLP"),
					Map.entry("amount", 0),
					Map.entry("frequency_type", "Mensual"),
					Map.entry("num_charges", 0),
					Map.entry("trial_days", 0),
					Map.entry("fixed_amount_day_charge", "0"),
					Map.entry("return_url", publicUrl))) // texto plano, no Base64
					.get("plan");
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
