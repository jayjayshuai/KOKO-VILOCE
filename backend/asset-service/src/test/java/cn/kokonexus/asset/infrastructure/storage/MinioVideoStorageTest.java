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
