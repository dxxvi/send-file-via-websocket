package home;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
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

  private final Map<WebSocketSession, OutputStream> uploads = new ConcurrentHashMap<>();

  private byte[] fileBytes = null;

  public ServingApp(
      @Value("${file.to.download}") String fileToDownload,
      @Value("${file.to.upload.dir}") String uploadDir) {
    this.fileToDownload = fileToDownload;
    this.uploadDir = uploadDir;
  }

  static void main(String[] args) {
    System.setProperty("file.to.download", "/tmp/msys64.7z");
    System.setProperty("file.to.upload.dir", "/home/ubuntu");

    SpringApplication.run(ServingApp.class, args);
  }

  @Bean
  public ServletServerContainerFactoryBean webSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
    container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
    return container;
  }

  @GetMapping(path = "/{info}.png", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> getInfo(@PathVariable String info) {
    try {
      File file = new File(fileToDownload);
      long fileSizeInBytes = file.length();
      long n = (fileSizeInBytes + CHUNK_SIZE - 1) / CHUNK_SIZE;

      String text =
          String.format("File %s size %d bytes, %d chunks", fileToDownload, fileSizeInBytes, n);

      BufferedImage image = new BufferedImage(800, 100, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2d = image.createGraphics();
      g2d.setColor(Color.WHITE);
      g2d.fillRect(0, 0, 800, 100);
      g2d.setColor(Color.BLACK);
      g2d.setFont(new Font("Arial", Font.PLAIN, 20));
      g2d.drawString(text, 10, 50);
      g2d.dispose();

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(image, "png", baos);

      ResponseCookie cookie =
          ResponseCookie.from("chunks", String.valueOf(n)).httpOnly(false).build();

      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, cookie.toString())
          .body(baos.toByteArray());
    } catch (Exception e) {
      return ResponseEntity.status(500).build();
    }
  }

  @GetMapping(path = "/{chunks}.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
  public ResponseEntity<byte[]> getChunks(@PathVariable String chunks) {
    try {
      String[] parts = chunks.split("-");
      int x = Integer.parseInt(parts[0]);
      int y = parts.length == 1 ? x : Integer.parseInt(parts[1]);

      if (fileBytes == null)
        fileBytes = java.nio.file.Files.readAllBytes(new File(fileToDownload).toPath());

      ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();

      for (int i = x; i <= y; i++) {
        int startIndex = (i - 1) * CHUNK_SIZE;
        if (startIndex >= fileBytes.length) {
          continue;
        }
        int endIndex = Math.min(startIndex + CHUNK_SIZE, fileBytes.length);

        byte[] chunkBytes = new byte[endIndex - startIndex];
        System.arraycopy(fileBytes, startIndex, chunkBytes, 0, chunkBytes.length);

        String base64Chunk = java.util.Base64.getEncoder().encodeToString(chunkBytes);

        ResponseCookie cookie =
            ResponseCookie.from("chunk-" + i, base64Chunk).httpOnly(false).build();

        responseBuilder.header(HttpHeaders.SET_COOKIE, cookie.toString());
      }

      String text = String.format("chunk %d - %d", x, y);
      BufferedImage image = new BufferedImage(800, 100, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2d = image.createGraphics();
      g2d.setColor(Color.WHITE);
      g2d.fillRect(0, 0, 800, 100);
      g2d.setColor(Color.BLACK);
      g2d.setFont(new Font("Arial", Font.PLAIN, 20));
      g2d.drawString(text, 10, 50);
      g2d.dispose();

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(image, "jpeg", baos);

      return responseBuilder.body(baos.toByteArray());
    } catch (Exception e) {
      return ResponseEntity.status(500).build();
    }
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(
        new BinaryWebSocketHandler() {
          @Override
          public void afterConnectionEstablished(WebSocketSession session) throws Exception {
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

  private final class UploadHandler extends AbstractWebSocketHandler {
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
      log.debug("Upload session {} opened", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
        throws IOException {
      if (uploads.containsKey(session)) {
        log.warn("Session {} already has an upload in progress", session.getId());
        session.close(CloseStatus.POLICY_VIOLATION);
        return;
      }

      String name = Paths.get(message.getPayload()).getFileName().toString();
      Path target = Paths.get(uploadDir).resolve(name);

      Files.deleteIfExists(target);

      OutputStream out =
          new BufferedOutputStream(
              Files.newOutputStream(
                  target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
      uploads.put(session, out);

      log.debug("Session {} uploading to {}", session.getId(), target);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
        throws IOException {
      OutputStream out = uploads.get(session);
      if (out == null) {
        return;
      }

      byte[] chunk = new byte[message.getPayloadLength()];
      message.getPayload().get(chunk);
      out.write(chunk);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      closeStream(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
      log.error("Transport error on session {}", session.getId(), exception);
      closeStream(session);
    }

    private void closeStream(WebSocketSession session) {
      OutputStream out = uploads.remove(session);
      if (out == null) {
        return;
      }

      try {
        out.flush();
        out.close();
        log.debug("Finalized upload for session {}", session.getId());
      } catch (IOException e) {
        log.error("Failed to finalize upload for session {}", session.getId(), e);
      }
    }
  }
}
