package interview.pilot.knowledge.indexing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class KnowledgeDocumentParser {
  static final long MAX_DOCUMENT_SIZE = 10L * 1024 * 1024;
  private static final int MAX_EXTRACTED_CHARACTERS = 10 * 1024 * 1024;
  private static final long MAX_DOCX_ENTRY_SIZE = MAX_DOCUMENT_SIZE;
  private static final long MAX_DOCX_ENTRY_COUNT = 100;
  private static final long MAX_DOCX_TOTAL_SIZE = MAX_DOCUMENT_SIZE;
  private static final long MAX_DOCX_EXPANSION_RATIO = 100;
  private static final Map<String, Set<String>> SUPPORTED_TYPES = Map.of(
      "md", Set.of("text/markdown", "text/x-web-markdown", "text/plain"),
      "txt", Set.of("text/plain"),
      "pdf", Set.of("application/pdf"),
      "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
  private static final Pattern CONTROL_CHARACTERS =
      Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
  private static final Pattern TRAILING_HORIZONTAL_WHITESPACE = Pattern.compile("(?m)[ \\t]+$");
  private static final Pattern REPEATED_BLANK_LINES = Pattern.compile("\\n{3,}");

  public String parse(InputStream input, String filename, long size) {
    if (input == null) {
      throw new IllegalArgumentException("Knowledge document input is required");
    }
    String extension = extension(filename);
    if (size < 0 || size > MAX_DOCUMENT_SIZE) {
      throw new IllegalArgumentException("Knowledge document exceeds 10 MB");
    }
    byte[] content = readBounded(input);
    if (content.length == 0) {
      return "";
    }
    if (!SUPPORTED_TYPES.get(extension).contains(detectType(content, filename))) {
      throw new IllegalArgumentException("Knowledge document type is unsupported");
    }
    if ("docx".equals(extension)) {
      preflightDocx(content);
    }
    return normalize("docx".equals(extension) ? parseDocx(content) : parseContent(content, filename));
  }

  private static String extension(String filename) {
    if (filename == null) {
      throw new IllegalArgumentException("Knowledge document type is unsupported");
    }
    int separator = filename.lastIndexOf('.');
    if (separator < 0 || separator == filename.length() - 1) {
      throw new IllegalArgumentException("Knowledge document type is unsupported");
    }
    String extension = filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    if (!SUPPORTED_TYPES.containsKey(extension)) {
      throw new IllegalArgumentException("Knowledge document type is unsupported");
    }
    return extension;
  }

  private static byte[] readBounded(InputStream input) {
    try (input) {
      byte[] content = input.readNBytes((int) MAX_DOCUMENT_SIZE + 1);
      if (content.length > MAX_DOCUMENT_SIZE) {
        throw new IllegalArgumentException("Knowledge document exceeds 10 MB");
      }
      return content;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Knowledge document could not be read", exception);
    }
  }

  private static String detectType(byte[] content, String filename) {
    DefaultDetector detector = new DefaultDetector();
    try (InputStream input = TikaInputStream.get(content)) {
      MediaType type = detector.detect(input, metadata(filename));
      return type.toString();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Knowledge document type could not be detected", exception);
    }
  }

  private static String parseContent(byte[] content, String filename) {
    AutoDetectParser parser = new AutoDetectParser();
    BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
    ParseContext context = new ParseContext();
    context.set(Parser.class, parser);
    context.set(EmbeddedDocumentExtractor.class, new NoEmbeddedDocuments());
    context.set(PDFParserConfig.class, pdfParserConfig());

    try (InputStream input = new ByteArrayInputStream(content)) {
      parser.parse(input, handler, metadata(filename), context);
      return handler.toString();
    } catch (IOException | TikaException | SAXException exception) {
      throw new IllegalArgumentException("Knowledge document could not be parsed", exception);
    }
  }

  private static String parseDocx(byte[] content) {
    try (var document = new XWPFDocument(new ByteArrayInputStream(content))) {
      StringBuilder text = new StringBuilder();
      for (var paragraph : document.getParagraphs()) {
        appendDocxText(text, paragraph.getText());
      }
      for (var table : document.getTables()) {
        for (var row : table.getRows()) {
          for (var cell : row.getTableCells()) {
            appendDocxText(text, cell.getText());
          }
        }
      }
      return text.toString();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Knowledge document could not be parsed", exception);
    }
  }

  private static void appendDocxText(StringBuilder target, String next) {
    if (next == null || next.isEmpty()) {
      return;
    }
    int separator = target.isEmpty() ? 0 : 1;
    if ((long) target.length() + separator + next.length() > MAX_EXTRACTED_CHARACTERS) {
      throw new IllegalArgumentException("Knowledge document contains too much text");
    }
    if (separator == 1) {
      target.append('\n');
    }
    target.append(next);
  }

  private static PDFParserConfig pdfParserConfig() {
    PDFParserConfig config = new PDFParserConfig();
    config.setExtractInlineImages(false);
    config.setSortByPosition(true);
    config.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    return config;
  }

  private static Metadata metadata(String filename) {
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
    return metadata;
  }

  private static void preflightDocx(byte[] content) {
    long total = 0;
    int entries = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
        if (++entries > MAX_DOCX_ENTRY_COUNT || !isSafeDocxEntry(entry)) {
          throw new IllegalArgumentException("DOCX package is unsafe");
        }
        long entrySize = 0;
        byte[] buffer = new byte[8192];
        for (int read; (read = zip.read(buffer)) >= 0;) {
          entrySize += read;
          total += read;
          if (entrySize > MAX_DOCX_ENTRY_SIZE || total > MAX_DOCX_TOTAL_SIZE) {
            throw new IllegalArgumentException("DOCX package is too large");
          }
        }
        zip.closeEntry();
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("DOCX package could not be inspected", exception);
    }
    if (entries == 0 || total > Math.max(1L, content.length) * MAX_DOCX_EXPANSION_RATIO) {
      throw new IllegalArgumentException("DOCX package has an unsafe expansion ratio");
    }
  }

  private static boolean isSafeDocxEntry(ZipEntry entry) {
    String name = entry.getName();
    return name != null
        && !name.isBlank()
        && !name.startsWith("/")
        && !name.startsWith("\\")
        && !name.contains("../")
        && !name.contains("..\\")
        && (entry.getMethod() == ZipEntry.DEFLATED || entry.getMethod() == ZipEntry.STORED);
  }

  private static String normalize(String text) {
    String normalized = CONTROL_CHARACTERS.matcher(text).replaceAll("");
    normalized = normalized.replace("\uFFFD", "");
    normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
    normalized = TRAILING_HORIZONTAL_WHITESPACE.matcher(normalized).replaceAll("");
    return REPEATED_BLANK_LINES.matcher(normalized).replaceAll("\n\n").strip();
  }

  private static final class NoEmbeddedDocuments implements EmbeddedDocumentExtractor {
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
      return false;
    }

    @Override
    public void parseEmbedded(
        InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) {
      // Knowledge indexing intentionally ignores embedded files and images.
    }
  }
}
