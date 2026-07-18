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

export interface ResumeDetail extends ResumeSummary {
  profile: ResumeProfile | null
  analysisError: string | null
}

export interface ResumeUploadResult {
  resumeId: number
  analysisTaskId: string
  duplicate: boolean
}
