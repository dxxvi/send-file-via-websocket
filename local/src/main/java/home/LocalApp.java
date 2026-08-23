package home;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SpringBootApplication
public class LocalApp {
  private static final Logger log = LoggerFactory.getLogger(LocalApp.class);

  private final Path fileToUpload;

  private volatile String sha256Cache;

  public LocalApp(@Value("${file.to.upload}") String fileToUpload) {
    this.fileToUpload = Path.of(fileToUpload);
  }

  static void main(String[] args) {
    if (Stream.of(args).noneMatch("file.to.upload"::equals)) {
      System.setProperty("file.to.upload", "");
      log.info("No file to upload. To change, set file.to.upload");
    }
    SpringApplication.run(LocalApp.class, args);
  }

  @Bean
  public Filter corsFilter() {
    return (request, response, chain) -> {
      HttpServletRequest req = (HttpServletRequest) request;
      HttpServletResponse res = (HttpServletResponse) response;
      res.setHeader("Access-Control-Allow-Origin", "*");
      res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
      res.setHeader("Access-Control-Allow-Headers", "*");
      res.setHeader("Access-Control-Expose-Headers", "Content-Length");
      res.setHeader("Access-Control-Allow-Private-Network", "true");
      if ("OPTIONS".equals(req.getMethod())) {
        res.setStatus(HttpServletResponse.SC_OK);
      } else {
        chain.doFilter(request, response);
      }
    };
  }

  @GetMapping("/file/info")
  public ResponseEntity<?> fileInfo() throws IOException {
    if (!Files.isRegularFile(fileToUpload)) {
      return notFound();
    }
    return ResponseEntity.ok(
        new FileInfo(fileToUpload.getFileName().toString(), Files.size(fileToUpload), sha256()));
  }

  @GetMapping("/file")
  public ResponseEntity<?> file() throws IOException {
    if (!Files.isRegularFile(fileToUpload)) {
      return notFound();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(Files.size(fileToUpload))
        .body(new InputStreamResource(Files.newInputStream(fileToUpload)));
  }

  private ResponseEntity<String> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body("file.to.upload is not set to an existing file");
  }

  private String sha256() {
    String cached = sha256Cache;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (sha256Cache == null) {
        sha256Cache = computeSha256();
      }
      return sha256Cache;
    }
  }

  private String computeSha256() {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (InputStream in = Files.newInputStream(fileToUpload)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  record FileInfo(String name, long size, String sha256) {}
}
