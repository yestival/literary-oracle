package com.literaryoracle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LiteraryOracleApplication {

	public static void main(String[] args) {
		if (System.getProperty("java.net.useSystemProxies") == null) {
			System.setProperty("java.net.useSystemProxies", "true");
		}
		SpringApplication.run(LiteraryOracleApplication.class, args);
	}

}
