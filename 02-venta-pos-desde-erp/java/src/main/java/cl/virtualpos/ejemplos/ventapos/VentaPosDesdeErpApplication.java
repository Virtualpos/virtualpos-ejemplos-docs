package cl.virtualpos.ejemplos.ventapos;

import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class VentaPosDesdeErpApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("verificar")) {
			verificar(args);
			return;
		}
		SpringApplication.run(VentaPosDesdeErpApplication.class, args);
	}

	/** Verifica tu API key de POS Cloud to Cloud sin crear operaciones: lista tu última operación. */
	private static void verificar(String[] args) {
		SpringApplication app = new SpringApplication(PosClient.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.setLogStartupInfo(false);
		app.setBannerMode(Banner.Mode.OFF);
		int exit = 0;
		try (ConfigurableApplicationContext ctx = app.run(args)) {
			PosClient pos = ctx.getBean(PosClient.class);
			Environment env = ctx.getEnvironment();
			try {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> items = (List<Map<String, Object>>) pos.listOperations(1).get("items");
				System.out.println("✔ API key válida en " + pos.getApiUrl());
				System.out.println(items.isEmpty() ? "  Aún no tienes operaciones."
						: "  Última operación: " + items.get(0).get("externalPaymentId") + " (" + items.get(0).get("status") + ")");
				String serial = env.getProperty("virtualpos.pos.serial", "");
				System.out.println(serial.isBlank() ? "✘ Falta VIRTUALPOS_POS_SERIAL en tu .env" : "  Serial del POS: " + serial);
				System.out.println(env.getProperty("virtualpos.pos.webhook-secret", "").isBlank()
						? "! Falta VIRTUALPOS_WEBHOOK_SECRET: no podrás verificar webhooks." : "  Secreto de webhook configurado.");
			} catch (PosException err) {
				System.err.println("✘ " + err.getMessage());
				if (err.getHint() != null) {
					System.err.println("  " + err.getHint());
				}
				exit = 1;
			}
		}
		System.exit(exit);
	}
}
