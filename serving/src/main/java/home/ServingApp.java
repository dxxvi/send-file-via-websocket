package home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@RestController
@SpringBootApplication
@EnableWebSocket
public class ServingApp implements WebSocketConfigurer {
  private static final Logger log = LoggerFactory.getLogger(ServingApp.class);

  private static final int CHUNK_SIZE = 1_999_799;

  private final String fileToDownload;

  private final String uploadDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final Map<WebSocketSession, UploadState> uploadStates = new ConcurrentHashMap<>();

  public ServingApp(
      @Value("${file.to.download}") String fileToDownload,
      @Value("${upload.to.dir}") String uploadDir) {
    this.fileToDownload = fileToDownload;
    this.uploadDir = uploadDir;
  }

  static void main(String[] args) {
    if (Stream.of(args).noneMatch("file.to.download"::equals)) {
      System.setProperty("file.to.download", "/tmp/msys64.7z");
      log.info("File to download is /tmp/msys64.7z. To change, set file.to.download");
    }
    if (Stream.of(args).noneMatch("upload.to.dir"::equals)) {
      System.setProperty("upload.to.dir", "/home/ubuntu");
      log.info("Upload to dir /home/ubuntu. To change, set upload.to.dir");
    }

    SpringApplication.run(ServingApp.class, args);
  }

  @Bean
  public ServletServerContainerFactoryBean webSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
    container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
    return container;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(
        new BinaryWebSocketHandler() {
          @Override
          public void afterConnectionEstablished(@Nonnull WebSocketSession session)
              throws Exception {
            File file = new File(fileToDownload);
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
              byte[] buffer = new byte[CHUNK_SIZE];
              int bytesRead;
              while ((bytesRead = in.read(buffer)) != -1) {
                if (bytesRead == CHUNK_SIZE) {
                  session.sendMessage(new BinaryMessage(buffer));
                } else {
                  byte[] lastChunk = new byte[bytesRead];
                  System.arraycopy(buffer, 0, lastChunk, 0, bytesRead);
                  session.sendMessage(new BinaryMessage(lastChunk));
                }
              }
            }
            session.close();
          }
        },
        "/ws-download");
    registry.addHandler(new UploadHandler(), "/ws-upload");
  }

  private static String sanitizeName(String raw) {
    String name = raw.replaceAll("\\p{Cntrl}", "").trim();
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
      return null;
    }
    return name;
  }

  private static final class UploadState {
    final String name;

    final long size;

    final String sha256;

    final Path part;

    final DigestOutputStream out;

    long written;

    UploadState(Path dir, String name, long size, String sha256) throws IOException {
      this.name = name;
      this.size = size;
      this.sha256 = sha256;
      this.part = dir.resolve(UUID.randomUUID() + "." + name + ".part");
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
      this.out =
          new DigestOutputStream(
              new BufferedOutputStream(
                  Files.newOutputStream(
                      part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)),
              digest);
    }

    Path finalPath() {
      return part.getParent().resolve(name);
    }

    boolean append(ByteBuffer payload) throws IOException {
      int remaining = payload.remaining();
      if (written + remaining > size) {
        return false;
      }
      byte[] chunk = new byte[remaining];
      payload.get(chunk);
      out.write(chunk, 0, chunk.length);
      written += remaining;
      return true;
    }

    boolean complete() throws IOException {
      out.flush();
      return written == size
          && HexFormat.of().formatHex(out.getMessageDigest().digest()).equals(sha256);
    }

    void closeStream() {
      try {
        out.close();
      } catch (IOException e) {
        // ignore
      }
    }

    void discard() {
      closeStream();
      try {
        Files.deleteIfExists(part);
      } catch (IOException e) {
        // ignore
      }
    }
  }

  private class UploadHandler extends BinaryWebSocketHandler {

    @Override
    public void afterConnectionClosed(
        @Nonnull WebSocketSession session, @Nonnull CloseStatus status) {
      UploadState state = uploadStates.remove(session);
      if (state != null) {
        state.discard();
        log.info("upload of {} aborted, deleted {}", state.name, state.part);
      }
    }

    @Override
    public void handleBinaryMessage(
        @Nonnull WebSocketSession session, @Nonnull BinaryMessage message) {
      UploadState state = uploadStates.get(session);
      if (state == null) {
        fail(session, "binary frame before start");
        return;
      }
      try {
        if (!state.append(message.getPayload())) {
          fail(session, "exceeds declared size " + state.size);
        }
      } catch (IOException e) {
        fail(session, "failed to write: " + e);
      }
    }

    @Override
    public void handleTextMessage(@Nonnull WebSocketSession session, @Nonnull TextMessage message) {
      JsonNode json;
      try {
        json = objectMapper.readTree(message.getPayload());
      } catch (IOException e) {
        fail(session, "bad json: " + e);
        return;
      }
      switch (json.path("action").asText("")) {
        case "start" -> startUpload(session, json);
        case "finish" -> finishUpload(session);
        default -> fail(session, "unknown action");
      }
    }

    private void startUpload(WebSocketSession session, JsonNode json) {
      String name = sanitizeName(json.path("name").asText(""));
      long size = json.path("size").asLong(-1);
      String sha256 = json.path("sha256").asText("").toLowerCase(Locale.ROOT);
      if (name == null) {
        fail(session, "invalid file name");
        return;
      }
      if (size < 0) {
        fail(session, "invalid size");
        return;
      }
      if (!sha256.matches("[0-9a-f]{64}")) {
        fail(session, "invalid sha256");
        return;
      }
      try {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        uploadStates.put(session, new UploadState(dir, name, size, sha256));
        log.info("upload started {} {} bytes", name, size);
      } catch (IOException e) {
        fail(session, "cannot create temp file: " + e);
      }
    }

    private void finishUpload(WebSocketSession session) {
      UploadState state = uploadStates.remove(session);
      if (state == null) {
        fail(session, "no upload in progress");
        return;
      }
      try {
        if (state.complete()) {
          state.closeStream();
          Files.move(state.part, state.finalPath(), StandardCopyOption.REPLACE_EXISTING);
          log.info("upload verified {} -> {}", state.part, state.finalPath());
          reply(session, "done", null);
        } else {
          state.discard();
          reply(session, "error", "sha256 or size mismatch");
        }
      } catch (IOException e) {
        state.discard();
        reply(session, "error", "finish failed: " + e);
      }
    }

    private void fail(WebSocketSession session, String message) {
      log.info("upload failed: {}", message);
      UploadState state = uploadStates.remove(session);
      if (state != null) {
        state.discard();
      }
      reply(session, "error", message);
      try {
        session.close(CloseStatus.POLICY_VIOLATION);
      } catch (IOException e) {
        log.debug("close failed: {}", e.toString());
      }
    }

    private void reply(WebSocketSession session, String action, String errorMessage) {
      ObjectNode json = objectMapper.createObjectNode();
      json.put("action", action);
      if (errorMessage != null) {
        json.put("message", errorMessage);
      }
      try {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(json)));
      } catch (IOException e) {
        log.info("failed to send {}: {}", action, e.toString());
      }
    }
  }
}
