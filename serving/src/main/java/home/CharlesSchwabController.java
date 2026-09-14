package home;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CharlesSchwabController {
  @GetMapping("/charles-schwab")
  public ResponseEntity<String> charles(@RequestParam Map<String, String> requestParams) {
    String tableRows =
        requestParams.entrySet().stream()
            .map(
                entry ->
                    "<tr><td>" + entry.getKey() + "</td><td>" + entry.getValue() + "</td></tr>")
            .collect(Collectors.joining("\n"));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(
            """
            <!doctype html>
            <html style="font-size: 24px">
            <head>
              <style>
                table { table-collapse: collapse }
                td { padding: .5rem; font-family: monospace }
              </style>
            </head>
            <body>
              <table>%s</table>
            </body>
            </html>"""
                .formatted(tableRows));
  }
}
