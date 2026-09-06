package io.github.kubaj12.online_store.shared.web.request;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
final class BrowserRequestTestApplicationService {

	private String loadValue = "";
	private int loadCount;
	private final List<String> submissions = new ArrayList<>();

	String load() {
		loadCount++;
		return loadValue;
	}

	void submit(String name) {
		submissions.add(name);
	}

	void respondWith(String value) {
		loadValue = value;
	}

	int loadCount() {
		return loadCount;
	}

	List<String> submissions() {
		return List.copyOf(submissions);
	}

	void reset() {
		loadValue = "";
		loadCount = 0;
		submissions.clear();
	}

}
