package home;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@RestController
@SpringBootApplication
@EnableWebSocket
public class ServingApp implements WebSocketConfigurer {
  private static final int CHUNK_SIZE = 2799;

  private final String fileToDownload;

  public ServingApp(@Value("${file.to.download}") String fileToDownload) {
    this.fileToDownload = fileToDownload;
  }

  static void main(String[] args) {
    System.setProperty("file.to.download", "/tmp/msys64.7z");

    SpringApplication.run(ServingApp.class, args);
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
      int y = Integer.parseInt(parts[1]);

      byte[] fileBytes = java.nio.file.Files.readAllBytes(new File(fileToDownload).toPath());
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
  }
}
