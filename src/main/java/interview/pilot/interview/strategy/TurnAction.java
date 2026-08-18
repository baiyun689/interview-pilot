package interview.pilot.interview.strategy;

/** 本轮指令的动作语义。 */
public enum TurnAction {
  /** 继续提问：必须携带目标能力和证据目标。 */
  ASK,
  /** 结束面试：必须携带结束原因和未完成证据摘要。 */
  FINISH
}
