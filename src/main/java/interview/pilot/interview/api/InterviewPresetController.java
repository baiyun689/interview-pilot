package interview.pilot.interview.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.interview.preset.InterviewPresetCatalog;

@RestController
@RequestMapping("/api/interview-presets")
public class InterviewPresetController {
  private final InterviewPresetCatalog presets;

  public InterviewPresetController(InterviewPresetCatalog presets) {
    this.presets = presets;
  }

  @GetMapping
  public List<PresetResponse> list() {
    return presets.list().stream().map(PresetResponse::from).toList();
  }

  public record PresetResponse(
      String id,
      String displayName,
      String description,
      String jobTitle,
      String jobDescription,
      String presetVersion) {
    static PresetResponse from(interview.pilot.interview.preset.InterviewPreset preset) {
      return new PresetResponse(
          preset.id(), preset.displayName(), preset.description(),
          preset.jobTitle(), preset.jobDescription(), preset.version());
    }
  }
}
