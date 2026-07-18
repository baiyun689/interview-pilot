package interview.pilot.resume.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.common.exception.BusinessException;

class ResumeTextExtractorTest {
  private static final String RESUME_TEXT =
      "Senior Java engineer with Spring Boot, PostgreSQL, Redis, messaging, testing, and cloud experience.";

  private final TextCleaningService cleaningService = new TextCleaningService();
  private final ResumeTextExtractor extractor = new ResumeTextExtractor(cleaningService);

  @Test
  void cleansUtf8TextAndNormalizesRepeatedBlankLines() {
    var file = file(
        "resume.txt",
        "text/plain",
        ("Java engineer\r\n\r\n\r\nSpring Boot developer with PostgreSQL, Redis, messaging, "
            + "testing, and cloud experience.").getBytes(StandardCharsets.UTF_8));

    assertThat(extractor.extract(file)).isEqualTo(
        "Java engineer\n\nSpring Boot developer with PostgreSQL, Redis, messaging, testing, and cloud experience.");
  }

  @Test
  void extractsPdfText() throws Exception {
    assertThat(extractor.extract(file("resume.pdf", "application/pdf", pdf(RESUME_TEXT))))
        .isEqualTo(RESUME_TEXT);
  }

  @Test
  void extractsDocxText() throws Exception {
    assertThat(extractor.extract(file(
        "resume.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        docx(RESUME_TEXT))))
        .isEqualTo(RESUME_TEXT);
  }

  @Test
  void rejectsEmptyDocument() {
    assertBusinessError(file("resume.txt", "text/plain", new byte[0]), "EMPTY_DOCUMENT");
  }

  @Test
  void rejectsUnsupportedExtensionEvenWhenMimeIsText() {
    assertBusinessError(
        file("resume.md", "text/plain", RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
        "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void rejectsMimeThatDoesNotMatchExtension() {
    assertBusinessError(
        file("resume.txt", "application/pdf", RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
        "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void rejectsContentThatDoesNotMatchDeclaredPdfType() {
    assertBusinessError(
        file("resume.pdf", "application/pdf", RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
        "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void acceptsBrowserOctetStreamOnlyWhenExtensionAndDetectedTypeAgree() {
    assertThat(extractor.extract(file(
        "resume.txt",
        "application/octet-stream",
        RESUME_TEXT.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(RESUME_TEXT);
  }

  @Test
  void acceptsTextMimeWithUtf8CharsetParameter() {
    assertThat(extractor.extract(file(
        "resume.txt",
        "text/plain;charset=UTF-8",
        RESUME_TEXT.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(RESUME_TEXT);
  }

  @Test
  void rejectsMalformedMimeAsUnsupportedMediaType() {
    assertBusinessError(
        file(
            "resume.txt",
            "text/plain; charset==UTF-8",
            RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
        "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void rejectsBrowserOctetStreamWithUnsupportedExtension() {
    assertBusinessError(
        file("resume.bin", "application/octet-stream", RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
        "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void rejectsFilesLargerThanTenMegabytesBeforeOpeningContent() throws Exception {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(10L * 1024 * 1024 + 1);

    assertThatThrownBy(() -> extractor.extract(file))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("FILE_TOO_LARGE"));
    verify(file, never()).getInputStream();
    verify(file, never()).getBytes();
  }

  @Test
  void rejectsTextShorterThanFiftyNonWhitespaceCharacters() {
    assertBusinessError(
        file("resume.txt", "text/plain", "Java Spring SQL".getBytes(StandardCharsets.UTF_8)),
        "DOCUMENT_TEXT_TOO_SHORT");
  }

  private void assertBusinessError(MockMultipartFile file, String code) {
    assertThatThrownBy(() -> extractor.extract(file))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(code));
  }

  private static MockMultipartFile file(String filename, String contentType, byte[] bytes) {
    return new MockMultipartFile("file", filename, contentType, bytes);
  }

  private static byte[] pdf(String text) throws Exception {
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      var page = new PDPage();
      document.addPage(page);
      try (var stream = new PDPageContentStream(document, page)) {
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
