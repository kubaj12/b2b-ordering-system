package io.github.kubaj12.online_store.identityaccess.web;

final class SecurityProbeService {

	private int calls;

	synchronized String call() {
		calls++;
		return "ok";
	}

	synchronized int calls() {
		return calls;
	}

	synchronized void reset() {
		calls = 0;
	}

}
