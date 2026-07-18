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
    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_801);
  }

  @Test
  void retainsTwoHundredCharactersOfOverlapForBoundarySizedPlainText() {
    String text = "a".repeat(1_600) + "b".repeat(20);

    List<String> chunks = splitter.split(text);

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0)).hasSize(1_600);
    assertThat(chunks.get(1)).startsWith("a".repeat(200));
  }

  @Test
  void splitsChineseSentencesAndIgnoresBlankText() {
    assertThat(splitter.split("  \r\n\r\n ")).isEmpty();

    List<String> chunks = splitter.split("第一句。".repeat(500));

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.contains("。"));
  }
}
