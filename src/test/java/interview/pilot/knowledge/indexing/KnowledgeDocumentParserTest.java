package interview.pilot.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentParserTest {
  private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser();

  @Test
  void parsesMarkdownAndNormalizesItsText() {
    byte[] content = "# Java\r\n\r\nSpring\u0000 Boot  \r\n\r\n\r\nActuator".getBytes(StandardCharsets.UTF_8);

    String parsed = parser.parse(new ByteArrayInputStream(content), "guide.md", content.length);

    assertThat(parsed).isEqualTo("# Java\n\nSpring Boot\n\nActuator");
  }

  @Test
  void returnsEmptyTextForAnEmptyDocument() {
    assertThat(parser.parse(new ByteArrayInputStream(new byte[0]), "empty.txt", 0)).isEmpty();
  }

  @Test
  void determinesAnEmptyDocumentFromTheStreamRatherThanCallerMetadata() {
    byte[] content = "still parse this text".getBytes(StandardCharsets.UTF_8);

    assertThat(parser.parse(new ByteArrayInputStream(content), "guide.txt", 0))
        .isEqualTo("still parse this text");
  }

  @Test
  void extractsPdfTextWithoutNeedingOcr() throws Exception {
    byte[] content = pdf("Java PDF document");

    assertThat(parser.parse(new ByteArrayInputStream(content), "guide.pdf", content.length))
        .isEqualTo("Java PDF document");
  }

  @Test
  void extractsDocxTextWithoutParsingEmbeddedDocuments() throws Exception {
    byte[] content = docx("Java DOCX document");

    assertThat(parser.parse(new ByteArrayInputStream(content), "guide.docx", content.length))
        .isEqualTo("Java DOCX document");
  }

  @Test
  void rejectsUnsupportedFilenameTypesBeforeParsing() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), "payload.exe", 1));
  }

  private static byte[] pdf(String text) throws Exception {
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      try (var stream = new PDPageContentStream(document, document.getPage(0))) {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(40, 700);
        stream.showText(text);
        stream.endText();
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private static byte[] docx(String text) throws Exception {
    try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
      document.createParagraph().createRun().setText(text);
      document.write(output);
      return output.toByteArray();
    }
  }
}
