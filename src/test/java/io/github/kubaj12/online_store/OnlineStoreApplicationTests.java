package io.github.kubaj12.online_store;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OnlineStoreApplicationTests {

	@Test
	void contextLoads() {
	}

}
