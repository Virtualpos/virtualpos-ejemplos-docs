package cl.virtualpos.ejemplos.cobroporlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class CobroPorLinkApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("verificar")) {
			verificar(args);
			return;
		}
		SpringApplication.run(CobroPorLinkApplication.class, args);
	}

	/**
	 * Verifica tus credenciales sin crear nada: consulta un pago que no existe.
	 * Si la API responde "no existe transacción", tu API KEY, tu SECRET KEY y tu firma están bien.
	 */
	private static void verificar(String[] args) {
		SpringApplication app = new SpringApplication(CobroPorLinkApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.setLogStartupInfo(false);
		app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
		int exit = 0;
		try (ConfigurableApplicationContext ctx = app.run(args)) {
			VirtualposClient vp = ctx.getBean(VirtualposClient.class);
			try {
				vp.getPayment("0000000000000000");
				System.out.println("✔ Credenciales válidas");
			} catch (VirtualposException err) {
				if (err.getApiMessage() != null && err.getApiMessage().matches("(?is).*no existe (la orden|transacci).*")) {
					System.out.println("✔ Credenciales válidas para " + vp.getApiUrl());
				} else {
					System.err.println("✘ " + err.getMessage());
					if (err.getHint() != null) {
						System.err.println("  " + err.getHint());
					}
					exit = 1;
				}
			}
		}
		System.exit(exit);
	}
}
