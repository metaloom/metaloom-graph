package io.metaloom.graph.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.metaloom.graph.cli.dump.DumpFormatException;
import io.metaloom.graph.cli.dump.DumpReader;
import io.metaloom.graph.cli.dump.GraphBackup;
import io.metaloom.graph.cli.dump.GraphRestore;

/**
 * The online half: a running server, over HTTP.
 *
 * <h2>Why HTTP and not the binary port</h2>
 *
 * <p>
 * The binary protocol carries Cypher and nothing else — no schema call, no admin call — so a backup over it would need
 * new frame types, a protocol version bump that the handshake refuses to negotiate around, new error codes and a branch
 * in the server's dispatcher, all to move a bulk transfer that is not a row stream and gains nothing from the frame
 * machinery. One HTTP GET does the same job with no protocol surface at all.
 * </p>
 *
 * <p>
 * {@code java.net.http} rather than {@code graph-client}: the client deliberately carries no engine — a test fails by
 * loading if {@code graph-core} ever reaches its classpath — and this module needs the engine for offline mode. Pulling
 * Vert.x in for a single request would be the wrong trade in the other direction.
 * </p>
 *
 * <h2>No timeout on the body</h2>
 *
 * <p>
 * A request timeout would be a bound on how large a database may be backed up, which is not a thing this tool gets to
 * decide. The connect timeout stays, because a server that is not there should fail quickly rather than look slow.
 * </p>
 */
final class RemoteGraph {

	private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

	private RemoteGraph() {
	}

	private static HttpClient client() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	/** A GET carrying the token when there is one. Authentication is on by default, so without it every call is a 401. */
	private static HttpRequest get(URI uri, String token) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return request.build();
	}

	private static final String NEEDS_TOKEN = "The server requires a login. Log in with POST /api/login and pass "
		+ "the \"token\" it returns in GRAPH_TOKEN.";

	static GraphBackup.Summary backup(String server, String token, String database, OutputStream out,
		GraphBackup.Progress progress) throws IOException {
		URI uri = URI.create(trim(server) + "/api/databases/" + encode(database) + "/backup");
		HttpRequest request = get(uri, token);
		HttpResponse<InputStream> response;
		try {
			response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while backing up " + database, e);
		}
		if (response.statusCode() != 200) {
			throw new IOException(explain(response, database));
		}
		// The dump reports itself, at both ends.
		//
		// The server does send the counts, as HTTP trailers — it cannot send them as headers, because it does not know
		// them until the walk it is already streaming has finished. java.net.http does not expose trailers at all, so
		// a client reading them gets zeroes and says a full database held nothing. Reading the dump's own header and
		// its own trailer out of the bytes as they go past costs one 512-byte buffer and one 32-byte one, needs no
		// seeking (so `--out=-` works), and states what actually arrived rather than what the far end claimed.
		byte[] head = new byte[512];
		int headLength = 0;
		byte[] tail = new byte[GraphBackup.TRAILER_BYTES];
		int tailLength = 0;
		long bytes = 0;
		try (InputStream body = response.body()) {
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = body.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
				bytes += read;
				if (headLength < head.length) {
					int copy = Math.min(read, head.length - headLength);
					System.arraycopy(buffer, 0, head, headLength, copy);
					headLength += copy;
				}
				tailLength = remember(tail, tailLength, buffer, read);
			}
		}
		if (tailLength < tail.length) {
			throw new IOException("The server answered 200 with " + bytes + " bytes, which is too short to be a dump");
		}
		return GraphBackup.summaryOf(tail, bytes, snapshotLsnOf(head, headLength, database));
	}

	/**
	 * Keeps the last {@code tail.length} bytes of the stream, across chunk boundaries.
	 *
	 * @return how many of them there are, which is below {@code tail.length} only for a dump shorter than a trailer
	 */
	private static int remember(byte[] tail, int tailLength, byte[] buffer, int read) {
		if (read >= tail.length) {
			System.arraycopy(buffer, read - tail.length, tail, 0, tail.length);
			return tail.length;
		}
		int keep = Math.min(tailLength, tail.length - read);
		System.arraycopy(tail, tailLength - keep, tail, 0, keep);
		System.arraycopy(buffer, 0, tail, keep, read);
		return keep + read;
	}

	/**
	 * Parses the dump's own header out of the first bytes that arrived.
	 *
	 * <p>
	 * For the snapshot LSN, which is in the dump and in no header. It also catches the case worth catching early: a
	 * {@code 200} carrying something that is not a dump at all — a proxy's error page, a login form — which would
	 * otherwise be written out in full and discovered only when someone tried to restore it.
	 * </p>
	 */
	private static long snapshotLsnOf(byte[] head, int headLength, String database) throws IOException {
		try (DumpReader in = new DumpReader(new ByteArrayInputStream(head, 0, headLength), headLength)) {
			return GraphRestore.header(in).snapshotLsn();
		} catch (DumpFormatException notADump) {
			throw new IOException("The server answered 200 backing up '" + database + "', but what it sent is not a "
				+ "dump: " + notADump.getMessage(), notADump);
		}
	}

	static List<String> databases(String server, String token) throws IOException {
		URI uri = URI.create(trim(server) + "/api/databases");
		HttpResponse<String> response;
		try {
			response = client().send(get(uri, token), HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while listing databases", e);
		}
		if (response.statusCode() == 401) {
			throw new IOException(NEEDS_TOKEN);
		}
		if (response.statusCode() != 200) {
			throw new IOException("The server answered " + response.statusCode() + " listing databases");
		}
		// A regex rather than a JSON parser, because adding a JSON dependency to a CLI whose only structured response
		// is a list of names would be a strange thing to ship. The shape is this project's own and is tested.
		List<String> names = new ArrayList<>();
		Matcher matcher = NAME.matcher(response.body());
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	private static long header(HttpResponse<?> response, String name) {
		return response.headers().firstValue(name).map(Long::parseLong).orElse(0L);
	}

	/** Turns a status code into the sentence an operator can act on. */
	private static String explain(HttpResponse<InputStream> response, String database) {
		String body = "";
		try (InputStream in = response.body()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// The status is the useful part; a body that cannot be read does not change what to do.
		}
		return switch (response.statusCode()) {
			case 401 -> NEEDS_TOKEN + " " + body;
			// Either refusal: the flag, or a login that does not hold BACKUP on this database. The body says which.
			case 403 -> "The server refuses to serve a backup. It has to be started with --allow-backup, which is a "
				+ "separate flag from --allow-write because a backup reads every byte of a database in one request, "
				+ "and the login has to hold BACKUP on it. " + body;
			case 404 -> "The server has no database '" + database + "'. " + body;
			default -> "The server answered " + response.statusCode() + " backing up '" + database + "'. " + body;
		};
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String trim(String server) {
		String url = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
		return url.contains("://") ? url : "http://" + url;
	}
}
