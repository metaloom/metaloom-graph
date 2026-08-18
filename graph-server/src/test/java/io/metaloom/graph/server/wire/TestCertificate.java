package io.metaloom.graph.server.wire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import io.vertx.core.net.SelfSignedCertificate;

/**
 * A throwaway certificate for {@code localhost}, generated per test.
 *
 * <p>
 * Generated rather than committed, for one reason that outweighs the convenience of a fixture file: a certificate has
 * an expiry, and a committed one turns into a suite that passes for years and then fails on a date nobody chose, in a
 * way that reads like a TLS defect. Nothing here is a secret worth keeping either — the key exists for the length of
 * one test method.
 * </p>
 *
 * <p>
 * The PEM is copied out of the generator's own temporary location into a directory the test owns, because that is the
 * shape an operator actually has: two files at paths handed to the server as configuration. Reading them from where
 * the test put them is part of what is being tested.
 * </p>
 */
final class TestCertificate {

	private final Path certificate;
	private final Path privateKey;

	private TestCertificate(Path certificate, Path privateKey) {
		this.certificate = certificate;
		this.privateKey = privateKey;
	}

	/**
	 * @param directory
	 *            usually a {@code @TempDir} or a {@code TempStore} path, so the key material goes away with the test
	 */
	static TestCertificate create(Path directory) throws IOException {
		SelfSignedCertificate generated = SelfSignedCertificate.create("localhost");
		Path certificate = directory.resolve("tls.crt");
		Path privateKey = directory.resolve("tls.key");
		// The same two names a Kubernetes tls secret uses, so the paths in a test read like the paths in the chart.
		Files.copy(Path.of(generated.certificatePath()), certificate, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(Path.of(generated.privateKeyPath()), privateKey, StandardCopyOption.REPLACE_EXISTING);
		generated.delete();
		return new TestCertificate(certificate, privateKey);
	}

	Path certificate() {
		return certificate;
	}

	Path privateKey() {
		return privateKey;
	}
}
