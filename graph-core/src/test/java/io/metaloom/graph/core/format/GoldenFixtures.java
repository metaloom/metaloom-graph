package io.metaloom.graph.core.format;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The committed golden fixtures: one directory per store format version under {@code src/test/resources/format/},
 * each holding a zipped root of databases and the canonical text it must read back as.
 *
 * <pre>
 * format/v4/
 *   fixture.properties   the version, the options it was written with, when and from which commit
 *   root.zip             a root: golden/ (the store, closed cleanly) and .auth/ (the identity file)
 *   expected.txt         GoldenContent.canonical of golden/, read back when the fixture was generated
 *   expected-auth.txt    GoldenContent.canonicalAuth of the root
 * </pre>
 */
public final class GoldenFixtures {

	static final String RESOURCE_ROOT = "/format";
	static final String ZIP = "root.zip";
	static final String EXPECTED = "expected.txt";
	static final String EXPECTED_AUTH = "expected-auth.txt";
	static final String PROPERTIES = "fixture.properties";

	/** A fixed timestamp for every zip entry, so a regenerated zip differs only where the store's bytes do. */
	private static final FileTime EPOCH = FileTime.fromMillis(0);

	private GoldenFixtures() {
	}

	/** One committed fixture. */
	public record Fixture(int version, Path directory) {

		public String expected() throws IOException {
			return Files.readString(directory.resolve(EXPECTED), StandardCharsets.UTF_8);
		}

		public String expectedAuth() throws IOException {
			return Files.readString(directory.resolve(EXPECTED_AUTH), StandardCharsets.UTF_8);
		}

		public Properties properties() throws IOException {
			Properties properties = new Properties();
			try (InputStream in = Files.newInputStream(directory.resolve(PROPERTIES))) {
				properties.load(in);
			}
			return properties;
		}

		/** Unpacks the root into {@code target}, which must not exist yet, and returns the store inside it. */
		public Path unpack(Path target) throws IOException {
			unzip(directory.resolve(ZIP), target);
			return target.resolve(GoldenContent.DATABASE);
		}
	}

	/** Every committed fixture, oldest first, read from the test classpath. */
	public static List<Fixture> all() throws IOException {
		URL url = GoldenFixtures.class.getResource(RESOURCE_ROOT);
		if (url == null) {
			return List.of();
		}
		Path root;
		try {
			root = Path.of(url.toURI());
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
		List<Fixture> fixtures = new ArrayList<>();
		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : (Iterable<Path>) entries::iterator) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry) && name.matches("v[0-9]+")) {
					fixtures.add(new Fixture(Integer.parseInt(name.substring(1)), entry));
				}
			}
		}
		fixtures.sort((a, b) -> Integer.compare(a.version(), b.version()));
		return fixtures;
	}

	/** Zips a directory tree, directories included so that empty ones survive, in a fixed order. */
	static void zip(Path source, Path zip) throws IOException {
		List<Path> entries;
		try (Stream<Path> walk = Files.walk(source)) {
			entries = walk.filter(p -> !p.equals(source)).sorted().toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
			out.setLevel(9);
			byte[] buffer = new byte[1 << 16];
			for (Path entry : entries) {
				String name = source.relativize(entry).toString().replace('\\', '/');
				boolean directory = Files.isDirectory(entry);
				ZipEntry zipEntry = new ZipEntry(directory ? name + "/" : name);
				zipEntry.setLastModifiedTime(EPOCH);
				out.putNextEntry(zipEntry);
				if (!directory) {
					try (InputStream in = new BufferedInputStream(Files.newInputStream(entry))) {
						int read;
						while ((read = in.read(buffer)) > 0) {
							out.write(buffer, 0, read);
						}
					}
				}
				out.closeEntry();
			}
		}
	}

	/** Unzips, refusing any entry that would land outside the target. */
	static void unzip(Path zip, Path target) throws IOException {
		Files.createDirectories(target);
		Path base = target.toAbsolutePath().normalize();
		try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
			ZipEntry entry;
			byte[] buffer = new byte[1 << 16];
			while ((entry = in.getNextEntry()) != null) {
				Path destination = base.resolve(entry.getName()).normalize();
				if (!destination.startsWith(base)) {
					throw new IOException("Zip entry " + entry.getName() + " is outside the target");
				}
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
					continue;
				}
				Files.createDirectories(destination.getParent());
				try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination))) {
					int read;
					while ((read = in.read(buffer)) > 0) {
						out.write(buffer, 0, read);
					}
				}
			}
		}
	}
}
