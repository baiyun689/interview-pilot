export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type SessionStatus = 'CREATED' | 'INTERVIEWING' | 'EVALUATING' | 'COMPLETED' | 'FAILED'
export type TurnStatus = 'ASKED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export type NextStep = 'FOLLOW_UP' | 'NEXT_TOPIC' | 'FINISH'
export type DifficultyAdjustment = 'INCREASE' | 'KEEP' | 'DECREASE'

export interface InterviewDecision {
  nextStep: NextStep
  difficultyAdjustment: DifficultyAdjustment
  targetCompetency: string
  probeFocus: string
  reason: string
  confidence: number
}

export interface InterviewTurn {
  requestId: string | null
  turnNo: number
  status: TurnStatus
  difficulty: Difficulty
  question: string
  targetCompetency: string
  askedAt: string
  answer: string | null
  feedback: string | null
  score: number | null
  evidence: string[]
  missingPoints: string[]
  redFlags?: string[]
  decision: InterviewDecision | null
  nextDifficulty: Difficulty | null
  answeredAt: string | null
  processingError: string | null
}

export interface InterviewSession {
  sessionId: string
  resumeId: number
  jobTitle: string
  jdText: string
  status: SessionStatus
  difficulty: Difficulty
  currentTurnNo: number
  totalTurnBudget: number
  providerId: string
  modelName: string
  skillId: string
  skillName: string
  skillVersion: string
  plan: { competencies: string[]; totalTurnBudget: number }
  turns: InterviewTurn[]
}

export interface InterviewHistory {
  sessionId: string
  jobTitle: string
  status: SessionStatus
  difficulty: Difficulty
  currentTurnNo: number
  totalTurnBudget: number
  providerId: string
  modelName: string
  skillId: string
  skillName: string
  createdAt: string
  completedAt: string | null
}

export interface CreateInterviewInput {
  resumeId: number | null
  jobTitle: string
  jdText: string
  difficulty: Difficulty
  totalTurnBudget: number
  providerId: string
  skillId: string
  knowledgeBaseIds: string[]
}

export type InterviewSkillGroup = 'JOB' | 'SPECIALTY' | 'CUSTOM'
export interface InterviewSkill {
  id: string
  displayName: string
  description: string
  group: InterviewSkillGroup
  icon: string
  defaultCompetencies: string[]
  version: string
}

export interface InterviewPreset {
  id: string
  displayName: string
  description: string
  version: string
}

export type InterviewEventType = 'ACCEPTED' | 'FEEDBACK' | 'DECISION' | 'NEXT_QUESTION' | 'COMPLETED' | 'ERROR'
interface StreamEvent<T extends InterviewEventType, P> { type: T; sessionId: string; turnNo: number; payload: P }
export type InterviewStreamEvent =
  | StreamEvent<'ACCEPTED', { requestId: string; replayed: boolean }>
  | StreamEvent<'FEEDBACK', { score: number; feedback: string; evidence: string[]; missingPoints: string[]; redFlags?: string[] }>
  | StreamEvent<'DECISION', { decision: InterviewDecision }>
  | StreamEvent<'NEXT_QUESTION', { question: string; targetCompetency: string; difficulty: Difficulty }>
  | StreamEvent<'COMPLETED', { status: SessionStatus }>
  | StreamEvent<'ERROR', { code: string; message: string; retryable: boolean }>

export interface InterviewReportStatus {
  sessionId: string
  sessionStatus: SessionStatus
  taskId: string | null
  taskStatus: 'PENDING' | 'PUBLISHED' | 'COMPLETED' | 'FAILED' | 'DEAD'
  error: string | null
  retryable: boolean
}

export interface InterviewReportResult {
  sessionId: string
  reportId: string
  report: { overallScore: number; competencyScores: Record<string, number>; strengths: string[]; improvements: string[]; summary: string }
  createdAt: string
}
