package fruition;

import fruition.core.DocumentApplication;
import org.springframework.boot.SpringApplication;

public class TestDocumentApplication {

	public static void main(String[] args) {
		SpringApplication.from(DocumentApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
