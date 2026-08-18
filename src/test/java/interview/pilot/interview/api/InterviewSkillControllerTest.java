package interview.pilot.interview.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;

class InterviewSkillControllerTest {
  @Test
  void listsSafeSkillSummariesWithoutInternalPromptMaterial() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new InterviewSkillController(new ClasspathInterviewSkillCatalog())).build();

    mvc.perform(get("/api/interview-skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(8)))
        .andExpect(jsonPath("$[0].id").value("ai-agent-dev"))
        .andExpect(jsonPath("$[0].displayName").value("AI Agent 开发"))
        .andExpect(jsonPath("$[0].group").value("JOB"))
        .andExpect(jsonPath("$[0].defaultCompetencies", hasSize(0)))
        .andExpect(jsonPath("$[0].persona").doesNotExist())
        .andExpect(jsonPath("$[0].rubric").doesNotExist())
        .andExpect(jsonPath("$[0].references").doesNotExist())
        .andExpect(jsonPath("$[0].version").value(not(containsString("Java 后端评分标准"))));
  }
}
