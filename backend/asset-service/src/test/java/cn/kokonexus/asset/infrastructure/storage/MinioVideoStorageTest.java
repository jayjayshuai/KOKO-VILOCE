package cn.kokonexus.asset.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实MinIO SDK与环回HTTP协议夹具，不冒充真实MinIO部署或生产对象验收。 */
class MinioVideoStorageTest {

    @TempDir
    Path directory;

    @Test
    void removesExactKeyVersionsAndDeleteMarkersButNeverSimilarPrefix() throws Exception {
        String key = "videos/42/36f1fb46-91cd-4f32-b174-55b0c5d4bf17/source.mp4";
        var deleted = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("location")) {
                    xml(
                        exchange,
                        200,
                        "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                    );
                    return;
                }
                if (query != null && query.contains("versions")) {
                    String entries = "";
                    if (!deleted.contains("versionId=v1")) entries += version("Version", key, "v1");
                    if (!deleted.contains("versionId=null")) entries += version("Version", key, "null");
                    if (!deleted.contains("versionId=marker")) entries += version("DeleteMarker", key, "marker");
                    entries += version("Version", key + "-other", "foreign");
                    xml(exchange, 200, listing(key, entries));
                    return;
                }
                if (exchange.getRequestMethod().equals("DELETE")) {
                    if (
                        !exchange
                            .getRequestURI()
                            .getPath()
                            .equals("/private-test/" + key)
                    ) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    deleted.add(query);
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            storage(server).removeAndConfirm(
                key,
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            );
            assertEquals(
                java.util.Set.of("versionId=v1", "versionId=null", "versionId=marker"),
                java.util.Set.copyOf(deleted)
            );
            assertEquals(3, deleted.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cleanupNeverConfusesForbiddenServerErrorRemainingVersionOrExpiredBudgetWithAbsence() throws Exception {
        String key = "videos/42/36f1fb46-91cd-4f32-b174-55b0c5d4bf17/source.mp4";
        for (String mode : List.of("forbidden", "server", "remaining", "too-many", "head-forbidden", "head-present")) {
            var mutations = new java.util.concurrent.atomic.AtomicInteger();
            var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query != null && query.contains("location")) {
                        xml(
                            exchange,
                            200,
                            "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                        );
                        return;
                    }
                    if (query != null && query.contains("versions")) {
                        if (mode.equals("forbidden")) {
                            xml(exchange, 403, "<Error><Code>AccessDenied</Code><Message>synthetic</Message></Error>");
                            return;
                        }
                        if (mode.equals("server")) {
                            xml(exchange, 500, "<Error><Code>InternalError</Code><Message>synthetic</Message></Error>");
                            return;
                        }
                        String entries = mode.equals("remaining") ? version("Version", key, "retained") : "";
                        if (mode.equals("too-many")) for (int n = 0; n < 9; n++) entries += version(
                            "Version",
                            key,
                            "v" + n
                        );
                        xml(exchange, 200, listing(key, entries));
                        return;
                    }
                    if (exchange.getRequestMethod().equals("DELETE")) {
                        mutations.incrementAndGet();
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    exchange.sendResponseHeaders(mode.equals("head-present") ? 200 : 403, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var failure = assertThrows(
                    ExternalDependencyUnavailableException.class,
                    () ->
                        storage(server).removeAndConfirm(
                            key,
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                        ),
                    mode
                );
                assertEquals("视频对象清理尚未核验", failure.getMessage());
                assertNull(failure.getCause());
                if (!mode.equals("remaining")) assertEquals(0, mutations.get(), mode);
                assertThrows(ExternalDependencyUnavailableException.class, () ->
                    storage(server).removeAndConfirm(key, System.nanoTime() - 1)
                );
            } finally {
                server.stop(0);
            }
        }
    }

    private static MinioVideoStorage storage(HttpServer server) {
        return new MinioVideoStorage(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "private-test",
            "synthetic-access",
            "synthetic-secret-for-test"
        );
    }

    private static String listing(String key, String entries) {
        return (
            "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>private-test</Name><Prefix>" +
            key +
            "</Prefix><KeyMarker/><VersionIdMarker/><MaxKeys>16</MaxKeys><IsTruncated>false</IsTruncated>" +
            entries +
            "</ListVersionsResult>"
        );
    }

    private static String version(String type, String key, String id) {
        return (
            "<" +
            type +
            "><Key>" +
            key +
            "</Key><VersionId>" +
            id +
            "</VersionId><IsLatest>false</IsLatest><LastModified>2026-10-17T00:00:00.000Z</LastModified>" +
            (type.equals("Version")
                ? "<ETag>synthetic</ETag><Size>16</Size><StorageClass>STANDARD</StorageClass>"
                : "") +
            "</" +
            type +
            ">"
        );
    }

    private static void xml(com.sun.net.httpserver.HttpExchange exchange, int status, String value)
        throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Test
    void fixedPrivatePrefixStreamsBytesAndRangeAndNeverPresignsOrReadsWholeFile() throws Exception {
        var requests = new ArrayList<String>();
        byte[] bytes = new byte[1024];
        java.util.Arrays.fill(bytes, (byte) 7);
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                if (
                    exchange.getRequestURI().getQuery() != null &&
                    exchange.getRequestURI().getQuery().contains("location")
                ) {
                    byte[] region =
                        "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>".getBytes(
                            StandardCharsets.UTF_8
                        );
                    exchange.sendResponseHeaders(200, region.length);
                    exchange.getResponseBody().write(region);
                    return;
                }
                if (exchange.getRequestMethod().equals("PUT")) {
                    assertArrayEquals(bytes, exchange.getRequestBody().readAllBytes());
                    exchange.getResponseHeaders().set("ETag", "\"synthetic-etag\"");
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    assertEquals("bytes=10-19", exchange.getRequestHeaders().getFirst("Range"));
                    exchange.getResponseHeaders().set("Content-Range", "bytes 10-19/1024");
                    exchange.sendResponseHeaders(206, 10);
                    exchange.getResponseBody().write(java.util.Arrays.copyOfRange(bytes, 10, 20));
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var storage = new MinioVideoStorage(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "private-test",
                "synthetic-access",
                "synthetic-secret-for-test"
            );
            Path file = directory.resolve("source.mp4");
            Files.write(file, bytes);
            storage.put("videos/42/test/source.mp4", file);
            try (var input = storage.open("videos/42/test/source.mp4", 10, 10)) {
                assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 10, 20), input.readAllBytes());
            }
            assertTrue(requests.contains("PUT /private-test/videos/42/test/source.mp4"));
            assertTrue(requests.contains("GET /private-test/videos/42/test/source.mp4"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void objectFaultIs503StyleDependencyFailureWithoutCredentialsOrRawUrl() throws Exception {
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var storage = new MinioVideoStorage(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "private-test",
                "synthetic-access",
                "synthetic-secret-for-test"
            );
            var failure = assertThrows(ExternalDependencyUnavailableException.class, () ->
                storage.open("videos/42/test/source.mp4", 0, 16)
            );
            assertEquals("视频对象读取暂时不可用", failure.getMessage());
            assertNull(failure.getCause());
        } finally {
            server.stop(0);
        }
    }
}
