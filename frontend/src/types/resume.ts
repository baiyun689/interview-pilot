export type ResumeStatus = 'PENDING' | 'ANALYZING' | 'READY' | 'FAILED'

export interface ResumeProject {
  name: string
  description: string
  technologies: string[]
}

export interface ResumeProfile {
  summary: string
  technicalSkills: string[]
  projects: ResumeProject[]
  strengths: string[]
  risks: string[]
}

export interface ResumeSummary {
  id: number
  originalFilename: string
  status: ResumeStatus
  duplicate: boolean
  analysisTaskId: string
  createdAt: string
}

export interface ResumeScoreDetail {
  projectScore: number
  skillMatchScore: number
  contentScore: number
  structureScore: number
  expressionScore: number
}

export interface ResumeSuggestion {
  category: string
  priority: '高' | '中' | '低'
  issue: string
  recommendation: string
}

export interface ResumeEvaluation {
  overallScore: number
  scoreDetail: ResumeScoreDetail
  suggestions: ResumeSuggestion[]
}

export interface ResumeDetail extends ResumeSummary {
  profile: ResumeProfile | null
  evaluation: ResumeEvaluation | null
  analysisError: string | null
}

export interface ResumeUploadResult {
  resumeId: number
  analysisTaskId: string
  duplicate: boolean
}
