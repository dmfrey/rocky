package com.example.clientragdemo.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

// A ~100 MB multipart upload succeeds against the embedded Tomcat, and an oversized one is a clean
// 413 rather than a connection reset. Ported from dmfrey/envoy.
//
// **Real Tomcat, real file.** MockMvc enforces none of this - the limits live in Tomcat's multipart
// parsing and in `max-swallow-size`, and the reset only happens on a real socket. The properties come
// from the **shipped** `src/main/resources/application.yaml`, read off disk, because
// `src/test/resources/application.yml` lowers the multipart limits to 100KB for every
// `@SpringBootTest`. A bare application with only the web auto-configurations keeps it to one Tomcat
// and no Testcontainers. Gradle runs tests with `backend/` as the working directory, hence the
// relative path.
//
// The sizes are binary, like Spring's `DataSize`: `100MB` is 104,857,600 bytes.
class MultipartUploadLimitsTest {

    private static final String MAIN_CONFIG = "file:src/main/resources/application.yaml";
    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path spill;

    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach
    void startTomcat() {
        context = new SpringApplicationBuilder( UploadApplication.class )
                .web( WebApplicationType.SERVLET )
                .properties( "spring.config.location=" + MAIN_CONFIG, "server.port=0",
                        "spring.main.banner-mode=off", "spring.servlet.multipart.location=" + spill )
                .run();
        port = ( (ServletWebServerApplicationContext) context ).getWebServer().getPort();
    }

    @AfterEach
    void stopTomcat() {
        context.close();
    }

    // "~100 MB" the way an operator means it: a 100,000,000-byte file, which is under the 100MB
    // (104,857,600-byte) per-file limit and well inside the 105MB request limit.
    @Test
    void aHundredMegabyteUploadSucceeds() throws Exception {
        var response = upload( 100_000_000L );

        assertThat( response.statusCode() ).isEqualTo( 200 );
        assertThat( response.body() ).isEqualTo( "100000000" );
    }

    // 110 MiB is past max-request-size, and well inside max-swallow-size - so the client reads a 413
    // from GlobalExceptionHandler instead of having the connection reset mid-send.
    @Test
    void anOversizedUploadIsAnswered413WithTheLimitNamed() throws Exception {
        var response = upload( 110 * MIB );

        assertThat( response.statusCode() ).isEqualTo( 413 );
        assertThat( response.body() ).contains( "File exceeds the maximum upload size" );
    }

    private java.net.http.HttpResponse<String> upload( long fileBytes ) throws Exception {
        var boundary = "upload100mb";
        var head = ( "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" ).getBytes( StandardCharsets.UTF_8 );
        var tail = ( "\r\n--" + boundary + "--\r\n" ).getBytes( StandardCharsets.UTF_8 );
        var length = head.length + fileBytes + tail.length;
        var request = HttpRequest.newBuilder( URI.create( "http://localhost:" + port + "/upload" ) )
                .header( "Content-Type", "multipart/form-data; boundary=" + boundary )
                .POST( BodyPublishers.fromPublisher( BodyPublishers.ofInputStream(
                        () -> new GeneratedBody( head, fileBytes, tail ) ), length ) )
                .build();
        return HttpClient.newBuilder().version( HttpClient.Version.HTTP_1_1 ).build()
                .send( request, BodyHandlers.ofString() );
    }

    // The head, `fileBytes` zero bytes, then the tail - generated, never held, so the test JVM
    // does not need 100 MB of heap to send 100 MB.
    private static final class GeneratedBody extends InputStream {

        private final byte[] head;
        private final long fileBytes;
        private final byte[] tail;
        private long position;

        GeneratedBody( byte[] head, long fileBytes, byte[] tail ) {
            this.head = head;
            this.fileBytes = fileBytes;
            this.tail = tail;
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            return read( one, 0, 1 ) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read( byte[] buffer, int offset, int length ) {
            var total = head.length + fileBytes + tail.length;
            if ( position >= total ) {
                return -1;
            }
            var count = (int) Math.min( length, total - position );
            for ( var i = 0; i < count; ) {
                var at = position + i;
                if ( at < head.length ) {
                    var run = (int) Math.min( count - i, head.length - at );
                    System.arraycopy( head, (int) at, buffer, offset + i, run );
                    i += run;
                } else if ( at < head.length + fileBytes ) {
                    var run = (int) Math.min( count - i, head.length + fileBytes - at );
                    Arrays.fill( buffer, offset + i, offset + i + run, (byte) 0 );
                    i += run;
                } else {
                    var run = (int) Math.min( count - i, total - at );
                    System.arraycopy( tail, (int) ( at - head.length - fileBytes ), buffer, offset + i, run );
                    i += run;
                }
            }
            position += count;
            return count;
        }

    }

    @Configuration( proxyBeanMethods = false )
    @ImportAutoConfiguration( {
            TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, MultipartAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class
    } )
    @EnableConfigurationProperties( ServerProperties.class )
    @Import( { GlobalExceptionHandler.class, UploadController.class } )
    static class UploadApplication {
    }

    @RestController
    static class UploadController {

        @PostMapping( "/upload" )
        String upload( @RequestParam( "file" ) MultipartFile file ) {
            return Long.toString( file.getSize() );
        }

    }

}
