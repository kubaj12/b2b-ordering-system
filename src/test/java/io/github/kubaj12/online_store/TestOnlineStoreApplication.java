package io.github.kubaj12.online_store;

import org.springframework.boot.SpringApplication;

public class TestOnlineStoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(OnlineStoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
