package io.metaloom.graph.apilock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The compiled classes of one module, as a classes directory or a jar: whatever the anchor class was loaded from.
 *
 * <p>
 * Listing the class files is what makes a newly added public type part of the dump without anyone naming it.
 * </p>
 */
final class ClassSource implements AutoCloseable {

	private final Path location;
	private final ZipFile jar;

	private ClassSource(Path location, ZipFile jar) {
		this.location = location;
		this.jar = jar;
	}

	static ClassSource of(Class<?> anchor) {
		CodeSource codeSource = anchor.getProtectionDomain().getCodeSource();
		if (codeSource == null || codeSource.getLocation() == null) {
			throw new IllegalStateException("Cannot locate the classes of " + anchor.getName() + ": it has no code source");
		}
		Path location;
		try {
			location = Path.of(codeSource.getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException("Cannot locate the classes of " + anchor.getName(), e);
		}
		try {
			return Files.isDirectory(location) ? new ClassSource(location, null) : new ClassSource(location, new ZipFile(location.toFile()));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot open " + location, e);
		}
	}

	Path location() {
		return location;
	}

	/** Binary names of every class file directly in the package, nested classes included, sorted. */
	List<String> classesIn(String packageName) {
		String dir = packageName.replace('.', '/');
		List<String> names = new ArrayList<>();
		if (jar == null) {
			Path packageDir = location.resolve(dir);
			if (!Files.isDirectory(packageDir)) {
				return List.of();
			}
			try (Stream<Path> files = Files.list(packageDir)) {
				files.map(path -> path.getFileName().toString())
					.filter(ClassSource::isTypeClassFile)
					.forEach(file -> names.add(packageName + "." + file.substring(0, file.length() - ".class".length())));
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot list " + packageDir, e);
			}
		} else {
			String prefix = dir + "/";
			for (ZipEntry entry : Collections.list(jar.entries())) {
				String name = entry.getName();
				if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0 && isTypeClassFile(name.substring(prefix.length()))) {
					names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
				}
			}
		}
		Collections.sort(names);
		return names;
	}

	/** The class file bytes of a binary name, or empty if this source has none. */
	Optional<byte[]> bytes(String binaryName) {
		return resource(binaryName.replace('.', '/') + ".class");
	}

	/** The {@code module-info.class} at the root of this source, or empty. */
	Optional<byte[]> moduleInfo() {
		return resource("module-info.class");
	}

	private Optional<byte[]> resource(String path) {
		try {
			if (jar == null) {
				Path file = location.resolve(path);
				return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
			}
			ZipEntry entry = jar.getEntry(path);
			if (entry == null) {
				return Optional.empty();
			}
			try (InputStream in = jar.getInputStream(entry)) {
				return Optional.of(in.readAllBytes());
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + path + " from " + location, e);
		}
	}

	private static boolean isTypeClassFile(String fileName) {
		return fileName.endsWith(".class") && !fileName.equals("package-info.class") && !fileName.equals("module-info.class");
	}

	@Override
	public void close() {
		if (jar != null) {
			try {
				jar.close();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
