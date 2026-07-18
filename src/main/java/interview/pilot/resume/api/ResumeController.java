package interview.pilot.resume.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.resume.application.ResumeQueryService;
import interview.pilot.resume.application.ResumeUploadService;
import interview.pilot.resume.application.ResumeUploadService.UploadResumeResult;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
  private final ResumeUploadService uploadService;
  private final ResumeQueryService queryService;

  public ResumeController(ResumeUploadService uploadService, ResumeQueryService queryService) {
    this.uploadService = uploadService;
    this.queryService = queryService;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public ResponseEntity<UploadResumeResult> upload(@RequestPart("file") MultipartFile file) {
    UploadResumeResult result = uploadService.upload(file);
    HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status).body(result);
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<ResumeResponse> list() {
    return queryService.list();
  }

  @GetMapping("/{id}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public ResumeResponse get(@PathVariable("id") Long resumeId) {
    return queryService.get(resumeId);
  }
}
