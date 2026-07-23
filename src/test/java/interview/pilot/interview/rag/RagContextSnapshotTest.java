package interview.pilot.interview.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RagContextSnapshotTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void notConfiguredIsEmpty() {
    var snapshot = RagContextSnapshot.notConfigured();
    assertThat(snapshot.status()).isEqualTo(RagStatus.NOT_CONFIGURED);
    assertThat(snapshot.chunks()).isEmpty();
    assertThat(snapshot.query()).isEmpty();
  }

  @Test
  void roundTripsWithTwoChunks() throws Exception {
    var chunk1 = new RagContextSnapshot.Chunk(
        "pt-1", UUID.randomUUID(), "notes.md", 0, 0.85, "Spring事务传播");
    var chunk2 = new RagContextSnapshot.Chunk(
        "pt-2", UUID.randomUUID(), "notes.md", 1, 0.72, "MyBatis缓存");
    var snapshot = new RagContextSnapshot(
        RagStatus.RETRIEVED, "Spring事务", "text-embedding-v3",
        List.of(chunk1, chunk2), null);

    String json = objectMapper.writeValueAsString(snapshot);
    RagContextSnapshot parsed = objectMapper.readValue(json, RagContextSnapshot.class);

    assertThat(parsed.status()).isEqualTo(RagStatus.RETRIEVED);
    assertThat(parsed.chunks()).hasSize(2);
    assertThat(parsed.chunks().getFirst().pointId()).isEqualTo("pt-1");
    assertThat(parsed.chunks().getFirst().score()).isEqualTo(0.85);
  }

  @Test
  void rejectsMoreThanSixChunks() {
    var chunks = new java.util.ArrayList<RagContextSnapshot.Chunk>();
    for (int i = 0; i < 7; i++) {
      chunks.add(new RagContextSnapshot.Chunk(
          "pt-" + i, UUID.randomUUID(), "f.md", i, 0.5, "content " + i));
    }
    assertThatThrownBy(() -> new RagContextSnapshot(
        RagStatus.RETRIEVED, "q", "v3", chunks, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too many RAG chunks");
  }

  @Test
  void rejectedRetrievedMustHaveChunks() {
    assertThatThrownBy(() -> new RagContextSnapshot(
        RagStatus.RETRIEVED, "q", "v3", List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retrieved snapshot requires chunks");
  }
}
