package interview.pilot.resume.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import interview.pilot.common.exception.BusinessException;

@Service
public class ResumeTextExtractor {
  static final long MAX_UPLOAD_SIZE = 10L * 1024 * 1024;
  private static final int MAX_EXTRACTED_CHARACTERS = 10 * 1024 * 1024;
  private static final int MIN_NON_WHITESPACE_CHARACTERS = 50;
  private static final Map<String, String> SUPPORTED_TYPES = Map.of(
      "pdf", "application/pdf",
      "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "txt", "text/plain");

  private final TextCleaningService cleaningService;

  public ResumeTextExtractor(TextCleaningService cleaningService) {
    this.cleaningService = cleaningService;
  }

  public String extract(MultipartFile file) {
    if (file == null || file.getSize() == 0) {
      throw error("EMPTY_DOCUMENT", "The uploaded document is empty", HttpStatus.UNPROCESSABLE_CONTENT);
    }
    if (file.getSize() > MAX_UPLOAD_SIZE) {
      throw error("FILE_TOO_LARGE", "The uploaded document exceeds 10 MB", HttpStatus.CONTENT_TOO_LARGE);
    }

    String filename = file.getOriginalFilename();
    String expectedType = expectedType(filename);
    validateDeclaredType(file.getContentType(), expectedType);

    byte[] content = readBounded(file);
    String detectedType = detectType(content, filename);
    if (!expectedType.equals(detectedType)) {
      throw unsupportedType();
    }

    String cleaned = cleaningService.cleanText(parse(content, filename));
    if (cleaned.isEmpty()) {
      throw error("EMPTY_DOCUMENT", "The document contains no extractable text",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }
    long nonWhitespace = cleaned.codePoints().filter(value -> !Character.isWhitespace(value)).count();
    if (nonWhitespace < MIN_NON_WHITESPACE_CHARACTERS) {
      throw error("DOCUMENT_TEXT_TOO_SHORT", "The document contains too little text",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }
    return cleaned;
  }

  private static String expectedType(String filename) {
    if (filename == null) {
      throw unsupportedType();
    }
    int separator = filename.lastIndexOf('.');
    if (separator < 0 || separator == filename.length() - 1) {
      throw unsupportedType();
    }
    String extension = filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    String type = SUPPORTED_TYPES.get(extension);
    if (type == null) {
      throw unsupportedType();
    }
    return type;
  }

  private static void validateDeclaredType(String declaredType, String expectedType) {
    if (declaredType == null) {
      throw unsupportedType();
    }
    org.springframework.http.MediaType declared;
    try {
      declared = org.springframework.http.MediaType.parseMediaType(declaredType);
    } catch (InvalidMediaTypeException exception) {
      throw unsupportedType();
    }
    org.springframework.http.MediaType expected =
        org.springframework.http.MediaType.parseMediaType(expectedType);
    if (!sameTypeAndSubtype(declared, expected)
        && !sameTypeAndSubtype(declared, org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)) {
      throw unsupportedType();
    }
  }

  private static boolean sameTypeAndSubtype(
      org.springframework.http.MediaType first,
      org.springframework.http.MediaType second) {
    return first.getType().equalsIgnoreCase(second.getType())
        && first.getSubtype().equalsIgnoreCase(second.getSubtype());
  }

  private static byte[] readBounded(MultipartFile file) {
    try (InputStream stream = file.getInputStream()) {
      byte[] content = stream.readNBytes((int) MAX_UPLOAD_SIZE + 1);
      if (content.length > MAX_UPLOAD_SIZE) {
        throw error("FILE_TOO_LARGE", "The uploaded document exceeds 10 MB",
            HttpStatus.CONTENT_TOO_LARGE);
      }
      return content;
    } catch (IOException exception) {
      throw error("DOCUMENT_PARSE_FAILED", "The document could not be read", HttpStatus.BAD_REQUEST);
    }
  }

  private static String detectType(byte[] content, String filename) {
    var parser = new AutoDetectParser();
    var metadata = metadata(filename);
    try (var stream = org.apache.tika.io.TikaInputStream.get(content)) {
      MediaType mediaType = parser.getDetector().detect(stream, metadata);
      return mediaType.toString();
    } catch (IOException exception) {
      throw error("DOCUMENT_PARSE_FAILED", "The document type could not be detected",
          HttpStatus.BAD_REQUEST);
    }
  }

  private static String parse(byte[] content, String filename) {
    var parser = new AutoDetectParser();
    var handler = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
    var context = new ParseContext();
    context.set(Parser.class, parser);
    context.set(EmbeddedDocumentExtractor.class, new NoEmbeddedDocuments());

    var pdf = new PDFParserConfig();
    pdf.setExtractInlineImages(false);
    pdf.setSortByPosition(true);
    pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    context.set(PDFParserConfig.class, pdf);

    try (var stream = new ByteArrayInputStream(content)) {
      parser.parse(stream, handler, metadata(filename), context);
      return handler.toString();
    } catch (IOException | TikaException | SAXException exception) {
      throw error("DOCUMENT_PARSE_FAILED", "The document could not be parsed", HttpStatus.BAD_REQUEST);
    }
  }

  private static Metadata metadata(String filename) {
    var metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
    return metadata;
  }

  private static BusinessException unsupportedType() {
    return error("UNSUPPORTED_FILE_TYPE", "Only PDF, DOCX, and TXT documents are supported",
        HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  private static BusinessException error(String code, String message, HttpStatus status) {
    return new BusinessException(code, message, status);
  }

  private static final class NoEmbeddedDocuments implements EmbeddedDocumentExtractor {
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
      return false;
    }

    @Override
    public void parseEmbedded(
        InputStream stream,
        ContentHandler handler,
        Metadata metadata,
        boolean outputHtml) {
      // Resume analysis intentionally ignores embedded files and images.
    }
  }
}
