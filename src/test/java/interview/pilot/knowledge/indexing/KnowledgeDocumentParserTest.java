package interview.pilot.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    byte[] content = "# Java\r\n\r\nSpring Boot  \r\n\r\n\r\nActuator".getBytes(StandardCharsets.UTF_8);

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

  @Test
  void rejectsBinaryAndMarkupContentPretendingToBePlainTextOrMarkdown() throws Exception {
    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream(pdf("not text")), "fake.txt", 100));
    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream(docx("not markdown")), "fake.md", 100));
    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream(zip()), "fake.txt", 100));
    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream("<h1>HTML</h1>".getBytes(StandardCharsets.UTF_8)),
            "fake.md", 13));
  }

  @Test
  void rejectsAnOverInflatedDocxPackage() throws Exception {
    byte[] bomb = docxWithLargeDocumentXml();

    assertThatIllegalArgumentException().isThrownBy(
        () -> parser.parse(new ByteArrayInputStream(bomb), "bomb.docx", bomb.length));
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

  private static byte[] zip() throws IOException {
    try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("payload.txt"));
      zip.write("payload".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      return output.toByteArray();
    }
  }

  private static byte[] docxWithLargeDocumentXml() throws IOException {
    String document = """
        <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
        <w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">
        <w:body><w:p><w:r><w:t>""" + "A".repeat(512 * 1024) + """
        </w:t></w:r></w:p></w:body></w:document>
        """;
    try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
      writeEntry(zip, "[Content_Types].xml", """
          <Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">
          <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>
          <Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>
          </Types>
          """);
      writeEntry(zip, "_rels/.rels", """
          <Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
          <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>
          </Relationships>
          """);
      writeEntry(zip, "word/document.xml", document);
      return output.toByteArray();
    }
  }

  private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
