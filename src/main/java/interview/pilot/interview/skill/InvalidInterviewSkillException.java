package interview.pilot.interview.skill;

import org.springframework.http.HttpStatus;

import interview.pilot.common.exception.BusinessException;

public class InvalidInterviewSkillException extends BusinessException {
  public InvalidInterviewSkillException(String skillId) {
    super(
        "INVALID_INTERVIEW_SKILL",
        "未找到面试方向: " + (skillId == null ? "" : skillId),
        HttpStatus.BAD_REQUEST);
  }
}
