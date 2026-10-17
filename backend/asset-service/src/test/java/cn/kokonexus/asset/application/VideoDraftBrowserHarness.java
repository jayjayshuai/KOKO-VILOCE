package cn.kokonexus.asset.application;

import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import cn.kokonexus.asset.interfaces.AssetExceptionHandler;
import cn.kokonexus.asset.interfaces.AssetGatewayFilter;
import cn.kokonexus.asset.interfaces.VideoDraftController;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 实际Tomcat/MVC/SQL/处理器接入浏览器；身份注入与本地对象存储明确为隔离夹具。 */
public final class VideoDraftBrowserHarness {

    @Configuration
    @EnableWebMvc
    static class Mvc implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

        @Bean
        StandardServletMultipartResolver multipartResolver() {
            return new StandardServletMultipartResolver();
        }

        @Override
        public void extendMessageConverters(
            java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters
        ) {
            for (var converter : converters)
                if (
                    converter instanceof
                        org.springframework.http.converter.json.MappingJackson2HttpMessageConverter jackson
                ) jackson.setObjectMapper(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                );
        }
    }

    public static void run(
        VideoDraftService upload,
        VideoDraftState state,
        VideoStorage storage,
        VideoDraftWorker worker,
        Path work,
        Path source
    ) throws Exception {
        String script = System.getenv("VIDEO_CHECK_BROWSER_SCRIPT"),
            dist = System.getenv("VIDEO_CHECK_DIST");
        if (script == null || dist == null) throw new IllegalArgumentException(
            "Actual browser harness inputs required"
        );
        String key = "synthetic-browser-gateway-" + "x".repeat(32);
        var context = new AnnotationConfigWebApplicationContext();
        context.register(
            Mvc.class,
            VideoDraftController.class,
            GlobalExceptionHandler.class,
            AssetExceptionHandler.class
        );
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("videoUpload", upload);
            factory.registerSingleton("videoState", state);
            factory.registerSingleton("videoStorage", storage);
        });
        var factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(work.resolve("tomcat").toFile());
        var server = factory.getWebServer(servletContext -> {
            context.setServletContext(servletContext);
            var servlet = servletContext.addServlet("dispatcher", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
            servlet.setMultipartConfig(new MultipartConfigElement(work.toString(), 33554432, 34603008, 0));
            var encoding = servletContext.addFilter(
                "utf8",
                new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true)
            );
            encoding.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            var filter = servletContext.addFilter("trustedTestGateway", new AssetGatewayFilter(key));
            filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        });
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            server.start();
            scheduler.scheduleWithFixedDelay(worker::work, 0, 1, TimeUnit.SECONDS);
            var builder = new ProcessBuilder("node", script)
                .directory(Path.of(script).toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(work.resolve("browser.private.log").toFile());
            Path browserTemp = work.resolve("browser-temp");
            java.nio.file.Files.createDirectories(browserTemp);
            for (String variable : java.util.List.of("TEMP", "TMP", "TMPDIR"))
                builder.environment().put(variable, browserTemp.toString());
            builder.environment().put("VIDEO_CHECK_PORT", String.valueOf(server.getPort()));
            builder.environment().put("VIDEO_CHECK_GATEWAY_KEY", key);
            builder.environment().put("VIDEO_CHECK_DIST", dist);
            builder.environment().put("VIDEO_CHECK_SOURCE", source.toString());
            builder.environment().put("VIDEO_CHECK_EVIDENCE", work.resolve("browser").toString());
            var browser = builder.start();
            try {
                if (!browser.waitFor(180, TimeUnit.SECONDS) || browser.exitValue() != 0) throw new AssertionError(
                    "Actual video browser failed; inspect private evidence"
                );
            } finally {
                if (browser.isAlive()) browser.destroyForcibly();
            }
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            server.stop();
            server.destroy();
            context.close();
        }
    }
}
