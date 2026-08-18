package interview.pilot.interview.strategy;

/** 单个能力在本场面试中的证据收集状态。 */
public enum CompetencyStatus {
  /** 仍有证据缺口且未达到追问上限，可以继续考察。 */
  OPEN,
  /** 全部必收证据已观察（或旧格式轮次按老口径判定已覆盖）。 */
  SUFFICIENT,
  /** 连续追问达到上限仍未补齐证据，按尽力处理，不再产生有效追问。 */
  EXHAUSTED
}
