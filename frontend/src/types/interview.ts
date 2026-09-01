export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type InterviewSize = 'QUICK' | 'STANDARD' | 'DEEP'
export type JobSourceType = 'PRESET' | 'CUSTOM'
export type SessionStatus = 'PREPARING' | 'READY' | 'INTERVIEWING' | 'EVALUATING' | 'COMPLETED' | 'PREPARATION_FAILED' | 'EVALUATION_FAILED'
export type TurnStatus = 'ASKED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export type InterviewPhase = 'SELF_INTRODUCTION' | 'FUNDAMENTALS' | 'PROJECT_EXPERIENCE' | 'SCENARIO_TRADEOFF'
export type QuestionType = 'SELF_INTRODUCTION' | 'MAIN' | 'FOLLOW_UP'

export interface InterviewPreset {
  id: string
  displayName: string
  description: string
  jobTitle: string
  jobDescription: string
  presetVersion: string
}

export interface InterviewTurn {
  turnNo: number
  status: TurnStatus
  phase: InterviewPhase
  questionType: QuestionType
  question: string
  askedAt: string
  answer: string | null
  answeredAt: string | null
}

export interface InterviewSession {
  sessionId: string
  resumeId: number | null
  jobTitle: string
  jdText: string
  status: SessionStatus
  difficulty: Difficulty
  interviewSize: InterviewSize
  jobSourceType: JobSourceType
  currentTurnNo: number
  totalTurnBudget: number
  providerId: string
  modelName: string
  preparationTaskId: string | null
  safeError: string | null
  turns: InterviewTurn[]
}

export interface InterviewHistory {
  sessionId: string
  jobTitle: string
  status: SessionStatus
  difficulty: Difficulty
  interviewSize: InterviewSize
  jobSourceType: JobSourceType
  currentTurnNo: number
  totalTurnBudget: number
  providerId: string
  modelName: string
  safeError: string | null
  createdAt: string
  completedAt: string | null
}

export interface CreateInterviewInput {
  resumeId: number | null
  jobSource:
    | { type: 'PRESET'; presetId: string }
    | { type: 'CUSTOM'; jobTitle: string; jobDescription: string }
  difficulty: Difficulty
  interviewSize: InterviewSize
  providerId: string
  knowledgeBaseIds: string[]
}

export interface CreateInterviewResult {
  sessionId: string
  status: 'PREPARING'
  preparationTaskId: string
}

export type InterviewEventType = 'ACCEPTED' | 'PROCESSING' | 'RESULT' | 'ERROR'
interface StreamEvent<T extends InterviewEventType, P> { type: T; sessionId: string; turnNo: number; payload: P }
export type InterviewStreamEvent =
  | StreamEvent<'ACCEPTED', { requestId: string; replayed: boolean }>
  | StreamEvent<'PROCESSING', { state: string }>
  | StreamEvent<'RESULT', { completedTurnNo: number; status: SessionStatus; nextTurn: InterviewTurn | null; idempotentReplay: boolean }>
  | StreamEvent<'ERROR', { code: string; message: string; retryable: boolean }>

export interface InterviewReportStatus {
  sessionId: string
  sessionStatus: SessionStatus
  taskId: string | null
  taskStatus: 'PENDING' | 'PUBLISHED' | 'COMPLETED' | 'FAILED' | 'DEAD'
  error: string | null
  retryable: boolean
}

export interface FixedInterviewReport {
  overallScore: number
  phaseScores: Record<InterviewPhase, number>
  strengths: string[]
  improvements: string[]
  technicalReferences: { sourceId: string; note: string }[]
  conflictNotes: string[]
  summary: string
  ragAvailability: Partial<Record<InterviewPhase, string>>
}

export interface InterviewReportResult {
  sessionId: string
  reportId: string
  report: FixedInterviewReport
  createdAt: string
}
