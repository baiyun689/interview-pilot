package interview.pilot.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RecursiveTextSplitterTest {
  private final RecursiveTextSplitter splitter = new RecursiveTextSplitter();

  @Test
  void splitsMarkdownHeadingsBeforeFallingBackToCharacterBoundaries() {
    String text = "# Java\n\n" + "Spring Boot testing. ".repeat(100)
        + "\n\n## PostgreSQL\n\n" + "Query planning. ".repeat(100);

    List<String> chunks = splitter.split(text);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).anyMatch(chunk -> chunk.contains("## PostgreSQL"));
    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_600);
  }

  @Test
  void retainsTwoHundredCharactersOfOverlapForBoundarySizedPlainText() {
    String text = "a".repeat(1_600) + "b".repeat(20);

    List<String> chunks = splitter.split(text);

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0)).hasSize(1_394);
    assertThat(chunks.get(1)).startsWith("a".repeat(200));
    assertThat(chunks.get(1)).hasSizeLessThanOrEqualTo(1_600);
    assertThat(chunks.get(1).charAt(200)).isEqualTo(' ');
    assertThat(chunks.get(0) + chunks.get(1).substring(201)).isEqualTo(text);
  }

  @Test
  void keepsSemanticSeparatorsBetweenOverlapAndNewContent() {
    String text = "word ".repeat(300) + "\n\nParagraph two. ".repeat(200);

    List<String> chunks = splitter.split(text);

    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_600);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).doesNotContain("wordParagraph"));
    assertThat(chunks).anyMatch(chunk -> chunk.contains("\n\nParagraph two."));
  }

  @Test
  void splitsChineseSentencesAndIgnoresBlankText() {
    assertThat(splitter.split("  \r\n\r\n ")).isEmpty();

    List<String> chunks = splitter.split("第一句。".repeat(500));

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.contains("。"));
  }

  @Test
  void neverSplitsASurrogatePairAtTheChunkBoundary() {
    String text = "a".repeat(1_399) + "😀" + "b".repeat(1_400);

    List<String> chunks = splitter.split(text);

    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_600);
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
      assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
    });
  }
}
