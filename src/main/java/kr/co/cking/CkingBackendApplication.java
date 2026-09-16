package kr.co.cking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CkingBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CkingBackendApplication.class, args);
	}

}
