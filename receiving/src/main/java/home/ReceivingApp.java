package home;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class ReceivingApp {
  static void main(String[] args) {
    SpringApplication.run(ReceivingApp.class, args);
  }

  @CrossOrigin(origins = "*")
  @PostMapping("/reset")
  public void reset() {
    new File("/tmp/received_file").delete();
  }

  @CrossOrigin(origins = "*")
  @PostMapping("/upload-chunk")
  public void uploadChunk(@RequestBody byte[] chunk) throws IOException {
    File outputFile = new File("/tmp/received_file");
    try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
      fos.write(chunk);
    }
  }

  @CrossOrigin(origins = "*")
  @PostMapping("/upload-chunks")
  public void uploadChunks(@RequestBody java.util.List<String> chunks) throws IOException {
    File outputFile = new File("/tmp/received_file");
    try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
      for (String chunk : chunks) {
        if (chunk != null && !chunk.isEmpty()) {
          byte[] decodedBytes = java.util.Base64.getDecoder().decode(chunk);
          fos.write(decodedBytes);
        }
      }
    }
  }
}
