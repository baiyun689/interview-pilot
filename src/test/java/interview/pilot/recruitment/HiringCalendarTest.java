package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import interview.pilot.recruitment.application.CampaignModels.InvitationView;
import interview.pilot.recruitment.application.HiringCalendar;

class HiringCalendarTest {
  private final Instant time = Instant.parse("2026-09-10T02:00:00Z");
  private InvitationView invitation(String status, int sequence, Instant planned) {
    return new InvitationView("cbd151c7-0c95-44d3-bef5-530eb8ac68fd", "企业中文😀".repeat(30),
        "Java;后端,开发\\实习\r\nBEGIN:VEVENT", 1, status, time, time.plusSeconds(3600),
        time.plusSeconds(5400), 30, planned, "Asia/Shanghai", sequence, 0);
  }
  @Test void producesPrivateUtcEventWithEscapedTextAndUtf8SafeFolding() {
    String text = new String(HiringCalendar.render(invitation("ACCEPTED", 2, time), time), StandardCharsets.UTF_8);
    for (String line : text.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    String unfolded = text.replace("\r\n ", "");
    assertThat(unfolded).contains("DTSTART:20260910T020000Z\r\n", "DTEND:20260910T023000Z\r\n", "SEQUENCE:2\r\n",
        "Java\\;后端\\,开发\\\\实习\\nBEGIN:VEVENT", "CLASS:PRIVATE\r\n", "STATUS:CONFIRMED\r\n", "企业中文😀".repeat(30));
    assertThat(unfolded.split("\r\nBEGIN:VEVENT\r\n", -1)).hasSize(2);
    assertThat(text.replace("\r\n", "")).doesNotContain("\r", "\n", "�");
  }
  @Test void cancellationAndReschedulingKeepTheSameUidAndAdvanceSequence() {
    String initial = new String(HiringCalendar.render(invitation("ACCEPTED", 1, time), time), StandardCharsets.UTF_8);
    String cancelled = new String(HiringCalendar.render(invitation("CANCELLED", 3, time), time), StandardCharsets.UTF_8);
    String uid = "UID:cbd151c7-0c95-44d3-bef5-530eb8ac68fd@interview-pilot\r\n";
    assertThat(initial).contains(uid); assertThat(cancelled).contains(uid, "SEQUENCE:3\r\n", "STATUS:CANCELLED\r\n");
    assertThatThrownBy(() -> HiringCalendar.render(invitation("ISSUED", 0, null), time)).hasMessageContaining("请先安排");
  }
}
