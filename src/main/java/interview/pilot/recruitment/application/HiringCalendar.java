package interview.pilot.recruitment.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import static interview.pilot.recruitment.application.CampaignModels.InvitationView;

/** Private, downloadable event snapshot (RFC 5545), never an email invitation. */
public final class HiringCalendar {
  private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private HiringCalendar() {}

  public static byte[] render(InvitationView invitation, Instant generatedAt) {
    if (invitation.plannedAt() == null) throw HiringAccess.conflict("请先安排面试时间");
    boolean cancelled = List.of("DECLINED", "CANCELLED", "EXPIRED").contains(invitation.status());
    var lines = List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Interview Pilot//Hiring//ZH",
        "CALSCALE:GREGORIAN", "BEGIN:VEVENT",
        "UID:" + UUID.fromString(invitation.id()) + "@interview-pilot",
        "DTSTAMP:" + UTC.format(generatedAt), "SEQUENCE:" + invitation.scheduleRevision(),
        "DTSTART:" + UTC.format(invitation.plannedAt()),
        "DTEND:" + UTC.format(invitation.plannedAt().plusSeconds(invitation.durationMinutes() * 60L)),
        "SUMMARY:" + escape(invitation.organizationName() + " · " + invitation.jobTitle() + " · 第 " + invitation.roundNo() + " 轮面试"),
        "DESCRIPTION:" + escape("请登录 Interview Pilot 的面试邀请页面查看最新安排。改期后请重新导入日历；以站内日程为准。"),
        "CLASS:PRIVATE", "STATUS:" + (cancelled ? "CANCELLED" : "CONFIRMED"),
        "END:VEVENT", "END:VCALENDAR");
    var output = new StringBuilder();
    for (String line : lines) {
      int octets = 0;
      for (int codePoint : line.codePoints().toArray()) {
        String character = new String(Character.toChars(codePoint));
        int size = character.getBytes(StandardCharsets.UTF_8).length;
        if (octets + size > 75) { output.append("\r\n "); octets = 1; }
        output.append(character); octets += size;
      }
      output.append("\r\n");
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
        .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
  }
}
